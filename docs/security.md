# Security (Phase 15)

Mercury is secure by default: every request must carry a valid token unless it is explicitly public, every service
checks the token itself (not only the edge), roles decide what a caller may do, and no password or key lives in the
repository. This document describes what is implemented, how it was tested, and what is not covered.

## 1. The shape of it

```
Browser / client
   │  HTTPS (TLS terminates in front of the gateway)
   ▼
API Gateway :8090 ── verifies the token ── rate limits (per address, per user) ── CORS ── body-size cap ── security headers
   │  forwards the same token
   ├─> User Service :8085        accounts, login, token issuing (signs with the PRIVATE key)
   ├─> Product Service :8081     ┐
   ├─> Order Service :8083       │ each verifies the token again with the PUBLIC key (defence in depth)
   ├─> Notification Service :8084│ and enforces its own role rules
   └─> Recommendation :8086      ┘
Inventory Service :8082          internal: no route at the gateway; only services and administrators may call it
```

Services call each other with **service tokens** (role `SERVICE`), obtained from the user-service with a per-service
client id and secret, cached, and renewed shortly before they expire.

## 2. Tokens

- **RS256 JWT**, signed by the user-service with an RSA private key that never leaves it. Every other component holds
  only the public key (`JWT_PUBLIC_KEY_FILE`), so verifying a token needs no network call: the platform keeps
  authorising requests while the user-service is down (tested, scenario F18 in [failure-matrix.md](failure-matrix.md)).
- Claims: `iss` (`mercury-user-service`), `aud` (`mercury`), `sub` (user id, or `service:<name>`), `roles`, `iat`, `nbf`,
  `exp`, `jti`. A token is refused unless the signature, issuer, audience and the time window are all right.
- Lifetimes: customers 15 minutes, services 10 minutes (`JWT_USER_TTL`, `JWT_SERVICE_TTL`). There is no refresh token and
  no server-side session, so a stolen token expires on its own and logout is "discard the token".
- Key pair generation: `scripts/generate-jwt-keys.sh` (written to the git-ignored `.secrets/`, mounted as Docker/Kubernetes
  secrets). The user-service refuses to start without keys (`JWT_REQUIRE_KEYS=true`); a throw-away pair is only generated
  when that is explicitly switched off for local development.
- `GET /.well-known/jwks.json` publishes the public key (never the private one).

## 3. Who may do what

| Role | Who | Can |
|---|---|---|
| (none) | anyone | read the product catalogue and popular recommendations; register; log in |
| `USER` | a customer | place orders, read **their own** orders, get recommendations, record views |
| `ADMIN` | an operator | everything a customer can, plus: create/update/delete products, create stock, read any order, notifications, operational endpoints |
| `SERVICE` | another Mercury service | reserve/release stock, read orders and notifications, operational endpoints. Cannot place customer orders |

| Endpoint | Anonymous | USER | ADMIN | SERVICE |
|---|---|---|---|---|
| `GET /products`, `/products/{id}` | ✅ | ✅ | ✅ | ✅ |
| `POST/PUT/DELETE /products` | 401 | 403 | ✅ | 403 |
| `POST /orders` | 401 | ✅ | ✅ | 403 |
| `GET /orders/{id}` | 401 | own only (else 404) | any | any |
| `GET/POST /inventory/**` | 401 | 403 | create/read | reserve/release/read |
| `GET /notifications/**` | 401 | 403 | ✅ | ✅ |
| `GET /recommendations/me`, `POST /interactions` | 401 | ✅ | ✅ | 403 |
| `/actuator/health/**`, `/actuator/prometheus` | ✅ | ✅ | ✅ | ✅ |
| other `/actuator/**` | 401 | 403 | ✅ | ✅ |
| anything not listed | 401 | 403 | 403 | 403 |

Rules are in each service's `security/SecurityConfiguration`. **Anything not listed is denied**, so a new endpoint is
closed until someone opens it on purpose. Security failures return the same JSON error shape as everything else
(`UNAUTHORIZED` / `FORBIDDEN`), never a stack trace.

### Customer data is private

- An order records its customer (the token subject). Another customer's order is reported as **404**, byte-for-byte like
  an order that does not exist, so ids cannot be probed.
- **Idempotency keys are per customer.** With authentication the claim is stored under a hash of customer + key, so two
  customers who pick the same key get two separate orders and one can never be handed the other's.
- Recommendation behaviour is recorded against the token subject only: nobody can record or read on someone else's behalf, and
  a client cannot claim a *purchase* (those come only from confirmed orders).

## 4. The edge (API gateway)

Spring Cloud Gateway (MVC). In order, for every request:

1. **Body-size cap** (default 1 MB, by declared length) → `413` before anything is forwarded.
2. **Per-address rate limit** *before* authentication (default burst 200, 100/s; credential endpoints 20 per minute), so
   garbage tokens and password guessing cannot be hammered for free → `429` with `Retry-After`.
3. **CORS**: no origin is allowed unless listed in `CORS_ALLOWED_ORIGINS`; credentials (cookies) are never allowed.
4. **Authentication**: signature, issuer, audience, expiry. Public routes are listed explicitly.
5. **Per-user rate limit** *after* authentication (default burst 60, 30/s), so one customer cannot starve the others.
6. **Routing**: only the public API. `inventory` has no route; `/api/v1/auth/service-token` is denied at the edge.
7. **Security headers** on every response: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
   `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Referrer-Policy: no-referrer`,
   `Strict-Transport-Security`.

The rate limiter is a token bucket in memory per gateway instance (state is bounded). With N gateway replicas a client may
get up to N times the limit; a shared (Redis) limiter is the upgrade if exact global limits are needed.

## 5. Accounts and passwords

- Passwords: 10–72 characters (bcrypt truncates beyond 72 bytes, so longer is refused rather than silently cut), stored only as
  **bcrypt (cost 12)** hashes.
- Login is uniform: a wrong password, an unknown email, a locked or disabled account all give the same `401 INVALID_CREDENTIALS`
  with the same work done (a dummy hash is checked for unknown accounts), so neither the response nor its timing reveals which
  emails exist.
- **Lockout**: 5 wrong passwords lock the account for 15 minutes (database-backed, so it holds across instances and restarts).
- **Throttle**: 20 attempts per minute per address in the user-service, and the gateway's credential budget in front of it.
- Registering cannot grant roles: any `roles` in the request body are ignored; everyone starts as `USER`. The first administrator is
  created at start-up from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (both required, no defaults).

## 6. Secrets

| Secret | Where it lives | Never in |
|---|---|---|
| Database password, admin password, service secrets, Grafana password | git-ignored `.env` generated by `scripts/init-secrets.sh` (random); Kubernetes: a Secret generated from `secrets.env` (git-ignored) | the repository, images, logs |
| JWT private key | `.secrets/jwt-private.pem` → Docker/Kubernetes secret mounted **only into the user-service** | every other service, the gateway |
| JWT public key | `.secrets/jwt-public.pem` → mounted into every service | (it is not secret) |

- No default passwords remain in the code or the Compose files: `spring.datasource.password=${POSTGRES_PASSWORD}` has no fallback, so a
  service without a configured password refuses to start. The developer override no longer injects `mercury`.
- Service containers run as a non-root user with a read-only filesystem and no capabilities; secrets are files, not environment values
  (except where a framework requires it).
- `server.error.include-*=never`: error responses never contain messages from exceptions or stack traces.
- **History caveat:** earlier commits contained the development database password `mercury` as a Compose default. It was a local
  development value, but anything reachable with it should be rotated before this repository is ever made public; the CI pipeline
  runs a secret scan over the full history.

## 7. Supply chain

- **Container scan** (Trivy, HIGH and CRITICAL with a fix available) of every image. The first run found three critical Tomcat
  vulnerabilities (CVE-2026-65182, -65905, -68525; fixed in 11.0.25) and several high ones in Jackson 2.21.5 / 3.1.5 (fixed in 2.21.7 / 3.1.7)
  in all services; the poms now override the versions ahead of the Spring Boot release, and the scan is a CI gate.
- **Secret scan** (gitleaks) over the whole history, **configuration scan** and **dependency scan** (Trivy) in CI; Dependabot opens weekly
  updates for Maven, Docker and the pipeline's own actions.
- The base image is `eclipse-temurin:21-jre-alpine` (no JDK, no build tools, fixed non-root uid 10001).

## 8. How it was tested

- **Per service** (`security/SecurityApiTests`, 5 services, 38 tests): missing, garbage, expired, wrong-issuer, wrong-audience,
  foreign-key and unsigned (`alg=none`-style) tokens; each role against each endpoint; ownership and idempotency scoping;
  malformed input; SQL-injection strings; error bodies without internals.
- **User-service** (13): hashing never leaks, registration rules, uniform login errors, lockout, throttling, token claims, JWKS.
- **Gateway** (13): authentication before routing, nothing reaches a service unauthenticated, inventory has no route, CORS, size cap, headers,
  per-address and per-user rate limits.
- **Black-box suite against the running platform** (`scripts/security/run.py`, results in [security-results.md](security-results.md)):
  45 checks through the gateway and directly against services, including a token with an edited payload and an original signature, an
  expired token with a genuine signature, a 2 MB body, a 30-guess password burst, five identical orders and the same key from two customers.
- **Chaos** (F18, F19): the user-service dying does not affect logged-in customers; a flood of forged tokens is refused without hurting real orders.

## 9. Not covered (known limitations)

- **TLS** is not terminated by this repository (it expects an ingress or load balancer in front of the gateway); traffic between services
  inside the network is plain HTTP, and Kafka and PostgreSQL connections are unauthenticated-in-transit. A service mesh with mTLS would close that.
- No **token revocation** list: a token is valid until it expires (15 minutes). Disabling an account takes effect at the next login.
- No **refresh tokens**, no social login, no password reset or email verification, no MFA.
- **Key rotation** is manual (replace the pair and restart); `kid` is in every token, but verifiers hold a single static public key.
- The in-memory **rate limiter** is per instance (see section 4); the login lockout is shared.
- Kafka messages are not signed; anything that can write to the broker can forge events. Broker authentication and ACLs belong to the
  infrastructure phase.
- Authentication is **switched off for the load tests** (`SECURITY_ENABLED=false`), so the published performance numbers exclude token
  verification; [performance.md](performance.md) measures that overhead separately.
