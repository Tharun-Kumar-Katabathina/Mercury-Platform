#!/usr/bin/env bash
# Performance smoke test for CI: 100 concurrent shoppers for 30 seconds against the gateway, with hard thresholds
# (error rate under 1%, browse p95 under 500 ms, order p95 under 2 s). All shoppers share one token, so the gateway must be started with
# RATE_LIMIT_USER_PER_SECOND / RATE_LIMIT_USER_BURST raised (CI does). A change that makes the platform
# dramatically slower or flaky fails the pipeline here, long before the full load test.
set -euo pipefail
cd "$(dirname "$0")/../.."
network="${COMPOSE_PROJECT_NAME:-$(basename "$PWD" | tr '[:upper:]' '[:lower:]')}_data"
# The security suite runs just before this in CI and ends with a burst of password guesses, so the gateway's login limiter is
# still refusing for a moment (429 RATE_LIMITED, Retry-After). Wait that out; any other refusal is a real failure.
login=""
for _ in $(seq 1 30); do
  code=$(curl -s -o /tmp/perf-smoke-login.$$ -w '%{http_code}' -X POST http://localhost:8090/api/v1/auth/login \
    -H 'Content-Type: application/json' -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}")
  if [[ $code == 200 ]]; then login=$(cat /tmp/perf-smoke-login.$$); break; fi
  [[ $code == 429 ]] || { echo "admin login failed: HTTP $code $(cat /tmp/perf-smoke-login.$$)" >&2; rm -f /tmp/perf-smoke-login.$$; exit 1; }
  sleep 2
done
rm -f /tmp/perf-smoke-login.$$
[[ -n $login ]] || { echo "admin login was still rate-limited after 60 s" >&2; exit 1; }
token=$(printf '%s' "$login" | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
mkdir -p docs/performance-results/raw
docker run --rm --network "$network" --user 0 \
  -v "$PWD/load-tests/k6:/scripts:ro" -v "$PWD/docs/performance-results/raw:/results" \
  -e AUTH_TOKEN="$token" -e BASE_URL=http://api-gateway:8090 -e PRODUCT_URL=http://api-gateway:8090 \
  -e INVENTORY_URL=http://inventory-service:8082 -e STEPS=100 -e RAMP=10s -e HOLD=20s -e RESULT_NAME=ci-smoke \
  -e P95_BROWSE_MS=500 -e P95_ORDER_MS=2000 -e MAX_ERROR_RATE=0.01 -e SEED_PRODUCTS=10 \
  grafana/k6:latest run /scripts/mixed.js
