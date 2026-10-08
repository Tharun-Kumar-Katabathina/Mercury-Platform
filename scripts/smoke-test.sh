#!/usr/bin/env bash
# End-to-end smoke test against a running Mercury platform (docker compose or Kubernetes port-forwards).
#   scripts/smoke-test.sh [product_url] [inventory_url] [order_url] [notification_url]
# Creates a product with stock, places an order, replays it, and checks stock and the notification.
# Exits non-zero (and says what failed) on the first broken expectation.
set -euo pipefail

PRODUCT=${1:-${PRODUCT_URL:-http://localhost:8081}}
INVENTORY=${2:-${INVENTORY_URL:-http://localhost:8082}}
ORDER=${3:-${ORDER_URL:-http://localhost:8083}}
NOTIFICATION=${4:-${NOTIFICATION_URL:-http://localhost:8084}}
USER_SERVICE=${USER_URL:-http://localhost:8085}
# When ADMIN_EMAIL / ADMIN_PASSWORD are set the platform is assumed to require authentication: the script logs in as the
# administrator (to create the product and its stock and to read notifications) and as a freshly registered customer
# (to place and read the order). Without them it talks to a platform running with SECURITY_ENABLED=false.
AUTH=${SMOKE_AUTH:-$([[ -n ${ADMIN_PASSWORD:-} ]] && echo yes || echo no)}
STOCK=${SMOKE_STOCK:-10}
QTY=${SMOKE_QTY:-2}
# shellcheck disable=SC2034  # reserved: documents the knob, the mode is detected from the order response
MODE=${SMOKE_ORDER_MODE:-auto}     # auto | sync | async

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print(d$1)"; }
TOKEN=""
http() { # method url [key] [body] -> sets BODY and CODE (uses $TOKEN when set)
  local out; out=$(curl -s -o /tmp/smoke-body.$$ -w '%{http_code}' -X "$1" "$2" -H 'Content-Type: application/json' \
      ${TOKEN:+-H "Authorization: Bearer $TOKEN"} ${3:+-H "Idempotency-Key: $3"} ${4:+-d "$4"}) || fail "cannot reach $2"
  CODE=$out; BODY=$(cat /tmp/smoke-body.$$); rm -f /tmp/smoke-body.$$
}
login() { # email password -> prints the access token
  TOKEN="" http POST "$USER_SERVICE/api/v1/auth/login" "" "{\"email\":\"$1\",\"password\":\"$2\"}"
  [[ $CODE == 200 ]] || fail "login as $1 -> HTTP $CODE: $BODY"
  echo "$BODY" | json "['accessToken']"
}

for svc in "$PRODUCT" "$INVENTORY" "$ORDER" "$NOTIFICATION"; do
  http GET "$svc/actuator/health/readiness"
  [[ $CODE == 200 ]] || fail "$svc is not ready (HTTP $CODE)"
done
echo "all four services ready"

if [[ $AUTH == yes ]]; then
  ADMIN_TOKEN=$(login "${ADMIN_EMAIL:-admin@mercury.local}" "$ADMIN_PASSWORD")
  CUSTOMER="smoke-$(date +%s)-$RANDOM@mercury.test"
  TOKEN="" http POST "$USER_SERVICE/api/v1/auth/register" "" "{\"email\":\"$CUSTOMER\",\"password\":\"smoke-test-password-1\"}"
  [[ $CODE == 201 ]] || fail "register customer -> HTTP $CODE: $BODY"
  CUSTOMER_TOKEN=$(login "$CUSTOMER" "smoke-test-password-1")
  echo "authenticated as administrator and as a new customer"
fi
as_admin()    { TOKEN=${ADMIN_TOKEN:-}; }
as_customer() { TOKEN=${CUSTOMER_TOKEN:-}; }

SKU="SMOKE-$(date +%s)-$RANDOM"
as_admin
http POST "$PRODUCT/api/v1/products" "" "{\"name\":\"Smoke item\",\"sku\":\"$SKU\",\"price\":10.00,\"quantity\":$STOCK}"
[[ $CODE == 201 ]] || fail "create product -> HTTP $CODE: $BODY"
PID=$(echo "$BODY" | json "['id']")
http POST "$INVENTORY/api/v1/inventory" "" "{\"productId\":\"$PID\",\"availableQuantity\":$STOCK}"
[[ $CODE == 201 ]] || fail "create inventory -> HTTP $CODE: $BODY"

KEY="smoke-$(date +%s)-$RANDOM"
as_customer
http POST "$ORDER/api/v1/orders" "$KEY" "{\"items\":[{\"productId\":\"$PID\",\"quantity\":$QTY}]}"
case $CODE in
  201) echo "order placed synchronously" ;;
  202) echo "order accepted (async)" ;;
  *)   fail "place order -> HTTP $CODE: $BODY" ;;
esac
OID=$(echo "$BODY" | json "['id']")

for _ in $(seq 1 60); do
  http GET "$ORDER/api/v1/orders/$OID"
  STATUS=$(echo "$BODY" | json "['status']")
  [[ $STATUS == CONFIRMED ]] && break
  [[ $STATUS == CANCELLED ]] && fail "order was cancelled: $BODY"
  sleep 1
done
[[ $STATUS == CONFIRMED ]] || fail "order still $STATUS after 60s"
echo "order $OID CONFIRMED"

http POST "$ORDER/api/v1/orders" "$KEY" "{\"items\":[{\"productId\":\"$PID\",\"quantity\":$QTY}]}"
[[ $CODE == 200 || $CODE == 201 ]] || fail "replay -> HTTP $CODE: $BODY"
[[ $(echo "$BODY" | json "['id']") == "$OID" ]] || fail "replay returned a different order"
echo "replay returned the same order (HTTP $CODE)"

as_admin
http GET "$INVENTORY/api/v1/inventory/$PID"
AVAIL=$(echo "$BODY" | json "['availableQuantity']"); RESV=$(echo "$BODY" | json "['reservedQuantity']")
[[ $AVAIL == "$((STOCK - QTY))" && $RESV == "$QTY" ]] || fail "stock is $AVAIL/$RESV, expected $((STOCK - QTY))/$QTY"
echo "stock $AVAIL available / $RESV reserved (reserved once)"

as_admin
for _ in $(seq 1 60); do
  http GET "$NOTIFICATION/api/v1/notifications/orders/$OID"
  [[ $CODE == 200 && $BODY != "[]" ]] && break
  sleep 1
done
[[ $BODY != "[]" ]] || fail "no notification recorded for order $OID"
echo "notification recorded"

RECOMMENDATION=${RECOMMENDATION_URL:-http://localhost:8086}
if curl -sf -o /dev/null "$RECOMMENDATION/actuator/health/readiness"; then
  TOKEN=""
  # the popular list is cached for 2 minutes (recommendation.cache.popular-ttl): a poll that lands before the event is
  # consumed can be served a stale list, so wait longer than the TTL before declaring that the service did not learn
  for _ in $(seq 1 150); do
    http GET "$RECOMMENDATION/api/v1/recommendations/popular?limit=50"
    [[ $CODE == 200 && $BODY == *"$PID"* ]] && break
    sleep 1
  done
  [[ $BODY == *"$PID"* ]] || fail "the recommendation service did not learn from order $OID"
  echo "recommendation service learned the purchase from the order event"

  # the same answers must be reachable at the public edge: this is what proves the gateway knows where the service is
  GATEWAY=${GATEWAY_URL:-http://localhost:8090}
  if curl -sf -o /dev/null "$GATEWAY/actuator/health/readiness"; then
    TOKEN="" http GET "$GATEWAY/api/v1/recommendations/popular?limit=50"
    [[ $CODE == 200 && $BODY == *"$PID"* ]] || fail "popular recommendations through the gateway -> HTTP $CODE: $BODY"
    if [[ $AUTH == yes ]]; then
      as_customer
      http GET "$GATEWAY/api/v1/recommendations/me"
      [[ $CODE == 200 ]] || fail "personal recommendations through the gateway -> HTTP $CODE: $BODY"
      http POST "$GATEWAY/api/v1/interactions" "" "{\"productId\":\"$PID\",\"type\":\"VIEW\"}"
      [[ $CODE == 200 || $CODE == 201 || $CODE == 202 || $CODE == 204 ]] || fail "recording a view through the gateway -> HTTP $CODE: $BODY"
    fi
    echo "recommendations reachable through the gateway (public popular list, personal list with a token)"
  fi
fi

echo "$OID $PID" > "${SMOKE_STATE_FILE:-/tmp/mercury-smoke-last}"
echo "SMOKE OK"
