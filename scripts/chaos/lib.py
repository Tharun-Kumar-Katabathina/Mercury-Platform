"""Black-box chaos harness for the Mercury platform (Phase 13).

Everything here talks to the real container stack: it injects failures with the Docker CLI (kill, pause, stop,
network disconnect, tc netem), generates load over HTTP, and verifies the outcome by reading the services'
PostgreSQL databases and Kafka directly. Nothing is mocked.
"""
import json
import os
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PROJECT = os.environ.get("CHAOS_PROJECT", "mercury-chaos")
SERVICES = ["product-service", "inventory-service", "order-service", "notification-service", "user-service"]
URLS = {"product": "http://localhost:8081", "inventory": "http://localhost:8082",
        "order": "http://localhost:8083", "notification": "http://localhost:8084", "user": "http://localhost:8085"}
PG_USER = "mercury"


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def sh(args, check=True, timeout=180, input=None, env=None):
    r = subprocess.run(args, capture_output=True, text=True, timeout=timeout, input=input, env=env, cwd=ROOT)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} -> {r.returncode}\n{r.stdout[-600:]}\n{r.stderr[-600:]}")
    return r


def container(service):
    return f"{PROJECT}-{service}-1"


class Stack:
    """Compose project 'mercury-chaos': production-like platform + chaos tuning + observability-free."""

    def __init__(self):
        # every secret (database, admin login, service credentials, JWT keys) is generated and remembered per project
        out = sh(["scripts/lib/platform-env.sh", PROJECT]).stdout
        self.secrets = dict(line.removeprefix("export ").split("=", 1) for line in out.splitlines() if "=" in line)
        self.password = self.secrets["POSTGRES_PASSWORD"]
        os.environ.update(self.secrets)
        self.mode = "SYNC"
        self.deadline = "12s"

    def _compose(self, *args, **kw):
        env = dict(os.environ, **self.secrets, ORDER_RESERVATION_MODE=self.mode,
                   ORDER_RESERVATION_ASYNC_DEADLINE=self.deadline)
        base = ["docker", "compose", "-p", PROJECT, "-f", "docker-compose.yml", "-f", "docker-compose.chaos.yml",
                "--profile", "platform"]
        return sh(base + list(args), env=env, **kw)

    def up(self, build=True):
        self._compose("up", "-d", *(["--build"] if build else []), timeout=900)
        self.wait_healthy()

    def down(self, volumes=True):
        self._compose("down", *(["-v"] if volumes else []), "--remove-orphans", check=False)

    def set_mode(self, mode, deadline="12s"):
        """Recreate Order Service with the given reservation mode (SYNC | ASYNC) and ASYNC waiting deadline."""
        if (mode, deadline) == (self.mode, self.deadline):
            return
        self.mode, self.deadline = mode, deadline
        self._compose("up", "-d", "--no-deps", "order-service", timeout=300)
        self.wait_healthy()

    # ---- failure injection ----------------------------------------------------------------------
    def kill(self, svc):          # SIGKILL: no shutdown hooks, exactly like a crash
        sh(["docker", "kill", container(svc)])

    def stop(self, svc):          # SIGTERM then wait: a graceful shutdown
        sh(["docker", "stop", container(svc)], timeout=120)

    def start(self, svc):
        sh(["docker", "start", container(svc)])

    def restart(self, svc):
        sh(["docker", "restart", container(svc)], timeout=180)

    def pause(self, svc):         # processes frozen, sockets stay open: the nastiest kind of "down"
        sh(["docker", "pause", container(svc)])

    def unpause(self, svc):
        sh(["docker", "unpause", container(svc)], check=False)

    def partition(self, svc):
        """Total packet loss on every interface of the container: connections to it hang, nothing answers, no RST.
        (tc netem rather than `docker network disconnect`, which would also tear down the published ports.)"""
        for iface in self._interfaces(svc):
            sh(["docker", "run", "--rm", f"--net=container:{container(svc)}", "--cap-add=NET_ADMIN",
                "mercury/netem", "qdisc", "replace", "dev", iface, "root", "netem", "loss", "100%"])

    def delay(self, svc, ms):     # latency on every interface of the container
        for iface in self._interfaces(svc):
            sh(["docker", "run", "--rm", f"--net=container:{container(svc)}", "--cap-add=NET_ADMIN",
                "mercury/netem", "qdisc", "replace", "dev", iface, "root", "netem", "delay", f"{ms}ms"])

    def clear_delay(self, svc):
        for iface in self._interfaces(svc):
            sh(["docker", "run", "--rm", f"--net=container:{container(svc)}", "--cap-add=NET_ADMIN",
                "mercury/netem", "qdisc", "del", "dev", iface, "root"], check=False)

    def _interfaces(self, svc):
        r = sh(["docker", "exec", container(svc), "sh", "-c", "ls /sys/class/net | grep '^eth'"], check=False)
        return r.stdout.split() or ["eth0"]

    def _state(self, svc):
        r = sh(["docker", "inspect", "-f", "{{.State.Running}} {{.State.Paused}}", container(svc)], check=False)
        running, _, paused = r.stdout.strip().partition(" ")
        return running == "true", paused == "true"

    def heal_everything(self):
        """Put the platform back to a fully healthy state, whatever a scenario did to it.
        Only acts on what is actually broken: on Docker Desktop, `docker start` on a running container drops its
        published ports."""
        for svc in SERVICES + ["postgres", "kafka", "qdrant"]:
            running, paused = self._state(svc)
            if paused:
                self.unpause(svc)
            if not running:
                self.start(svc)
        for svc in SERVICES:
            self.clear_delay(svc)
        self.wait_healthy()

    def wait_healthy(self, timeout=240):
        deadline = time.time() + timeout
        bad = "?"
        while time.time() < deadline:
            r = self._compose("ps", "--format", "{{.Name}}|{{.Health}}|{{.State}}", check=False)
            bad = [l for l in r.stdout.splitlines() if l and not (l.endswith("|healthy|running"))]
            if not bad:
                return
            time.sleep(2)
        raise RuntimeError(f"stack not healthy after {timeout}s: {bad}")

    # ---- data access -----------------------------------------------------------------------------
    def sql(self, db, query):
        r = sh(["docker", "exec", "-i", container("postgres"), "psql", "-U", PG_USER, "-d", db, "-At", "-F", "|", "-c", query],
               check=False, timeout=60)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip())
        return [line.split("|") for line in r.stdout.splitlines() if line]

    def kafka_exec(self, *args, input=None):
        return sh(["docker", "exec", "-i", container("kafka"), *args], check=False, timeout=60, input=input)

    def produce(self, topic, key, value):
        self.kafka_exec("/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "localhost:29092",
                        "--topic", topic, "--property", "parse.key=true", "--property", "key.separator=~",
                        input=f"{key}~{value}\n")

    def topic_size(self, topic):
        r = self.kafka_exec("/opt/kafka/bin/kafka-get-offsets.sh",
                            "--bootstrap-server", "localhost:29092", "--topic", topic, "--time", "latest")
        return sum(int(l.rsplit(":", 1)[1]) for l in r.stdout.splitlines() if ":" in l and l.rsplit(":", 1)[1].isdigit())

    def consumer_lag(self):
        r = self.kafka_exec("/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "localhost:29092",
                            "--describe", "--all-groups")
        lag = 0
        for line in r.stdout.splitlines():
            parts = line.split()
            if len(parts) >= 6 and parts[0] not in ("GROUP",) and parts[5].isdigit():
                lag += int(parts[5])
        return lag

    def metric(self, svc, name, labels=""):
        """Sum of a Prometheus metric from a service's actuator endpoint."""
        port = {"product-service": 8081, "inventory-service": 8082, "order-service": 8083, "notification-service": 8084,
                "user-service": 8085}[svc]
        try:
            body = urllib.request.urlopen(f"http://localhost:{port}/actuator/prometheus", timeout=5).read().decode()
        except Exception:
            return None
        total = 0.0
        for line in body.splitlines():
            if line.startswith(name) and labels in line and not line.startswith("#"):
                try:
                    total += float(line.rsplit(" ", 1)[1])
                except ValueError:
                    pass
        return total


# ---- HTTP --------------------------------------------------------------------------------------

@dataclass
class Resp:
    status: int
    body: object
    seconds: float


class Auth:
    """The administrator and one customer, logged in through the user-service; tokens are renewed before they expire."""
    _tokens = {}

    @classmethod
    def _login(cls, email, password):
        r = http("POST", f"{URLS['user']}/api/v1/auth/login", {"email": email, "password": password}, timeout=15, token=None)
        if r.status != 200:
            raise RuntimeError(f"login failed for {email}: {r.status} {r.body}")
        return r.body["accessToken"]

    @classmethod
    def token(cls, who):
        cached = cls._tokens.get(who)
        if cached and time.time() - cached[1] < 600:                     # access tokens live 15 minutes
            return cached[0]
        if who == "admin":
            token = cls._login(os.environ["ADMIN_EMAIL"], os.environ["ADMIN_PASSWORD"])
        else:
            email, password = f"chaos-{uuid.uuid4().hex[:8]}@mercury.test", "chaos-test-password-1"
            r = http("POST", f"{URLS['user']}/api/v1/auth/register", {"email": email, "password": password}, timeout=15, token=None)
            if r.status != 201:
                raise RuntimeError(f"register failed: {r.status} {r.body}")
            token = cls._login(email, password)
        cls._tokens[who] = (token, time.time())
        return token

    @classmethod
    def forget(cls):
        cls._tokens.clear()


def http(method, url, body=None, key=None, timeout=30, token="admin"):
    """token: "admin", "customer", a raw token, or None for an anonymous call"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    if key:
        req.add_header("Idempotency-Key", key)
    if token in ("admin", "customer"):
        try:
            token = Auth.token(token)
        except Exception as e:                    # the user-service itself is down: the call goes out unauthenticated and fails
            return Resp(0, f"no token: {str(e)[:60]}", 0.0)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw, status = r.read().decode(), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode(), e.code
    except Exception as e:                       # connection refused, reset, timeout
        return Resp(0, str(e)[:80], time.time() - t0)
    try:
        parsed = json.loads(raw) if raw else None
    except ValueError:
        parsed = raw
    return Resp(status, parsed, time.time() - t0)


@dataclass
class Fixture:
    """Products created for one scenario, with their initial stock."""
    stock: dict = field(default_factory=dict)

    def new_product(self, stock=50):
        sku = f"CHAOS-{uuid.uuid4().hex[:10]}"
        p = http("POST", f"{URLS['product']}/api/v1/products",
                 {"name": "Chaos item", "sku": sku, "price": 10.00, "quantity": stock})
        assert p.status == 201, f"create product -> {p.status} {p.body}"
        pid = p.body["id"]
        i = http("POST", f"{URLS['inventory']}/api/v1/inventory", {"productId": pid, "availableQuantity": stock})
        assert i.status == 201, f"create inventory -> {i.status} {i.body}"
        self.stock[pid] = stock
        return pid


def place_order(pid, qty=1, key=None, token="customer"):
    return http("POST", f"{URLS['order']}/api/v1/orders",
                {"items": [{"productId": pid, "quantity": qty}]}, key=key or f"chaos-{uuid.uuid4()}", timeout=40, token=token)


def get_order(order_id):
    return http("GET", f"{URLS['order']}/api/v1/orders/{order_id}", timeout=10, token="admin")


def run_load(pid, count, workers=4, pace=0.15, qty=1, on_progress=None):
    """Place `count` orders for one product, `workers` at a time, paced; returns every response."""
    results = []
    lock = threading.Lock()

    def one(i):
        time.sleep(pace * (i // workers))
        r = place_order(pid, qty)
        with lock:
            results.append(r)
        if on_progress:
            on_progress(i, r)

    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(one, range(count)))
    return results


def histogram(responses):
    h = {}
    for r in responses:
        h[r.status] = h.get(r.status, 0) + 1
    return dict(sorted(h.items()))


def order_ids(responses):
    return [r.body["id"] for r in responses if isinstance(r.body, dict) and "id" in r.body and r.status in (200, 201, 202)]


def await_until(fn, timeout=120, interval=1.0):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = fn()
        if last:
            return last
        time.sleep(interval)
    return last


# ---- invariants ----------------------------------------------------------------------------------

def check_invariants(stack, fx, timeout=180):
    """Wait for the system to settle, then check the invariants. Returns (ok, message, seconds_to_settle)."""
    t0 = time.time()
    last = "not checked"
    while time.time() - t0 < timeout:
        problems = _violations(stack, fx)
        if not problems:
            return True, "all invariants hold", time.time() - t0
        last = "; ".join(problems[:3])
        time.sleep(2)
    return False, last, time.time() - t0


def _violations(stack, fx):
    problems = []
    if not fx.stock:
        return problems
    ids = ",".join(f"'{p}'" for p in fx.stock)
    try:
        inv = {r[0]: (int(r[1]), int(r[2])) for r in stack.sql(
            "mercury_inventory", f"select product_id, available_quantity, reserved_quantity from inventory where product_id in ({ids})")}
        orders = stack.sql("mercury_order",
                           f"select o.id, o.status, i.product_id, i.quantity from orders o join order_items i on i.order_id=o.id where i.product_id in ({ids})")
    except Exception as e:
        return [f"database not readable: {e}"]
    confirmed = {}
    for oid, status, pid, qty in orders:
        if status not in ("CONFIRMED", "CANCELLED"):
            problems.append(f"order {oid[:8]} still {status}")
        if status == "CONFIRMED":
            confirmed[pid] = confirmed.get(pid, 0) + int(qty)
    for pid, initial in fx.stock.items():
        avail, reserved = inv.get(pid, (None, None))
        if avail is None:
            problems.append(f"no inventory row for {pid[:8]}"); continue
        if avail < 0:
            problems.append(f"OVERSOLD {pid[:8]}: available {avail}")
        if avail + reserved != initial:
            problems.append(f"stock not conserved for {pid[:8]}: {avail}+{reserved} != {initial}")
        if reserved != confirmed.get(pid, 0):
            problems.append(f"stranded or missing reservation for {pid[:8]}: reserved {reserved}, confirmed orders hold {confirmed.get(pid, 0)}")
    if orders:
        oids = ",".join(f"'{o[0]}'" for o in orders)
        for state, in stack.sql("mercury_order", f"select state from order_saga where order_id in ({oids}) and state not in ('CONFIRMED','CANCELLED')"):
            problems.append(f"saga in {state}")
        n = stack.sql("mercury_order", f"select count(*) from order_outbox where aggregate_id in ({oids}) and published_at is null")[0][0]
        if int(n):
            problems.append(f"{n} order events not yet published")
        n = stack.sql("mercury_inventory", f"select count(*) from inventory_outbox where aggregate_id in ({oids}) and published_at is null")[0][0]
        if int(n):
            problems.append(f"{n} inventory replies not yet published")
        dup = stack.sql("mercury_order", f"select count(*) from (select aggregate_id from order_outbox where event_type='OrderConfirmed' and aggregate_id in ({oids}) group by 1 having count(*)>1) x")[0][0]
        if int(dup):
            problems.append(f"{dup} orders announced as confirmed more than once")
    return problems
