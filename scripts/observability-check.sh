#!/usr/bin/env bash
# Phase 12 checkpoint: one order, followed through traces, metrics, logs and Grafana.
#
#   scripts/observability-check.sh [--keep]
#
#   create order -> trace visible (REST: Order->Product->Inventory)
#                -> trace visible across Kafka (ASYNC: Order->Inventory->Order->Notification)
#                -> metrics increment in Prometheus
#                -> the same trace id appears in the logs of several services
#                -> every Grafana dashboard query works and the overview reflects the orders
set -euo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-mercury-obs}
eval "$(scripts/lib/platform-env.sh "$COMPOSE_PROJECT_NAME")"
PW_FILE="/tmp/${COMPOSE_PROJECT_NAME}.env"
export TRACING_SAMPLING_PROBABILITY=1.0
GRAFANA_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-admin}
DC="docker compose -f docker-compose.yml -f docker-compose.observability.yml --profile platform"
PROM=http://localhost:9090 JAEGER=http://localhost:16686 GRAFANA=http://localhost:3000
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { echo "OBSERVABILITY CHECK FAIL: $*" >&2; exit 1; }
ok()   { echo "  ok: $*"; }
# shellcheck disable=SC2154  # rc is assigned inside the trap string itself
trap 'rc=$?; [[ $rc -ne 0 ]] && $DC ps 2>/dev/null | tail -20; [[ "${1:-}" == --keep ]] || { $DC down -v --remove-orphans >/dev/null 2>&1; rm -f "$PW_FILE"; }; exit $rc' EXIT

wait_healthy() {
  for _ in $(seq 1 150); do
    bad=$($DC ps --format '{{.Name}}|{{.Health}}' | grep -v -E '\|(healthy)?$' || true)
    [[ -z $bad ]] && return 0; sleep 2
  done; fail "not healthy: $bad"
}
prom() { curl -sfG "$PROM/api/v1/query" --data-urlencode "query=$1" | python3 -c "
import sys,json
r=json.load(sys.stdin)['data']['result']
print(sum(float(x['value'][1]) for x in r) if r else 0)"; }
trace_services() { curl -sf "$JAEGER/api/traces/$1" | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
if not d: sys.exit(0)
t=d[0]; procs={k:v['serviceName'] for k,v in t['processes'].items()}
print(' '.join(sorted({procs[s['processID']] for s in t['spans']})))
print(len(t['spans']), file=sys.stderr)"; }
trace_id_for_order() { # the trace id that the Order Service logged for this order
  docker logs "$COMPOSE_PROJECT_NAME-order-service-1" 2>&1 | grep "$1" | grep -o '\[[0-9a-f]\{32\}-[0-9a-f]\{16\}\]' | tr -d '[]' | cut -c1-32 | sed -n 1p || true
}
place_order_and_wait() { # echoes: orderId productId
  SMOKE_STATE_FILE=/tmp/obs-check-state scripts/smoke-test.sh >/tmp/obs-check-smoke.log 2>&1 || { cat /tmp/obs-check-smoke.log; fail "smoke test"; }
  cat /tmp/obs-check-state
}

step "1. start the platform with the observability stack"
$DC up -d --build >/tmp/obs-check-up.log 2>&1 || { tail -20 /tmp/obs-check-up.log; fail "docker compose up"; }
wait_healthy
$DC ps --format '{{.Name}} {{.Status}}' | sed 's/^/  /'

step "2. every scrape target is up"
for _ in $(seq 1 30); do
  DOWN=$(curl -sf $PROM/api/v1/targets | python3 -c "
import sys,json
t=json.load(sys.stdin)['data']['activeTargets']
print(len(t), [x['scrapeUrl'] for x in t if x['health']!='up'])" || echo "0 [unreachable]")
  [[ $DOWN == *"[]" ]] && break; sleep 2
done
echo "  targets: $DOWN"; [[ $DOWN == *"[]" ]] || fail "targets down: $DOWN"

step "3. baseline metrics"
sleep 6
CREATED0=$(prom 'sum(orders_created_total)'); RES0=$(prom 'sum(inventory_reservations_total{path="rest",result="reserved"})')
POSTS0=$(prom 'sum(http_server_requests_seconds_count{application="order-service",method="POST",uri="/api/v1/orders"})')
echo "  orders created=$CREATED0 reservations=$RES0 POST /orders=$POSTS0"

step "4. a synchronous order: trace across REST (Order -> Product -> Inventory)"
read -r ORDER1 _ < <(place_order_and_wait)
TRACE1=$(trace_id_for_order "$ORDER1"); [[ -n $TRACE1 ]] || fail "no trace id in the order service log"
echo "  order $ORDER1 trace $TRACE1"
SERVICES1=""
for _ in $(seq 1 20); do SERVICES1=$(trace_services "$TRACE1" 2>/tmp/obs-spans || true); [[ $SERVICES1 == *inventory-service* && $SERVICES1 == *product-service* ]] && break; sleep 2; done
echo "  services in the trace: $SERVICES1 ($(cat /tmp/obs-spans) spans)"
for s in order-service product-service inventory-service; do [[ $SERVICES1 == *$s* ]] || fail "$s missing from the REST trace"; done
ok "one trace spans Order, Product and Inventory"

step "4b. metrics moved after the synchronous order"
for _ in $(seq 1 20); do
  CREATED1=$(prom 'sum(orders_created_total)'); RES1=$(prom 'sum(inventory_reservations_total{path="rest",result="reserved"})')
  POSTS1=$(prom 'sum(http_server_requests_seconds_count{application="order-service",method="POST",uri="/api/v1/orders"})')
  python3 -c "import sys; sys.exit(0 if $CREATED1 >= $CREATED0 + 1 and $RES1 >= $RES0 + 1 and $POSTS1 >= $POSTS0 + 2 else 1)" && break; sleep 2
done
echo "  orders created $CREATED0 -> $CREATED1, REST reservations $RES0 -> $RES1, POST /orders $POSTS0 -> $POSTS1"
python3 -c "import sys; sys.exit(0 if $CREATED1 >= $CREATED0 + 1 and $RES1 >= $RES0 + 1 and $POSTS1 >= $POSTS0 + 2 else 1)" || fail "metrics did not increase after the synchronous order"
ok "orders.created, inventory.reservations and the HTTP request counter all moved"

step "5. an asynchronous order: trace across Kafka and the outbox"
ORDER_RESERVATION_MODE=ASYNC $DC up -d --no-deps order-service >/tmp/obs-check-up.log 2>&1 || { tail -20 /tmp/obs-check-up.log; fail "switching order-service to ASYNC"; }
wait_healthy
read -r ORDER2 _ < <(place_order_and_wait)
TRACE2=$(trace_id_for_order "$ORDER2"); [[ -n $TRACE2 ]] || fail "no trace id for the async order"
echo "  order $ORDER2 trace $TRACE2"
SERVICES2=""
for _ in $(seq 1 30); do SERVICES2=$(trace_services "$TRACE2" 2>/tmp/obs-spans || true); [[ $SERVICES2 == *inventory-service* && $SERVICES2 == *notification-service* ]] && break; sleep 2; done
echo "  services in the trace: $SERVICES2 ($(cat /tmp/obs-spans) spans)"
for s in order-service inventory-service notification-service; do [[ $SERVICES2 == *$s* ]] || fail "$s missing from the async trace"; done
ok "one trace spans Order, Inventory and Notification through Kafka"

step "6. logs are correlated"
# the asynchronous trace: the Order container was recreated in step 5, so the first order's log is gone
# shellcheck disable=SC2066  # one trace id on purpose: the loop variable keeps the body unchanged
for pair in "$TRACE2"; do
  N=0; LIST=""
  for svc in order-service product-service inventory-service notification-service; do
    HITS=$(docker logs "$COMPOSE_PROJECT_NAME-$svc-1" 2>&1 | grep -c "$pair" || true)
    if [[ ${HITS:-0} -gt 0 ]]; then N=$((N+1)); LIST="$LIST $svc"; fi
  done
  echo "  trace ${pair:0:8}... appears in the logs of $N services:$LIST"
  [[ $N -ge 3 ]] || fail "trace id $pair found in the logs of fewer than 3 services"
done
ok "the same trace id is in the logs of several services"

step "7. metrics moved after the asynchronous order too"
for _ in $(seq 1 20); do
  ASYNC_RES=$(prom 'sum(inventory_reservations_total{path="async",result="reserved"})')
  ASYNC_CONSUMED=$(prom 'sum(order_inventory_events_consumed_total)')
  python3 -c "import sys; sys.exit(0 if $ASYNC_RES >= 1 and $ASYNC_CONSUMED >= 1 else 1)" && break; sleep 2
done
echo "  async reservations=$ASYNC_RES, Inventory replies consumed by Order=$ASYNC_CONSUMED"
python3 -c "import sys; sys.exit(0 if $ASYNC_RES >= 1 and $ASYNC_CONSUMED >= 1 else 1)" || fail "asynchronous-path metrics did not move"
ok "inventory.reservations{path=async} and order.inventory.events.consumed moved"

step "8. Grafana"
COUNT=$(curl -sf -u "admin:$GRAFANA_PASSWORD" "$GRAFANA/api/search?tag=mercury" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
echo "  dashboards provisioned: $COUNT"; [[ $COUNT -ge 7 ]] || fail "expected 7 dashboards, found $COUNT"
python3 - "$GRAFANA" "admin:$GRAFANA_PASSWORD" <<'PY' || fail "a dashboard query failed"
import sys, json, glob, urllib.request, urllib.parse, base64
grafana, creds = sys.argv[1], sys.argv[2]
auth = "Basic " + base64.b64encode(creds.encode()).decode()
def q(expr):
    url = f"{grafana}/api/datasources/proxy/uid/prometheus/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    req = urllib.request.Request(url, headers={"Authorization": auth})
    return json.load(urllib.request.urlopen(req, timeout=20))
bad, total, with_data = [], 0, 0
for f in sorted(glob.glob("infrastructure/observability/grafana/dashboards/*.json")):
    d = json.load(open(f)); n = e = 0
    for p in d["panels"]:
        for t in p.get("targets", []):
            total += 1; n += 1
            try:
                r = q(t["expr"])
                if r["status"] != "success": raise ValueError(r)
                if r["data"]["result"]: with_data += 1; e += 1
            except Exception as ex:
                bad.append((d["title"], p["title"], str(ex)[:100]))
    print(f"  {d['title']:<22} {n:>3} queries, {e:>3} with data")
print(f"  {total} queries run through Grafana, {with_data} returned data, {len(bad)} failed")
for b in bad: print("  FAILED:", b)
sys.exit(1 if bad else 0)
PY
OVERVIEW=$(curl -sf -u "admin:$GRAFANA_PASSWORD" -G "$GRAFANA/api/datasources/proxy/uid/prometheus/api/v1/query" --data-urlencode 'query=sum(increase(orders_created_total[15m]))' | python3 -c "import sys,json; r=json.load(sys.stdin)['data']['result']; print(round(float(r[0]['value'][1]),1) if r else 0)")
echo "  overview 'orders created (15m)' as Grafana sees it: $OVERVIEW"
python3 -c "import sys; sys.exit(0 if $OVERVIEW >= 1 else 1)" || fail "the overview does not reflect the orders"
ok "dashboards provisioned, every query valid, the overview reflects the new orders"

step "OBSERVABILITY CHECK OK"
