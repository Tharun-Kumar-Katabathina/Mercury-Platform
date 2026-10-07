#!/usr/bin/env python3
"""Load-test the platform at 100 / 500 / 1,000 / 2,500 / 5,000 / 10,000 concurrent users and record what happened.

  scripts/perf/run.py --profile baseline  --scenario mixed
  scripts/perf/run.py --profile optimized --scenario mixed --steps 100,500,1000
  scripts/perf/compare.py                          # baseline vs optimized, with the improvement in %

Profiles (the same code; only configuration differs):
  baseline   one replica of each service, no cache, default pools and thread counts, the hot indexes dropped
  optimized  Redis product cache, tuned connection pools and Tomcat threads, hot indexes present,
             Order and Product scaled to two replicas behind the nginx load balancer

For every user level a separate k6 run measures p50/p95/p99, throughput and error rate, and Prometheus supplies
CPU, memory, connection-pool use, Kafka lag and the outbox backlog over the same window. Results go to
docs/performance-results/<profile>-<scenario>.json.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PROJECT = "mercury-perf"
PW_FILE = f"/tmp/{PROJECT}.env"
RESULTS = os.path.join(ROOT, "docs", "performance-results")
PROM = "http://localhost:9090"

PROFILES = {
    "baseline": dict(
        env=dict(PRODUCT_CACHE_ENABLED="false", DB_POOL_SIZE="10", DB_POOL_MIN_IDLE="10",
                 TOMCAT_MAX_THREADS="200", TOMCAT_MAX_CONNECTIONS="8192", TOMCAT_ACCEPT_COUNT="100"),
        scale=dict(order=1, product=1), drop_indexes=True),
    "optimized": dict(
        env=dict(PRODUCT_CACHE_ENABLED="true", DB_POOL_SIZE="30", DB_POOL_MIN_IDLE="10",
                 TOMCAT_MAX_THREADS="400", TOMCAT_MAX_CONNECTIONS="16384", TOMCAT_ACCEPT_COUNT="500"),
        scale=dict(order=2, product=2), drop_indexes=False),
}

# the indexes the baseline runs without (all exist in the migrations / entity annotations)
HOT_INDEXES = {
    "mercury_order": ["idx_order_saga_due", "idx_order_outbox_due", "idx_order_outbox_aggregate", "idx_order_items_order_id"],
    "mercury_inventory": ["idx_inventory_outbox_due", "idx_inventory_outbox_aggregate"],
    "mercury_notification": ["idx_notification_order_id"],
    "mercury_product": ["idx_products_created_at_id"],
}


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def sh(args, check=True, timeout=900, env=None):
    r = subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=env, cwd=ROOT)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(args)}\n{r.stdout[-800:]}\n{r.stderr[-800:]}")
    return r


def secrets():
    """every generated secret for this project (database password, ...), remembered between runs"""
    out = subprocess.run([os.path.join(ROOT, "scripts", "lib", "platform-env.sh"), PROJECT], capture_output=True, text=True, check=True).stdout
    return dict(line.removeprefix("export ").split("=", 1) for line in out.splitlines() if "=" in line)


# only what the measurements need, so a small Docker VM is not starved (no Qdrant, Jaeger, Grafana, user/recommendation/gateway)
SERVICES = ["postgres", "kafka", "redis", "product-service", "inventory-service", "order-service", "notification-service",
            "gateway", "prometheus", "kafka-exporter", "postgres-exporter", "redis-exporter", "cadvisor"]


class Stack:
    def __init__(self, profile, mode="SYNC", scenario="mixed"):
        self.scenario = scenario
        self.p = PROFILES[profile]
        # Authentication is OFF for the load tests: they measure the business path (cache, pools, replicas, indexes), and
        # every user would need a login. scripts/perf/smoke.sh and docs/performance.md cover the cost of authentication.
        self.env = dict(os.environ, **secrets(), ORDER_RESERVATION_MODE=mode, SECURITY_ENABLED="false",
                        TRACING_SAMPLING_PROBABILITY="0", **self.p["env"])
        self.base = ["docker", "compose", "-p", PROJECT, "-f", "docker-compose.yml",
                     "-f", "docker-compose.observability.yml", "-f", "docker-compose.perf.yml", "--profile", "platform"]

    def compose(self, *args, **kw):
        return sh(self.base + list(args), env=self.env, **kw)

    def up(self):
        s = self.p["scale"]
        extra = ["qdrant", "recommendation-service"] if self.scenario == "recommendations" else []
        self.compose("up", "-d", "--build", "--scale", f"order-service={s['order']}",
                     "--scale", f"product-service={s['product']}", *SERVICES, *extra, timeout=1500)
        self.wait_healthy()
        # not needed for the measurements; frees memory in a small Docker VM

    def wait_healthy(self, timeout=420):
        deadline = time.time() + timeout
        bad = []
        while time.time() < deadline:
            r = self.compose("ps", "--format", "{{.Name}}|{{.Health}}|{{.State}}", check=False)
            bad = [l for l in r.stdout.splitlines() if l and not l.endswith("|healthy|running") and not (l.split("|")[1] == "" and l.endswith("|running"))]
            if not bad:
                return
            time.sleep(3)
        raise RuntimeError(f"not healthy: {bad}")

    def down(self):
        self.compose("down", "-v", "--remove-orphans", check=False)
        try:
            os.remove(PW_FILE)
        except FileNotFoundError:
            pass

    def sql(self, db, statement):
        sh(["docker", "exec", "-i", f"{PROJECT}-postgres-1", "psql", "-U", "mercury", "-d", db, "-c", statement], check=False)


def prom(query, at=None):
    params = {"query": query}
    if at:
        params["time"] = at
    with urllib.request.urlopen(f"{PROM}/api/v1/query?" + urllib.parse.urlencode(params), timeout=30) as r:
        data = json.load(r)["data"]["result"]
    return [(x["metric"], float(x["value"][1])) for x in data]


def scalar(query, at=None, default=None):
    r = prom(query, at)
    return round(r[0][1], 4) if r else default


def system_metrics(window_s, at):
    w = f"{window_s}s"
    return {
        "docker_cpu_cores": scalar(f'sum(rate(container_cpu_usage_seconds_total{{id="/docker"}}[{w}]))', at),
        "docker_memory_mb": (scalar('container_memory_working_set_bytes{id="/docker"}', at) or 0) / 1e6,
        "order_cpu_pct": scalar(f'avg(avg_over_time(process_cpu_usage{{application="order-service"}}[{w}]))', at),
        "product_cpu_pct": scalar(f'avg(avg_over_time(process_cpu_usage{{application="product-service"}}[{w}]))', at),
        "order_heap_mb": (scalar('max(jvm_memory_used_bytes{application="order-service",area="heap"})', at) or 0) / 1e6,
        "db_pool_active_max": scalar(f'max(max_over_time(hikaricp_connections_active[{w}]))', at),
        "db_pool_pending_max": scalar(f'max(max_over_time(hikaricp_connections_pending[{w}]))', at),
        "kafka_lag": scalar('sum(kafka_consumergroup_lag)', at, 0),
        "outbox_pending": scalar('sum(outbox_pending)', at, 0),
        "cache_hit_ratio": scalar(f'sum(increase(product_cache_total{{result="hit"}}[{w}])) / clamp_min(sum(increase(product_cache_total[{w}])), 1)', at),
    }


def run_level(stack, scenario, vus, ramp, hold, think, buy_ratio, tag):
    script = f"/scripts/{scenario}.js"
    results_dir = os.path.join(RESULTS, "raw")
    os.makedirs(results_dir, exist_ok=True)
    name = f"{tag}-{scenario}-{vus}"
    env = {"BASE_URL": "http://gateway:8080", "PRODUCT_URL": "http://gateway:8080", "RECOMMENDATION_URL": "http://gateway:8080", "INVENTORY_URL": "http://inventory-service:8082",
           "STEPS": str(vus), "RAMP": ramp, "HOLD": hold, "THINK_SECONDS": str(think), "BUY_RATIO": str(buy_ratio),
           "RESULT_NAME": name, "SEED_PRODUCTS": "50", "MAX_ERROR_RATE": "1",
           "K6_PROMETHEUS_RW_SERVER_URL": "http://prometheus:9090/api/v1/write",
           "K6_PROMETHEUS_RW_PUSH_INTERVAL": "5s", "K6_PROMETHEUS_RW_TREND_STATS": "p(50),p(95),p(99)"}
    cmd = ["docker", "run", "--rm", "--network", f"{PROJECT}_data", "-v", f"{ROOT}/load-tests/k6:/scripts:ro",
           "-v", f"{results_dir}:/results", "--user", "0"]
    for k, v in env.items():
        cmd += ["-e", f"{k}={v}"]
    cmd += ["grafana/k6:latest", "run", "--out", "experimental-prometheus-rw", "--tag", f"testid={name}", script]
    started = time.time()
    r = sh(cmd, check=False, timeout=3600)
    elapsed = time.time() - started
    path = os.path.join(results_dir, f"{name}.json")
    if not os.path.exists(path):
        raise RuntimeError(f"k6 produced no summary for {name}:\n{r.stdout[-1200:]}\n{r.stderr[-1200:]}")
    d = json.load(open(path))["metrics"]

    def v(metric, stat, default=None):
        return d.get(metric, {}).get("values", {}).get(stat, default)

    def tagged(name_, stat):
        return v(f"http_req_duration{{name:{name_}}}", stat)

    out = {
        "vus": vus, "seconds": round(elapsed),
        "requests": v("http_reqs", "count"), "rps": round(v("http_reqs", "rate", 0), 1),
        "error_rate_pct": round(v("http_req_failed", "rate", 0) * 100, 3),
        "checks_pass_pct": round((v("checks", "rate", 1)) * 100, 2),
        "latency_ms": {"p50": v("http_req_duration", "med"), "p90": v("http_req_duration", "p(90)"),
                       "p95": v("http_req_duration", "p(95)"), "p99": v("http_req_duration", "p(99)"),
                       "max": v("http_req_duration", "max")},
        "browse_ms": {"p50": tagged("GET product", "med"), "p95": tagged("GET product", "p(95)"), "p99": tagged("GET product", "p(99)")},
        "order_ms": {"p50": tagged("POST order", "med"), "p95": tagged("POST order", "p(95)"), "p99": tagged("POST order", "p(99)")},
        "recommendation_ms": {k: {"p50": tagged(k, "med"), "p95": tagged(k, "p(95)"), "p99": tagged(k, "p(99)")} for k in ("similar", "me", "popular")},
    }
    out["system"] = system_metrics(int(hold.rstrip("s")), None)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", choices=PROFILES, required=True)
    ap.add_argument("--scenario", default="mixed", choices=["mixed", "browse", "orders", "recommendations"])
    ap.add_argument("--steps", default="100,500,1000,2500,5000,10000")
    ap.add_argument("--ramp", default="20s")
    ap.add_argument("--hold", default="45s")
    ap.add_argument("--think", type=float, default=3.0)
    ap.add_argument("--buy-ratio", type=float, default=0.05)
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--no-up", action="store_true", help="the stack is already running")
    a = ap.parse_args()

    stack = Stack(a.profile, scenario=a.scenario)
    os.makedirs(RESULTS, exist_ok=True)
    result = {"profile": a.profile, "scenario": a.scenario, "think_seconds": a.think, "buy_ratio": a.buy_ratio,
              "hold": a.hold, "config": PROFILES[a.profile]["env"], "replicas": PROFILES[a.profile]["scale"],
              "indexes_dropped": PROFILES[a.profile]["drop_indexes"], "started": time.strftime("%Y-%m-%d %H:%M:%S"),
              "host": {"cpus": os.cpu_count()}, "levels": []}
    try:
        if not a.no_up:
            log(f"starting the platform ({a.profile})")
            stack.up()
        if PROFILES[a.profile]["drop_indexes"]:
            for db, names in HOT_INDEXES.items():
                for n in names:
                    stack.sql(db, f"DROP INDEX IF EXISTS {n}")
            log("baseline: hot indexes dropped")
        for vus in [int(x) for x in a.steps.split(",")]:
            log(f"--- {a.profile} / {a.scenario} / {vus} concurrent users")
            lvl = run_level(stack, a.scenario, vus, a.ramp, a.hold, a.think, a.buy_ratio, a.profile)
            result["levels"].append(lvl)
            log(f"    {lvl['rps']} req/s, errors {lvl['error_rate_pct']}%, p95 {lvl['latency_ms']['p95']:.0f} ms, p99 {lvl['latency_ms']['p99']:.0f} ms")
            json.dump(result, open(os.path.join(RESULTS, f"{a.profile}-{a.scenario}.json"), "w"), indent=1)
            time.sleep(10)          # let the system drain between levels
    finally:
        if not a.keep and not a.no_up:
            stack.down()
    return 0


if __name__ == "__main__":
    sys.exit(main())
