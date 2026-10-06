#!/usr/bin/env bash
# Phase 11 checkpoint: proves the production-like compose platform comes up healthy, works, survives restarts
# and keeps its data.
#
#   scripts/platform-check.sh [--keep]      (--keep leaves the stack and its volumes running/in place)
#
# Uses its own compose project (mercury-verify) and `-f docker-compose.yml` (no developer override), so it
# never touches the containers or volumes of a local development setup.
set -euo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-mercury-verify}
export POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-$(openssl rand -hex 12)}
export POSTGRES_USER=${POSTGRES_USER:-mercury}
export SMOKE_STATE_FILE=/tmp/mercury-verify-state
DC="docker compose -f docker-compose.yml --profile platform"
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { echo "PLATFORM CHECK FAIL: $*" >&2; exit 1; }
cleanup() { [[ ${1:-} == --keep ]] || { step "cleaning up"; $DC down -v --remove-orphans >/dev/null 2>&1 || true; }; }
trap 'rc=$?; [[ $rc -ne 0 ]] && { $DC ps 2>/dev/null || true; $DC logs --tail 30 2>/dev/null || true; }; cleanup "${1:-}"; exit $rc' EXIT

wait_healthy() { # every container of the project must report healthy
  for i in $(seq 1 90); do
    local unhealthy; unhealthy=$($DC ps --format '{{.Name}} {{.Health}}' | grep -v ' healthy$' || true)
    [[ -z $unhealthy ]] && return 0
    sleep 2
  done
  fail "not healthy after 180s: $unhealthy"
}

step "1. start everything"
$DC up -d --build
wait_healthy
$DC ps --format 'table {{.Name}}\t{{.Status}}'

step "2. infrastructure checks"
$DC exec -T postgres psql -U "$POSTGRES_USER" -d mercury -Atc "select datname from pg_database where datname like 'mercury_%' order by 1"
$DC exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list
$DC exec -T qdrant bash -c ':> /dev/tcp/127.0.0.1/6333' && echo "qdrant reachable"

step "3. smoke test"
scripts/smoke-test.sh
read -r ORDER_ID PRODUCT_ID < "$SMOKE_STATE_FILE"

step "4. restart every service (data tier stays up)"
$DC restart product-service inventory-service order-service notification-service
wait_healthy

step "5. restart the data tier too (postgres, kafka, qdrant)"
$DC restart postgres kafka qdrant
wait_healthy
# services reconnect on their own; give readiness a moment to flip back
for i in $(seq 1 60); do
  curl -sf localhost:8083/actuator/health/readiness >/dev/null && break || sleep 2
done

step "6. data survived"
curl -sf "localhost:8083/api/v1/orders/$ORDER_ID" | grep -q '"status":"CONFIRMED"' || fail "order $ORDER_ID lost or changed"
STOCK=$(curl -sf "localhost:8082/api/v1/inventory/$PRODUCT_ID")
echo "$STOCK" | grep -q '"availableQuantity":8' || fail "stock changed across restarts: $STOCK"
echo "order and stock intact"

step "7. full down and up (containers removed, volumes kept)"
$DC down
$DC up -d
wait_healthy
curl -sf "localhost:8083/api/v1/orders/$ORDER_ID" | grep -q '"status":"CONFIRMED"' || fail "order lost after down/up"
echo "order survived container removal"

step "8. smoke test again"
scripts/smoke-test.sh

step "PLATFORM CHECK OK"
