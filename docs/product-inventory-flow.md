# Product → Inventory reservation flow

How a stock reservation travels from a client through Product Service to Inventory
Service. Everything below reflects the code as implemented.

## 1. The reservation API

### Product Service (public entry point)

```
POST http://localhost:8081/api/v1/products/{productId}/reserve
Idempotency-Key: <caller-chosen key>
Content-Type: application/json

{ "quantity": 2 }
```

- `quantity` is required and must be at least 1.
- `Idempotency-Key` is required. A missing or blank header returns `400 MISSING_IDEMPOTENCY_KEY`.
  This is checked **before** the product lookup.

Success (`200 OK`):

```json
{
  "productId": "74b601e4-d04b-4864-bf00-262319dfa32f",
  "quantityReserved": 2,
  "availableQuantity": 8,
  "reservedQuantity": 2,
  "version": 1
}
```

On a replay the same body is returned with the extra response header
`Idempotent-Replayed: true`.

### Inventory Service (called by Product, also callable directly)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/inventory` | Create the inventory record for a product (`{"productId", "availableQuantity"}`) |
| `GET` | `/api/v1/inventory/{productId}` | Read stock |
| `PUT` | `/api/v1/inventory/{productId}` | Set `availableQuantity` |
| `POST` | `/api/v1/inventory/{productId}/reserve` | Reserve stock; needs `Idempotency-Key` (required, not blank, max 255 characters) |
| `GET` | `/api/v1/inventory/{productId}/reservations/{idempotencyKey}` | Read-only: the reservation made under that key, or `404 RESERVATION_NOT_FOUND` (used by Order Service recovery) |
| `POST` | `/api/v1/inventory/{productId}/reservations/{idempotencyKey}/fence` | Settle a key for good: `{"status":"RESERVED","reservation":{...}}` if a reservation exists, else write a tombstone (`{"status":"FENCED"}`) after which `reserve` under that key returns `409 RESERVATION_FENCED`. Idempotent; used by Order Service compensation |
| `POST` | `/api/v1/inventory/{productId}/release` | Give reserved stock back; same key rules; `409 INSUFFICIENT_RESERVED_STOCK` if more than reserved |

The Inventory reserve response additionally contains `updatedAt`; Product Service ignores
fields it does not need.

### What Product Service does, in order

1. Reject a missing/blank `Idempotency-Key` (`400`).
2. Validate the body (`quantity >= 1`).
3. Check the product exists in its own database (`existsById`). Unknown product → `404 PRODUCT_NOT_FOUND`
   and **Inventory is not called**.
4. Call Inventory Service with the **same key and quantity**.
5. Return Inventory's result (adding `Idempotent-Replayed: true` if Inventory replayed).

The method is deliberately not `@Transactional`, so no database connection is held while
waiting for the remote call.

## 2. InventoryClient and configuration

`com.mercury.product.client.InventoryClient` is a Spring `RestClient` wrapper with two
methods:

- `getInventory(productId)` → `GET /api/v1/inventory/{productId}`
- `reserveInventory(productId, quantity, idempotencyKey)` → `POST /api/v1/inventory/{productId}/reserve`

Configuration (Product Service `application.properties`):

```properties
inventory.service.url=${INVENTORY_SERVICE_URL:http://localhost:8082}
```

The URL is never hardcoded in Java. The `RestClient.Builder` comes from
`spring-boot-starter-restclient`.

**Every failure leaves the client as `InventoryServiceException`** (status + response body),
never as a raw Spring `RestClientResponseException`:

- any non-2xx answer keeps its status and body;
- a connection-level failure (`ResourceAccessException`, e.g. connection refused) becomes
  status `503` with no body.

There are **no timeouts, retries, or circuit breaker** on this call.

## 3. Idempotency-Key behavior

Inventory Service owns idempotency; Product Service only forwards the caller's key unchanged
(it never generates or alters one).

| Situation | Result |
|---|---|
| First request with a key | Stock is reserved; a record is stored; `200` |
| Same key, same `productId` + `quantity` | Stored original response returned; stock is **not** deducted again; `200` + `Idempotent-Replayed: true` |
| Same key, different `quantity` or different `productId` | `422 IDEMPOTENCY_KEY_MISMATCH`; nothing changes |
| Request failed (e.g. insufficient stock) | **Nothing is stored**; a later request with the same key is evaluated again against current stock |

Implementation details:

- The key is compared through a SHA-256 hash of `productId:quantity`, stored in
  `idempotency_records.request_hash`.
- The **original response is stored as a JSON snapshot** (`response_body`). A replay returns
  that snapshot, even if stock has changed since (for example it still shows `availableQuantity: 8`).
- The idempotency record is written **in the same database transaction** as the stock change,
  so both commit or neither does.
- `idempotency_records.idempotency_key` has a **unique constraint**. If two concurrent requests
  with the same key race, only one can insert; the other rolls back (including its stock
  change) and then returns the winner's stored result.
- Keys are unique across the whole table, not per product.
- Records are kept indefinitely; there is no retention or cleanup yet.

## 4. Error propagation and status codes

Product Service error bodies use the same shape as Inventory's:

```json
{ "timestamp": "...", "status": 409, "error": "INSUFFICIENT_STOCK", "message": "..." }
```

| Situation | Where decided | Status | `error` |
|---|---|---|---|
| Missing or blank `Idempotency-Key` | Product | 400 | `MISSING_IDEMPOTENCY_KEY` |
| Invalid body (e.g. `quantity` 0 or absent) | Product (validation) | 400 | Spring Boot's default error body (`timestamp`, `status`, `error: "Bad Request"`, `path`); no Mercury `error` code or `message` |
| Unknown product | Product | 404 | `PRODUCT_NOT_FOUND` |
| Product exists, no inventory record | Inventory, passed through | 404 | `INVENTORY_NOT_FOUND` |
| Not enough stock | Inventory, passed through | 409 | `INSUFFICIENT_STOCK` |
| Same key, different request | Inventory, passed through | 422 | `IDEMPOTENCY_KEY_MISMATCH` |
| Lost the optimistic-lock race after all retries | Inventory, passed through | 409 | `CONCURRENT_MODIFICATION` |
| Any other Inventory 4xx | Inventory, passed through | same as Inventory | Inventory's `error`, or `INVENTORY_REQUEST_REJECTED` if its body has none |
| Inventory 5xx | Product | 502 | `INVENTORY_SERVICE_ERROR` |
| Inventory unreachable | Product | 503 | `INVENTORY_SERVICE_UNAVAILABLE` |

Rule: **an Inventory 4xx keeps its own status, error code and message**; Product does not turn
it into a 500. Inventory 5xx and connection failures are reported as 502/503 so they are
distinguishable from a Product bug.

A key longer than 255 characters is rejected by Inventory's own request validation; that
case has no dedicated test.

### When Inventory Service is unavailable

- Connection refused/unreachable → Product answers `503 INVENTORY_SERVICE_UNAVAILABLE`.
- Product Service itself stays healthy and keeps serving its own endpoints
  (verified manually by stopping Inventory).
- A call that connects but never answers would hang: no timeout is configured yet.

## 5. Optimistic locking and reservation concurrency

Inventory Service protects each stock row with an `@Version` column. Every `UPDATE` carries
`WHERE version = ?`, so of two concurrent writers to the same row only the first commit wins.

`InventoryService.reserveInventory(productId, quantity, key)`:

1. The method itself is **not** `@Transactional`. Each attempt runs in its **own** transaction
   (via `TransactionTemplate`), so a retry re-reads the latest stock and idempotency record.
2. In a transaction it: looks for an existing idempotency record (replay / mismatch) → loads the
   inventory → if `available < quantity` throws `InsufficientStockException` → subtracts from
   `availableQuantity`, adds to `reservedQuantity` → flushes (the version check happens here)
   → stores the idempotency record.
3. `ObjectOptimisticLockingFailureException` (someone else changed the row) → short random
   backoff (1 to `5 × attempt` ms) → retry in a new transaction.
4. Attempts are limited by `inventory.reserve.max-attempts` (default **5**). After the last one
   the conflict is returned as `409 CONCURRENT_MODIFICATION`.
5. `InsufficientStockException` is a business answer: **never retried, never stored**.

Database safety nets (Flyway `V1`): `CHECK (available_quantity >= 0)` and
`CHECK (reserved_quantity >= 0)`, plus a unique constraint on `inventory.product_id`.

What has been observed (not guarantees beyond the invariant):

- The invariant that matters, **no overselling**, holds in every test and manual run:
  `available + reserved` equals the original stock and `available` never goes below 0.
- With enough retries (tests use 15) 100 parallel different-key requests against 10 units give
  exactly 10 successes.
- With the default 5 attempts a valid request can occasionally exhaust its retries and get
  `409 CONCURRENT_MODIFICATION` (seen once in a 100-request manual run). It is safe to retry;
  that is why `InventoryDefaultRetryConcurrencyTests` asserts only the invariants.
- 100 parallel requests with the **same** key produce exactly one reservation and 99 replays.

## 6. Tests covering this flow

| Suite | Count | What it checks |
|---|---|---|
| `InventoryClientTests` (Product) | 7 | Exact requests sent by the client, key forwarding, replay-flag parsing, error translation (404/409/500/unreachable) |
| `ProductReservationApiTests` (Product) | 13 | Product-side decisions with a **mocked** `InventoryClient`: validation order, product check, pass-through of Inventory errors, 502/503 |
| `ProductInventoryIntegrationTests` (Product) | 6 | The **real** stack, see below |
| Inventory suites | 40 | CRUD, `@Version` stale-write rejection, reservation, idempotency, concurrency (H2) |

### The real Product → Inventory → PostgreSQL integration test

`ProductInventoryIntegrationTests` (with helper `RealInventoryStack`) exercises the actual
HTTP boundary with no mocks:

- A PostgreSQL container is started with **Testcontainers** (`postgres:16-alpine`), with one
  database per service.
- Inventory Service is **built** with its own `mvnw package -DskipTests` and **launched as a
  separate process** (`java -jar`) on a free port; Inventory's Flyway migrations run on the
  container.
- Product Service runs in the test JVM (`@SpringBootTest`, random port) with the real
  `InventoryClient` pointing at that process.
- The test calls Product's HTTP API, then **reads the resulting state directly from PostgreSQL
  with JDBC**.

Scenarios: reservation updates PostgreSQL (10 → 7 available / 3 reserved); a replayed key sent
three times deducts once (10 → 8 / 2, one record, `Idempotent-Replayed: true`); insufficient
stock returns 409 and changes nothing; same key with a different quantity returns 422; a product
without inventory returns `INVENTORY_NOT_FOUND`; an unknown product returns `PRODUCT_NOT_FOUND`
and touches nothing in Inventory.

Each test uses fresh UUIDs and deletes its own rows afterwards; the container is removed
automatically when the JVM exits.

## 7. Known limitations

- No timeouts, retries, circuit breaker or other resilience on Product → Inventory calls.
- Idempotency records are never cleaned up.
- Product Service has no Flyway migrations (`ddl-auto=update`).
- `Product.quantity` (a field on the product) is independent of Inventory's stock; nothing keeps
  them in sync.
- Product Service does not create inventory records; they must be created via Inventory Service.
- Inventory now has a release endpoint (`POST /api/v1/inventory/{productId}/release`), used by Order Service for compensation; Product Service does not call it. See [Order Service](order-service.md#6-inventory-integration).
- The real integration test has its own caveats, see [Local development](local-development.md#caveats).
