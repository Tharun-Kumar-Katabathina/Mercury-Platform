#!/usr/bin/env python3
"""Black-box security tests against the running platform (through the API gateway, and directly against services).

  scripts/security/run.py [--gateway http://localhost:8090] [--keys .secrets] [--write-report]

Environment: ADMIN_EMAIL / ADMIN_PASSWORD (the bootstrap administrator), as in .env.
Every check states what it does and what it expects; the process exits non-zero if any expectation fails, and
with --write-report the results are written to docs/security-results.md.

It forges tokens itself (with the real signing key from .secrets when present, to build an *expired but otherwise
valid* token, and with a throw-away key for a *wrong signature*): the point is to prove the platform rejects them.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
GATEWAY = "http://localhost:8090"
DIRECT = {"product": "http://localhost:8081", "inventory": "http://localhost:8082", "order": "http://localhost:8083",
          "notification": "http://localhost:8084", "user": "http://localhost:8085"}
results = []


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def req(method, url, body=None, token=None, headers=None, timeout=20, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    r = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    if token:
        r.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            text = resp.read().decode()
            return resp.status, (json.loads(text) if text.startswith(("{", "[")) else text), dict(resp.headers)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text), dict(e.headers)
        except ValueError:
            return e.code, text, dict(e.headers)
    except Exception as e:
        return 0, str(e), {}


def check(group, name, expectation, ok, actual):
    results.append(dict(group=group, name=name, expectation=expectation, actual=str(actual)[:160], ok=bool(ok)))
    print(f"  {'PASS' if ok else 'FAIL'}  {group:<24} {name}: {str(actual)[:110]}", flush=True)


def sign_rs256(private_pem_path, header, claims):
    signing_input = f"{b64(json.dumps(header).encode())}.{b64(json.dumps(claims).encode())}"
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", private_pem_path], input=signing_input.encode(), capture_output=True, check=True).stdout
    return f"{signing_input}.{b64(sig)}"


def forged_tokens(keys_dir):
    """tokens a real attacker could build, with a verdict of what each one is"""
    now = int(time.time())
    base = {"iss": "mercury-user-service", "aud": ["mercury"], "sub": "attacker", "roles": ["USER"], "iat": now - 10, "nbf": now - 10,
            "exp": now + 600, "jti": str(uuid.uuid4())}
    out = {}
    stranger = tempfile.NamedTemporaryFile(suffix=".pem", delete=False)
    stranger.close()
    subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", stranger.name], capture_output=True, check=True)
    out["signed by an unknown key"] = sign_rs256(stranger.name, {"alg": "RS256", "typ": "JWT"}, {**base, "roles": ["ADMIN"]})
    out["alg=none (unsigned)"] = f"{b64(json.dumps({'alg': 'none', 'typ': 'JWT'}).encode())}.{b64(json.dumps({**base, 'roles': ['ADMIN']}).encode())}."
    real = os.path.join(keys_dir, "jwt-private.pem")
    if os.path.exists(real):
        out["expired (correct signature)"] = sign_rs256(real, {"alg": "RS256", "typ": "JWT"}, {**base, "exp": now - 3600, "iat": now - 7200, "nbf": now - 7200})
        out["wrong audience (correct signature)"] = sign_rs256(real, {"alg": "RS256", "typ": "JWT"}, {**base, "aud": ["another-app"]})
        out["wrong issuer (correct signature)"] = sign_rs256(real, {"alg": "RS256", "typ": "JWT"}, {**base, "iss": "evil-idp"})
    os.unlink(stranger.name)
    out["garbage"] = "this.is.notajwt"
    return out


def tamper_roles(token):
    """keep the signature, change the payload to claim ADMIN"""
    head, payload, sig = token.split(".")
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    claims["roles"] = ["ADMIN", "USER"]
    return f"{head}.{b64(json.dumps(claims).encode())}.{sig}"


def main():
    global GATEWAY
    args = sys.argv[1:]
    keys = os.path.join(ROOT, ".secrets")
    if "--gateway" in args:
        GATEWAY = args[args.index("--gateway") + 1]
    if "--keys" in args:
        keys = args[args.index("--keys") + 1]
    admin_email = os.environ.get("ADMIN_EMAIL", "admin@mercury.local")
    admin_password = os.environ.get("ADMIN_PASSWORD", "")
    if not admin_password:
        sys.exit("ADMIN_PASSWORD is required (see .env)")

    print(f"\nsecurity checks through {GATEWAY}\n")
    # ---- setup: an admin, two customers, a product with stock ------------------------------------------------
    s, body, _ = req("POST", f"{GATEWAY}/api/v1/auth/login", {"email": admin_email, "password": admin_password})
    assert s == 200, f"admin login failed: {s} {body}"
    admin = body["accessToken"]
    users = {}
    for name in ("alice", "bob"):
        email = f"{name}-{uuid.uuid4().hex[:8]}@mercury.test"
        s, _, _ = req("POST", f"{GATEWAY}/api/v1/auth/register", {"email": email, "password": "a-long-enough-password-1"})
        assert s == 201, f"register {name}: {s}"
        s, body, _ = req("POST", f"{GATEWAY}/api/v1/auth/login", {"email": email, "password": "a-long-enough-password-1"})
        users[name] = (email, body["accessToken"])
    alice, bob = users["alice"][1], users["bob"][1]
    s, product, _ = req("POST", f"{GATEWAY}/api/v1/products", {"name": "Sec item", "sku": f"SEC-{uuid.uuid4().hex[:8]}", "price": 5.0, "quantity": 100}, admin)
    assert s == 201, f"create product: {s} {product}"
    pid = product["id"]
    s, _, _ = req("POST", f"{DIRECT['inventory']}/api/v1/inventory", {"productId": pid, "availableQuantity": 100}, admin)
    assert s == 201, f"create inventory: {s}"
    order_body = {"items": [{"productId": pid, "quantity": 1}]}

    # ---- 1. unauthenticated -----------------------------------------------------------------------------------
    g = "unauthenticated"
    for method, path in (("POST", "/api/v1/orders"), ("GET", f"/api/v1/orders/{uuid.uuid4()}"), ("POST", "/api/v1/products"),
                         ("GET", "/api/v1/notifications/orders/" + str(uuid.uuid4())), ("GET", "/api/v1/users/me")):
        s, b, h = req(method, GATEWAY + path, order_body if method == "POST" else None)
        check(g, f"{method} {path.split('/')[3] if path.count('/') > 2 else path} through the gateway", "401", s == 401 and isinstance(b, dict) and b.get("error") == "UNAUTHORIZED", f"{s} {b.get('error') if isinstance(b, dict) else b}")
    s, _, _ = req("GET", f"{GATEWAY}/api/v1/products")
    check(g, "the public catalogue is readable", "200", s == 200, s)
    for svc, path in (("order", "/api/v1/orders"), ("inventory", f"/api/v1/inventory/{pid}"), ("notification", "/api/v1/notifications/orders/" + str(uuid.uuid4()))):
        s, _, _ = req("POST" if svc == "order" else "GET", DIRECT[svc] + path, order_body if svc == "order" else None)
        check(g, f"{svc}-service directly, no token", "401", s == 401, s)

    # ---- 2. invalid / expired / forged tokens --------------------------------------------------------------------
    g = "invalid tokens"
    for label, token in forged_tokens(keys).items():
        s, _, _ = req("GET", f"{GATEWAY}/api/v1/orders/{uuid.uuid4()}", token=token)
        s2, _, _ = req("GET", f"{DIRECT['order']}/api/v1/orders/{uuid.uuid4()}", token=token)
        check(g, label, "401 at the gateway and at the service", s == 401 and s2 == 401, f"gateway {s}, service {s2}")
    s, _, _ = req("GET", f"{GATEWAY}/api/v1/orders/{uuid.uuid4()}", token=tamper_roles(alice))
    check(g, "payload edited to claim ADMIN, signature kept", "401", s == 401, s)

    # ---- 3. wrong role / privilege escalation ----------------------------------------------------------------------
    g = "roles and escalation"
    s, _, _ = req("POST", f"{GATEWAY}/api/v1/products", {"name": "x", "sku": f"E-{uuid.uuid4().hex[:6]}", "price": 1, "quantity": 1}, alice)
    check(g, "a customer creates a product", "403", s == 403, s)
    s, _, _ = req("DELETE", f"{GATEWAY}/api/v1/products/{pid}", token=alice)
    check(g, "a customer deletes a product", "403", s == 403, s)
    s, _, _ = req("GET", f"{GATEWAY}/api/v1/inventory/{pid}", token=admin)
    check(g, "inventory through the gateway, even as admin", "404 (no route)", s == 404, s)
    s, _, _ = req("GET", f"{DIRECT['inventory']}/api/v1/inventory/{pid}", token=alice)
    check(g, "a customer reads stock directly", "403", s == 403, s)
    s, _, _ = req("POST", f"{DIRECT['inventory']}/api/v1/inventory/{pid}/reserve", {"quantity": 1000}, alice, {"Idempotency-Key": "x"})
    check(g, "a customer reserves stock directly", "403", s == 403, s)
    s, _, _ = req("POST", f"{GATEWAY}/api/v1/auth/service-token", {"clientId": "order-service", "clientSecret": "guess"}, admin)
    check(g, "the service-token endpoint through the edge", "403/401 (internal only)", s in (401, 403), s)
    s, b, _ = req("POST", f"{GATEWAY}/api/v1/auth/register", {"email": f"mallory-{uuid.uuid4().hex[:6]}@mercury.test", "password": "a-long-enough-password-1", "roles": ["ADMIN"]})
    ok = s == 201 and b.get("roles") == ["USER"]
    check(g, "registering with roles=[ADMIN] in the body", "created as USER only", ok, b.get("roles") if isinstance(b, dict) else b)
    s, b, _ = req("POST", f"{GATEWAY}/api/v1/orders", order_body, alice, {"Idempotency-Key": f"own-{uuid.uuid4()}"})
    oid = b.get("id") if isinstance(b, dict) else None
    s2, _, _ = req("GET", f"{GATEWAY}/api/v1/orders/{oid}", token=bob)
    s3, _, _ = req("GET", f"{GATEWAY}/api/v1/orders/{uuid.uuid4()}", token=bob)
    check(g, "reading another customer's order", "404, same as a missing order", s2 == 404 and s3 == 404, f"foreign {s2}, missing {s3}")
    s4, _, _ = req("GET", f"{GATEWAY}/api/v1/orders/{oid}", token=admin)
    check(g, "an administrator reads any order", "200", s4 == 200, s4)

    # ---- 4. malformed input ----------------------------------------------------------------------------------------
    g = "malformed input"
    for label, kw in (("not JSON", dict(raw=b"{ nope")), ("empty body", dict(raw=b"")), ("wrong types", dict(body={"items": "x"})),
                      ("negative quantity", dict(body={"items": [{"productId": pid, "quantity": -5}]})),
                      ("zero items", dict(body={"items": []})), ("huge quantity", dict(body={"items": [{"productId": pid, "quantity": 2**40}]}))):
        s, b, _ = req("POST", f"{GATEWAY}/api/v1/orders", token=alice, headers={"Idempotency-Key": f"m-{uuid.uuid4()}"}, **kw)
        leaks = isinstance(b, dict) and any(k in b for k in ("trace", "stackTrace", "exception"))
        check(g, label, "400, no stack trace", s == 400 and not leaks, f"{s}{' LEAK' if leaks else ''}")
    s, b, _ = req("GET", f"{GATEWAY}/api/v1/orders/not-a-uuid", token=alice)
    check(g, "a non-UUID id", "400", s == 400, s)
    s, b, _ = req("POST", f"{GATEWAY}/api/v1/orders", raw=b"x" * 2_000_000, token=alice, headers={"Idempotency-Key": "big"})
    check(g, "a 2 MB body", "413 (refused at the edge)", s == 413, s)
    # a chunked body declares no length, so the edge counts it as it streams to the service: the same 413, the same cap
    s, b, _ = req("POST", f"{GATEWAY}/api/v1/orders", raw=(b"x" * 65536 for _ in range(30)), token=alice, headers={"Idempotency-Key": "big-chunked"})
    check(g, "a 2 MB chunked body", "413 (counted at the edge)", s == 413, s)
    s, _, _ = req("POST", f"{GATEWAY}/api/v1/orders", order_body, alice, {"Idempotency-Key": "k" * 300})
    check(g, "an over-long Idempotency-Key", "400", s == 400, s)

    # ---- 5. injection ------------------------------------------------------------------------------------------------
    g = "injection"
    evil = "x'); DROP TABLE products; --"
    s, b, _ = req("POST", f"{GATEWAY}/api/v1/products", {"name": evil, "sku": f"INJ-{uuid.uuid4().hex[:6]}", "price": 1, "quantity": 1}, admin)
    check(g, "SQL in a product name", "stored as plain text", s == 201 and b.get("name") == evil, f"{s} {b.get('name') if isinstance(b, dict) else b}")
    s, b, _ = req("GET", f"{GATEWAY}/api/v1/products")
    check(g, "the products table survived", "200 and still listing", s == 200 and isinstance(b, list) and len(b) >= 1, f"{s}, {len(b) if isinstance(b, list) else b} rows")
    for label, path in (("OR 1=1 as an id", "/api/v1/orders/1' OR '1'='1"), ("UNION in an id", "/api/v1/products/1 UNION SELECT * FROM users")):
        s, b, _ = req("GET", GATEWAY + path.replace(" ", "%20").replace("'", "%27"), token=alice)
        check(g, label, "refused (400/401/403/404), no database error text", s in (400, 401, 403, 404) and "SQL" not in str(b) and "syntax" not in str(b).lower(), s)
    s, b, _ = req("POST", f"{GATEWAY}/api/v1/auth/login", {"email": "admin@mercury.local' OR '1'='1", "password": "' OR '1'='1"})
    check(g, "SQL in the login form", "400/401", s in (400, 401), s)
    s, b, _ = req("POST", f"{GATEWAY}/api/v1/orders", order_body, alice, {"Idempotency-Key": "k'; DELETE FROM orders; --"})
    check(g, "SQL in the Idempotency-Key", "handled as an opaque string", s in (201, 200, 202), s)

    # ---- 6. duplicate requests ----------------------------------------------------------------------------------------
    g = "duplicates"
    key = f"dup-{uuid.uuid4()}"
    first = req("POST", f"{GATEWAY}/api/v1/orders", order_body, alice, {"Idempotency-Key": key})
    again = [req("POST", f"{GATEWAY}/api/v1/orders", order_body, alice, {"Idempotency-Key": key}) for _ in range(4)]
    same = all(a[1].get("id") == first[1].get("id") for a in again if isinstance(a[1], dict))
    check(g, "the same order sent 5 times", "one order, replays flagged", first[0] in (201, 202) and same and all(a[2].get("Idempotent-Replayed") == "true" or a[2].get("idempotent-replayed") == "true" for a in again), f"{[a[0] for a in again]}")
    s, b, _ = req("POST", f"{GATEWAY}/api/v1/orders", {"items": [{"productId": pid, "quantity": 2}]}, alice, {"Idempotency-Key": key})
    check(g, "same key, different payload", "422", s == 422, s)
    s, b, _ = req("POST", f"{GATEWAY}/api/v1/orders", order_body, bob, {"Idempotency-Key": key})
    check(g, "another customer reusing that key", "their own new order", s in (201, 202) and b.get("id") != first[1].get("id"), s)

    # ---- 7. rate limiting / brute force ----------------------------------------------------------------------------
    g = "abuse"
    victim = users["alice"][0]
    codes = [req("POST", f"{GATEWAY}/api/v1/auth/login", {"email": victim, "password": f"wrong-password-{i}"})[0] for i in range(30)]
    check(g, "30 password guesses in a burst", "401s then 429", 429 in codes and codes[0] == 401, f"first {codes[0]}, any 429 {429 in codes}")
    s, _, h = req("POST", f"{GATEWAY}/api/v1/auth/login", {"email": victim, "password": "a-long-enough-password-1"})
    check(g, "after that, even the right password waits", "429 with Retry-After", s == 429 and any(k.lower() == "retry-after" for k in h), s)

    ok_count = sum(r["ok"] for r in results)
    print(f"\n{ok_count}/{len(results)} checks passed")
    if "--write-report" in args:
        write_report()
    return 0 if ok_count == len(results) else 1


def write_report():
    lines = ["# Security test results", "",
             "Generated by `scripts/security/run.py` against the running platform (through the API gateway, and directly against "
             "the services where noted). Every row is an attack or abuse attempt and the response the platform is required to give.", "",
             f"Run on {time.strftime('%Y-%m-%d %H:%M')}: **{sum(r['ok'] for r in results)}/{len(results)} passed**.", "",
             "| Area | Attempt | Required | Observed | Result |", "|---|---|---|---|---|"]
    for r in results:
        lines.append(f"| {r['group']} | {r['name']} | {r['expectation']} | {r['actual'].replace('|', '/')} | {'✅' if r['ok'] else '❌'} |")
    open(os.path.join(ROOT, "docs", "security-results.md"), "w").write("\n".join(lines) + "\n")
    print("wrote docs/security-results.md")


if __name__ == "__main__":
    sys.exit(main())
