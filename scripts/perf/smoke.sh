#!/usr/bin/env bash
# Performance smoke test for CI: 100 concurrent shoppers for 30 seconds against the gateway, with hard thresholds
# (error rate under 1%, browse p95 under 500 ms, order p95 under 2 s). A change that makes the platform
# dramatically slower or flaky fails the pipeline here, long before the full load test.
set -euo pipefail
cd "$(dirname "$0")/../.."
network="${COMPOSE_PROJECT_NAME:-$(basename "$PWD" | tr '[:upper:]' '[:lower:]')}_data"
login=$(curl -sf -X POST http://localhost:8090/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}")
token=$(printf '%s' "$login" | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')
mkdir -p docs/performance-results/raw
docker run --rm --network "$network" --user 0 \
  -v "$PWD/load-tests/k6:/scripts:ro" -v "$PWD/docs/performance-results/raw:/results" \
  -e AUTH_TOKEN="$token" -e BASE_URL=http://api-gateway:8090 -e PRODUCT_URL=http://api-gateway:8090 \
  -e INVENTORY_URL=http://inventory-service:8082 -e STEPS=100 -e RAMP=10s -e HOLD=20s -e RESULT_NAME=ci-smoke \
  -e P95_BROWSE_MS=500 -e P95_ORDER_MS=2000 -e MAX_ERROR_RATE=0.01 -e SEED_PRODUCTS=10 \
  grafana/k6:latest run /scripts/mixed.js
