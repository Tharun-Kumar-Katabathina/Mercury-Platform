# Order Service

Creates orders across Product Service and Inventory Service, keeps them consistent without a
distributed transaction, is idempotent, and recovers from lost responses, failed compensations and crashes.
Everything below describes what is implemented. Failure recovery has its own document:
**[Saga reliability and recovery](saga-recovery.md)**. Order events and the outbox are described in
**[Event-driven architecture](event-driven-architecture.md)**.

## 1. Architecture

```
Client
  |  POST /api/v1/orders   (Idempotency-Key)
  v
Order Service :8083 ---------------------> PostgreSQL / mercury_order
  |
  |  GET  /api/v1/products/{id}                      (name, sku, price: read only)
  v
Product Service :8081
  |
Order Service
  |  POST /api/v1/inventory/{id}/reserve   (one per item)
  |  POST /api/v1/inventory/{id}/release   (compensation only)
  v
Inventory Service :8082 -----------------> PostgreSQL / mercury_inventory
```

| Owner | Data |
|---|---|
| Order Service | `orders`, `order_items`, `order_idempotency_records` (database `mercury_order`) |
| Product Service | products, the authority on name / SKU / price |
| Inventory Service | stock and reservations |

Order Service never connects to the Product or Inventory databases; it only calls their HTTP APIs
through `ProductClient` and `InventoryClient`.

**The core design problem.** The Order database, Product Service and Inventory Service are three
separate systems, so no `@Transactional` can make "validate, reserve stock, save the order" atomic.
Order Service therefore runs an **orchestrated saga**: a sequence of small local transactions and
remote calls, where every step that can fail has a defined way to be undone (section 8).

## 2. API

### `POST /api/v1/orders`

Header `Idempotency-Key: <client key>` (required, at most 255 characters).

```json
{ "items": [ { "productId": "…", "quantity": 2 } ] }
```

The client never sends a price. Response `201 Created`:

```json
{
  "id": "…", "status": "CONFIRMED", "totalAmount": 4998.00,
  "items": [ { "productId": "…", "productName": "MacBook Pro 16", "sku": "MBP-16-M4",
               "unitPrice": 2499.00, "quantity": 2, "subtotal": 4998.00 } ],
  "createdAt": "…", "updatedAt": "…"
}
```

A retry with the same key and payload returns the **same body, again `201`**, plus the header
`Idempotent-Replayed: true`.

Rules: `items` must not be empty; every item needs a `productId` and `quantity >= 1`; the same
`productId` may appear only once (send one item with the total quantity).

### `GET /api/v1/orders/{orderId}`

Returns the persisted order as it was snapshotted (`200`), or `404 ORDER_NOT_FOUND`.

## 3. Database

Flyway migrations in `src/main/resources/db/migration`; Hibernate runs with `ddl-auto=validate`.

| Table | Purpose | Notable constraints |
|---|---|---|
| `orders` | id (UUID), status, total_amount, created_at, updated_at | status in (`PENDING`,`CONFIRMED`,`CANCELLED`); `total_amount >= 0` |
| `order_items` | one row per product in an order | FK to `orders`; `quantity > 0`; `unit_price >= 0`; unique (`order_id`,`product_id`); index on `order_id`; `reservation_status` (V3) |
| `order_idempotency_records` | one row per client Idempotency-Key (V2) | unique `idempotency_key`; FK to `orders`; status `IN_PROGRESS`/`COMPLETED` |
| `order_outbox` | events waiting to be, or already, published to Kafka (V4) | `seq` identity; unique `event_id`; event type in (`OrderCreated`,`OrderConfirmed`,`OrderCancelled`); index on (`published_at`,`next_attempt_at`) |
| `order_saga` | durable saga progress, one row per order (V3, `failure_reason` added in V4) | PK/FK `order_id`; state in (`RESERVING`,`COMPENSATING`,`CONFIRMED`,`CANCELLED`,`RECOVERY_FAILED`); `attempt_count >= 0`; index on (`state`,`next_attempt_at`) |

Migrations: `V1__initial_order_schema.sql`, `V2__add_order_idempotency_records.sql`,
`V3__add_durable_saga.sql` (per-item reservation status and `order_saga`, backfilled for existing orders),
`V4__add_outbox.sql` (`order_outbox` and the saga's `failure_reason`).

**Items are a snapshot.** `product_name`, `sku` and `unit_price` are copied from Product Service when
the order is placed and never re-read. If the product's price changes later, the order keeps the old
price (covered by an integration test). `reservation_status` is saga progress, not part of the snapshot.

## 4. Order lifecycle

```
            reservation + confirm succeed
 PENDING  ------------------------------->  CONFIRMED   (final)
    |
    |   any failure after the order was saved, once stock is given back
    +--------------------------------------->  CANCELLED   (final)
```

`CANCELLED` means **fully compensated**. An order whose compensation is still outstanding stays `PENDING`
and is finished by recovery (or becomes `RECOVERY_FAILED` in `order_saga` if recovery gives up). The state
machine is enforced in the `Order` entity: only a `PENDING` order can be confirmed or cancelled. The saga's
own states are in [saga-recovery.md](saga-recovery.md#2-durable-state).

## 5. Product integration

`ProductClient` calls `GET /api/v1/products/{id}` for every item and keeps only `id`, `name`,
`sku` and `price`. This happens **before anything is saved or reserved** and has no side effects.
`OrderPlanner` validates the request, orders the items canonically by `productId`, snapshots the
products and calculates the total (`sum(unitPrice * quantity)`).

## 6. Inventory integration

`InventoryClient` calls reserve (one call per item, in `productId` order) and release. The
Idempotency-Key of every call is supplied by Order Service and forwarded unchanged; the client never
invents one. Deterministic keys, one per order and product:

| Operation | Key |
|---|---|
| reserve | `order:{orderId}:product:{productId}` |
| release | `order:{orderId}:product:{productId}:release` |

Because the keys are fixed for a given order, repeating either call is safe.

A third call, `GET /api/v1/inventory/{productId}/reservations/{key}`, is read-only and exists for recovery:
it says whether a reservation was made under a key, which is how an unknown reserve outcome (a lost response)
is resolved without sending a second reserve. See [saga-recovery.md](saga-recovery.md#3-ambiguous-reservations).

### Inventory release endpoint (added for compensation)

`POST /api/v1/inventory/{productId}/release`, header `Idempotency-Key`, body `{"quantity": n}`.
Moves `n` units from reserved back to available. It has the same safeguards as reserve:
optimistic locking with retry, idempotent replay (`Idempotent-Replayed: true`), `422` on key reuse
with a different request, failures never stored. Releasing more than is reserved returns
`409 INSUFFICIENT_RESERVED_STOCK` and changes nothing. A key used for a reservation cannot be reused
for a release (or the other way round); that is `422`, not a replay.

## 7. Idempotency of order creation

Same pattern as Inventory, adapted to a multi-step flow:

1. The request payload is canonicalised (items sorted by `productId`) and hashed, so item order in the
   payload does not matter.
2. If the key already exists: a different hash is `422 IDEMPOTENCY_KEY_MISMATCH`; a `COMPLETED` claim
   returns the stored original response (replay); an `IN_PROGRESS` claim means another request is
   still working, so the duplicate waits (polls every 25 ms, up to
   `order.idempotency.wait-timeout`, default 5 s) and then replays, or gets `409 ORDER_CONFLICT`
   if it is still not finished.
3. Otherwise the order is saved as `PENDING` **and the key is claimed in one local transaction**.
   The unique key means exactly one concurrent request can do this; the others roll back (leaving no
   order behind) and take path 2.
4. When the order is confirmed the claim becomes `COMPLETED` and stores the response JSON.
5. **Failures are not replayed.** When an attempt fails and is compensated, its claim is deleted, so
   retrying the same key evaluates the request afresh (and creates a new order). The failed attempt
   stays in the table as a `CANCELLED` order.

Result for 100 concurrent requests with one key: 1 order, 1 reservation per item, 99 replays.

Duplicate polling reads the claim through a plain projection (`ClaimView`), never a cached entity, so
it is correct even if open-in-view is switched on (see Testing).

## 8. The saga: reservation and compensation

`OrderService.createOrder` hands the saved order to `OrderSagaService`:

```
 validate request                      -> 400 INVALID_ORDER
 look up Idempotency-Key               -> replay / 422 / wait
 read products (no side effects)       -> 404 PRODUCT_NOT_FOUND, 502/503
 save PENDING order + claim key + saga (one local transaction)
 for each item: mark RESERVING, reserve stock, mark RESERVED   (key order:{id}:product:{pid})
      any failure -> COMPENSATE, rethrow the downstream error
 confirm order + store response + saga CONFIRMED               (one local transaction)
      failure -> check the order's real state, then COMPENSATE
```

Each item's progress is written **before** its remote call, so a timeout or crash leaves a durable
"outcome unknown" record instead of nothing. **Compensate** is one idempotent routine used by the request
and by the recovery worker: resolve unknown reservations by asking Inventory, release held ones under a
deterministic key, then cancel the order and free the key. If it cannot finish, the saga stays
`COMPENSATING` and recovery retries it with backoff. Details, tables and the failure matrix are in
[saga-recovery.md](saga-recovery.md#4-compensation-one-routine-used-everywhere).

Reservation is sequential in a fixed order. No database transaction is open during any remote call.

## 9. Error handling

All errors use `{"timestamp","status","error","message"}`.

| Status | `error` | When |
|---|---|---|
| 400 | `INVALID_ORDER` | empty/invalid items, bad quantity, duplicate product, malformed body, malformed id |
| 400 | `MISSING_IDEMPOTENCY_KEY` | header absent or blank |
| 404 | `ORDER_NOT_FOUND` | unknown order id |
| 404 | `PRODUCT_NOT_FOUND` | Product Service says so (passed through) |
| 404 | `INVENTORY_NOT_FOUND` | product has no inventory record (passed through) |
| 409 | `INSUFFICIENT_STOCK` | passed through from Inventory |
| 409 | `CONCURRENT_MODIFICATION` | Inventory lost the lock race after all its retries (passed through) |
| 409 | `ORDER_CONFLICT` | same key still in progress after the wait timeout |
| 422 | `IDEMPOTENCY_KEY_MISMATCH` | same key, different request (Order's own, or passed through from Inventory) |
| 500 | `ORDER_PROCESSING_FAILED` | order could not be finalised after reserving; stock was released (or left for reconciliation) |
| 502 | `PRODUCT_SERVICE_ERROR` / `INVENTORY_SERVICE_ERROR` | downstream returned 5xx |
| 503 | `PRODUCT_SERVICE_UNAVAILABLE` / `INVENTORY_SERVICE_UNAVAILABLE` | downstream unreachable or timed out |

Any other 4xx from a downstream service keeps its status and message; if its body has no error code,
the code is `PRODUCT_REQUEST_REJECTED` / `INVENTORY_REQUEST_REJECTED`.

## 10. Timeouts, resilience and observability

- Every call to Product and Inventory has `spring.http.clients.connect-timeout` (default `2s`) and
  `read-timeout` (default `5s`); a timeout becomes a `503`. Verified against a real slow HTTP server.
- A **circuit breaker and a bulkhead** per downstream protect the live request path (fail fast when a
  service keeps failing; cap concurrent calls). A request refused this way surfaces as `503` and is known
  to have never been sent. See [saga-recovery.md](saga-recovery.md#6-circuit-breaker-and-bulkhead-live-path-only).
- `/actuator/health`, Micrometer metrics and a read-only `/actuator/sagas` overview of unfinished orders; see
  [saga-recovery.md](saga-recovery.md#7-observability).
- Logs carry the order id, product id, operation, result, duration and a short SHA-256 fingerprint of the
  Idempotency-Key; the raw key is never logged.
- **Events (Phase 9).** `OrderCreated`, `OrderConfirmed` and `OrderCancelled` are written to `order_outbox` in the
  same transaction as the order change (`createPending`, `confirm`, `cancel`) and published to Kafka by a
  separate publisher; `OrderCancelled` carries a reason (`INSUFFICIENT_STOCK`, `INVENTORY_UNAVAILABLE`, …).
  Orders do not depend on Kafka: with Kafka down, orders still succeed and events wait in the outbox. Metrics
  `events.published`, `events.publish.failed`, `outbox.pending`; read-only `/actuator/outbox`. See
  [event-driven-architecture.md](event-driven-architecture.md).

## 11. Testing

| Suite | Count | Covers |
|---|---|---|
| `OrderPersistenceTests`, `OrderRetrievalApiTests` | 10 | model, Flyway schema, DB constraints, state transitions, GET |
| `ProductClientTests`, `InventoryClientTests`, `ClientTimeoutTests` | 12 (4 + 6 + 2) | wire format, error translation, real timeouts |
| `OrderPlannerTests` | 11 | validation, snapshot, total, canonical order |
| `OrderServiceTests` | 16 | creation, reservation keys, every failure and compensation path, idempotency, 100-way concurrency |
| `OrderIncompleteCompensationTests` | 1 | failed release leaves the order `PENDING` |
| `OrderApiTests` | 17 | status codes and error bodies for every case in section 9 |
| `OrderIdempotencyWithOpenInViewTests` | 1 | regression: duplicates must see completion even with open-in-view on |
| `SagaRecoveryTests` | 12 | durable progress, ambiguous reservations, recovery, backoff, exclusivity |
| `DownstreamGuardTests` | 8 | circuit breaker and bulkhead |
| `SagaOperationsTests` | 4 | `/actuator/sagas` and metrics |
| `OrderEventsTests` | 9 | events written atomically with state changes, contents, no idempotency key, replays and duplicates |
| `OutboxPublisherTests`, `OutboxEndpointTests` | 11 | publisher behaviour under Kafka failure, ordering, crashes, racing publishers; `/actuator/outbox` |
| `OrderServiceApplicationTests` | 1 | context loads |
| **`OrderFlowIntegrationTests`** | 12 | **real** Order, Product, Inventory and PostgreSQL |
| **`SagaRecoveryIntegrationTests`** | 7 | **real stack + fault proxy**: lost responses, failed compensation, exhausted retries, six workers, `kill -9` |
| **`ResilienceIntegrationTests`** | 1 | **real stack**: Inventory outage, circuit opens, recovery after return |
| **`EventFlowIntegrationTests`** | 7 | **real stack incl. Kafka and Notification**: order → outbox → Kafka → notification, Kafka outage, crash before publishing, duplicate and poison messages, 50 concurrent orders |

Order Service: **140 tests**. (Product 27, Inventory 40, Notification 15.)

The integration tests start a Testcontainers PostgreSQL (`postgres:16-alpine`), build Product and Inventory
from `../product-service` and `../inventory-service` and run each as a separate process; Order Service runs in
the test JVM (and, for the kill test, as its own process). `OrderFlowIntegrationTests` scenarios (all verified
directly in PostgreSQL): successful order and price snapshot; same-key replay deducts once; key mismatch;
insufficient stock; failure on the second item gives back the first item's stock; unknown product; product
without inventory; Inventory stopped (503, order left for recovery, then cancelled by recovery, ordering works
again); stock reserved then order persistence fails (reservation released, no orphan); 100 concurrent same-key
requests; 30 concurrent different-key orders against 10 units (never oversold; excess load is shed with 409 or
503; only CONFIRMED orders hold stock). The failure-injection tests are described in
[saga-recovery.md](saga-recovery.md#10-tests).

**Lessons recorded by these suites.** (1) `src/test/resources/application.properties` *replaces*
`src/main/resources/application.properties` during tests, so settings must be mirrored there; a missing
`spring.jpa.open-in-view=false` silently enabled open-in-view and exposed stale claim reads under real
PostgreSQL concurrency, fixed in both the test config and the code (projection reads). (2) Resilience4j
silently caps `minimum-calls` at the window size; Order Service now refuses that configuration at startup.

## 12. Local development

Ports: Order `8083`, Product `8081`, Inventory `8082`, PostgreSQL `5432`.

| Variable | Default |
|---|---|
| `SERVER_PORT` | `8083` |
| `ORDER_DB_URL` | `jdbc:postgresql://localhost:5432/mercury_order` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | `mercury` / `change-me` (the compose database uses `mercury`) |
| `PRODUCT_SERVICE_URL` | `http://localhost:8081` |
| `INVENTORY_SERVICE_URL` | `http://localhost:8082` |
| `HTTP_CLIENT_CONNECT_TIMEOUT` / `HTTP_CLIENT_READ_TIMEOUT` | `2s` / `5s` |
| `ORDER_IDEMPOTENCY_WAIT_TIMEOUT` | `5s` |
| `ORDER_RECOVERY_*`, `ORDER_INVENTORY_*`, `ORDER_PRODUCT_*`, `MANAGEMENT_ENDPOINTS` | see [saga-recovery.md](saga-recovery.md#8-configuration) |
| `KAFKA_BOOTSTRAP_SERVERS`, `ORDER_OUTBOX_*`, `KAFKA_PRODUCER_*` | `localhost:9092`, see [event-driven-architecture.md](event-driven-architecture.md#10-configuration) |

```bash
docker compose up -d postgres kafka
docker exec mercury-postgres psql -U mercury -d mercury -c "CREATE DATABASE mercury_order"   # once (plus mercury_product / mercury_inventory / mercury_notification)

# three terminals, from services/<name>:
POSTGRES_PASSWORD=mercury ./mvnw spring-boot:run        # inventory-service, product-service, order-service

# try it: create a product, give it stock, order it
curl -s -X POST localhost:8081/api/v1/products -H 'Content-Type: application/json' \
  -d '{"name":"MacBook Pro 16","sku":"MBP-16-M4","price":2499.00,"quantity":10}'
curl -s -X POST localhost:8082/api/v1/inventory -H 'Content-Type: application/json' \
  -d '{"productId":"<id>","availableQuantity":10}'
curl -i -X POST localhost:8083/api/v1/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-001' -d '{"items":[{"productId":"<id>","quantity":2}]}'
# repeat the same curl: 201 + Idempotent-Replayed: true, stock unchanged

curl -s localhost:8083/actuator/sagas          # any unfinished orders?
curl -s localhost:8083/actuator/metrics/orders.recovery.pending
```

Tests (from `services/order-service`):

```bash
./mvnw clean test                                                    # everything (the integration tests need Docker)
./mvnw clean test -Dtest='!*IntegrationTests'                        # without Docker
./mvnw clean test -Dtest='OrderFlowIntegrationTests,SagaRecoveryIntegrationTests,ResilienceIntegrationTests,EventFlowIntegrationTests'   # only the real-stack tests (~3 min)
```

## 13. Known limitations

The failure-recovery limitations are listed in [saga-recovery.md](saga-recovery.md#11-known-limitations).
In addition:

- Prices are read once, when the order is placed; a product price change between validation and
  confirmation is not detected.
- The stored response snapshot column holds up to 100,000 characters, which bounds the number of
  items per order in practice.
- Product Service's `quantity` field is unrelated to Inventory stock.
- The integration tests need Docker and expect `../product-service` and `../inventory-service`
  next to `order-service`; they rebuild both with their `mvnw` on every run.

