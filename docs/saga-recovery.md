# Saga reliability and recovery (Phase 8)

Phase 7 made the order saga safe while a request is running. Phase 8 makes it **recoverable**: after a
lost response, a failed compensation, an unavailable dependency, or a crashed process, every order
ends in a final state without anyone intervening, and without overselling or double reservation.
Everything below describes what is implemented.

## 1. The rule

> An order that does not reach `CONFIRMED` always ends `CANCELLED`, with all of its stock given back.

That matches what the client was told (an error, or nothing at all). It also makes recovery
deterministic: whatever state a saga is left in, there is exactly one way forward, described in
section 4. A client that retries with the same Idempotency-Key after a cancelled attempt simply gets a
fresh, independent attempt.

## 2. Durable state

Nothing the saga needs to remember lives only in a request thread.

**Per item: `order_items.reservation_status`**, written *before* each remote call:

| Status | Meaning |
|---|---|
| `NOT_STARTED` | nothing attempted |
| `RESERVING` | a reserve call was started and its outcome is **unknown**: Inventory may or may not hold it |
| `RESERVED` | Inventory confirmed the reservation |
| `NOT_RESERVED` | Inventory certainly holds nothing for this item |
| `RELEASING` | a release call was started and its outcome is unknown (releasing again is safe) |
| `RELEASED` | the stock was given back |

**Per order: `order_saga`** (one row per order):

| Column | Purpose |
|---|---|
| `state` | `RESERVING`, `AWAITING_INVENTORY` (Phase 10, ASYNC orders), `COMPENSATING`, `CONFIRMED`, `CANCELLED`, `RECOVERY_FAILED` |
| `attempt_count`, `last_error` | how many recovery attempts failed, and why |
| `next_attempt_at` | when recovery should look at it next (backoff, or "abandoned after") |
| `locked_until` | lease: who owns it right now |
| `version` | optimistic lock (a second writer fails loudly) |

States: `RESERVING` is the live request working forward. `COMPENSATING` means the order did not complete
and its stock is being given back (with `attempt_count > 0` this is the compensation that has to be retried).
`CONFIRMED` and `CANCELLED` are final. `RECOVERY_FAILED` means the retries ran out and a person must look.

Flyway `V3__add_durable_saga.sql` adds both, and backfills existing orders: confirmed orders' items become
`RESERVED`, cancelled ones `RELEASED`, and an order still `PENDING` from before the migration gets its items
marked `RESERVING` and a `COMPENSATING` saga due immediately, so Phase 7 leftovers are recovered too.

## 3. Ambiguous reservations

The problem: Inventory commits a reservation, then the response is lost (timeout, dropped connection). Order
Service cannot tell "reserved" from "never arrived".

The solution has two halves.

1. **Record the intent first.** Before every reserve call the item is saved as `RESERVING`. Whatever happens
   next, the database says "outcome unknown".
2. **Make the outcome queryable by its key.** Inventory exposes a read-only lookup:

   `GET /api/v1/inventory/{productId}/reservations/{idempotencyKey}`
   - `200` with the stored reservation: it happened;
   - `404 RESERVATION_NOT_FOUND`: Inventory holds nothing under that key (also for a key that was used for a
     release, or for a different product, or whose reservation failed).

   To tell reservations from releases, Inventory's `idempotency_records` gained an `operation` column
   (Flyway `V3__add_idempotency_operation.sql`, backfilled from the stored response).

   The read-only lookup alone is not enough to cancel on: "not found" only describes the moment of the question,
   and a reserve still in flight could be applied right after it (see section 11). So compensation settles the
   key with a **fence**:

   `POST /api/v1/inventory/{productId}/reservations/{idempotencyKey}/fence` (SERVICE or ADMIN role)
   - `200 {"status":"RESERVED","reservation":{...}}`: a reservation exists under the key (the caller must release it);
   - `200 {"status":"FENCED"}`: nothing was held and a tombstone now is: every later `reserve` under that key
     is refused with `409 RESERVATION_FENCED` and changes nothing;
   - idempotent; `422 IDEMPOTENCY_KEY_MISMATCH` for a key used for a release or another product.

   The tombstone is a row in `idempotency_records` with `operation = FENCED` (Flyway `V6__fenced_reservations.sql`).
   The key is unique, so exactly one of "reserve" and "fence" can claim it: a reserve still in flight when the fence
   commits fails its own insert, rolls back its stock change and is answered `409`; a fence that loses to a committed
   reserve sees that reservation and reports `RESERVED`. Deploy Inventory before Order Service (an Order that calls
   `/fence` on an older Inventory gets an error, treats the item as unresolved and retries through recovery).

Order Service resolves an unknown item by fencing the key: `RESERVED` means treat it as `RESERVED` (then release it);
`FENCED` means `NOT_RESERVED`. No blind second reserve is ever sent. If Inventory cannot be asked, nothing is
guessed: the item stays `RESERVING` and the saga waits for recovery.

A request that is rejected locally (circuit open, bulkhead full) was never sent, so that outcome is *certain*
and the item goes straight to `NOT_RESERVED` without a lookup. A 4xx from Inventory is likewise definitive.

## 4. Compensation: one routine, used everywhere

`OrderSagaService.compensate(orderId)` is the only place stock is given back. Both the live request and the
recovery worker call it. For each item (newest first):

| Item status | Action |
|---|---|
| `NOT_STARTED`, `NOT_RESERVED`, `RELEASED` | nothing |
| `RESERVING` | look the reservation up (section 3): none, mark `NOT_RESERVED`; found, mark `RESERVED` and continue |
| `RESERVED`, `RELEASING` | mark `RELEASING`, call release under the deterministic key `order:{orderId}:product:{productId}:release`, mark `RELEASED` |

When every item is settled the order is marked `CANCELLED`, the saga `CANCELLED`, and the client's
Idempotency-Key is freed. If anything is outstanding the saga stays `COMPENSATING` and a retry is scheduled.

It is safe to run any number of times, from any process: it only reads durable state, and Inventory's reserve,
lookup and release are idempotent by key. A repeated release is a replay, not a second release.

Two rules guard the one dangerous mistake, releasing stock that belongs to a confirmed order:
- if marking the order `CONFIRMED` appears to fail, the order's real state is read first; if it *is* confirmed
  the confirmed order is returned and nothing is released;
- if that state cannot be read at all, stock is **not** released (a temporary leak is recoverable, an oversell
  is not).

## 5. The recovery worker

`SagaRecoveryWorker` runs `SagaRecovery.recoverDue()` on a fixed delay. A pass:

1. **Claims** due sagas in one short transaction: `RESERVING` or `COMPENSATING` sagas whose
   `next_attempt_at` has passed and whose lease has expired, selected with row locks
   (`FOR UPDATE SKIP LOCKED`) and leased to the caller.
2. **Processes** each outside any transaction by running the compensation routine.
3. **Finishes** the saga, or **schedules a retry** with exponential backoff.

A saga still `RESERVING` is only picked up after `stale-after`: the live request that owns it refreshes the
lease and that deadline on every step, so a slow but alive request is left alone, while one whose process died
becomes due.

### Two workers cannot process the same saga
Exclusivity comes from three independent layers: `SKIP LOCKED` row locks when claiming (a second worker is
given different rows, or none), the lease (`locked_until`) that keeps a claimed saga out of later claims, and
the saga's `@Version` (a stale writer fails). Even if two workers did overlap, every call they make is
idempotent by key, so nothing could be reserved or released twice. Verified with six simultaneous workers on
real PostgreSQL.

### Bounded retries
Each failed attempt waits longer before the next: `initial-backoff × multiplier^(attempts−1)`, capped at
`max-backoff` (defaults: 1 s, 2 s, 4 s, 8 s … up to 5 min). After `max-attempts` (default 10) the saga becomes
`RECOVERY_FAILED`: the worker stops touching it, the order stays `PENDING`, and it appears in
`/actuator/sagas` and the `orders.recovery.exhausted` metric.

### Crash handling
If the Order Service process dies mid-saga, the database holds exactly what was in flight (items `RESERVING` /
`RESERVED`, order `PENDING`, saga `RESERVING`). After `stale-after` any instance's worker picks the saga up,
resolves the unknown items, releases what is held and cancels the order. Verified by `kill -9` of a real Order
Service process while Inventory had applied a reservation whose answer was still pending.

## 6. Circuit breaker and bulkhead (live path only)

Resilience4j, used programmatically (its Spring Boot starters target Boot 3), around every call to Product and
Inventory. They protect **requests**; recovery completes **accepted work**. Neither replaces the other.

- **Circuit breaker** per downstream: opens when the failure rate over the sliding window reaches the threshold
  (with at least `minimum-calls`), stays open for `wait-duration-in-open-state`, then lets
  `permitted-calls-in-half-open-state` trial calls through and closes again if they succeed. Only 5xx and
  unreachable/timeouts count as failures; a 4xx (`409 INSUFFICIENT_STOCK`, `404`) is a healthy "no".
- **Bulkhead** per downstream: at most `max-concurrent-calls` calls in flight; excess calls are rejected at
  once instead of piling up on threads.
- A rejected call surfaces as `503 *_SERVICE_UNAVAILABLE` and is marked "never sent".
- `minimum-calls` larger than `sliding-window-size` is rejected at startup (Resilience4j would otherwise cap
  it silently, opening the circuit much earlier than configured).
- Timeouts remain: connect 2 s, read 5 s.

## 7. Observability

**Logs** use `key=value` fields and never contain the raw Idempotency-Key (only a short hash is ever logged):
`saga orderId=… operation=RESERVE|FENCE|RELEASE productId=… result=OK|FOUND|FENCED|FAILED reason=… durationMs=…`,
and `recovery orderId=… attempt=… result=…`.

**Metrics** (`/actuator/metrics/<name>`):

| Metric | Meaning |
|---|---|
| `orders.created`, `orders.confirmed`, `orders.cancelled` | order outcomes |
| `orders.recovery.success` / `orders.recovery.failed` | sagas finished by recovery / attempts that did not finish |
| `orders.recovery.pending` | sagas due for recovery right now |
| `orders.recovery.exhausted` | sagas in `RECOVERY_FAILED` (need a person) |
| `orders.saga.count{state}` | sagas per state |
| `saga.compensation.success` / `saga.compensation.failure` | releases that worked / failed |
| `inventory.reserve.timeout`, `inventory.release.timeout` | Inventory calls with an unknown outcome |
| `downstream.circuit.state{service}` | 0 closed, 1 half-open, 2 open |
| `downstream.bulkhead.available{service}` | free bulkhead slots |

**`GET /actuator/sagas`** is a read-only overview: counts per state, how many are due, the age of the oldest
unfinished saga, and up to 100 unfinished sagas with state, attempts, age, next attempt and last error. It
answers "are any orders stuck, for how long, after how many attempts?". It shows order ids only, never
idempotency keys, and nothing here can trigger recovery. **There is no authentication in the system yet:** do
not expose actuator endpoints on a public interface (`MANAGEMENT_ENDPOINTS` controls what is exposed).

## 8. Configuration

All values are in `application.properties` with an environment override.

| Property / variable | Default | Meaning |
|---|---|---|
| `order.recovery.enabled` / `ORDER_RECOVERY_ENABLED` | `true` | run the background worker |
| `…interval` / `ORDER_RECOVERY_INTERVAL` | `10s` | pause between passes |
| `…batch-size` / `ORDER_RECOVERY_BATCH_SIZE` | `20` | sagas claimed per pass |
| `…max-attempts` / `ORDER_RECOVERY_MAX_ATTEMPTS` | `10` | failed attempts before `RECOVERY_FAILED` |
| `…initial-backoff` / `ORDER_RECOVERY_INITIAL_BACKOFF` | `1s` | wait after the first failure |
| `…max-backoff` / `ORDER_RECOVERY_MAX_BACKOFF` | `5m` | backoff ceiling |
| `…backoff-multiplier` / `ORDER_RECOVERY_BACKOFF_MULTIPLIER` | `2.0` | growth per attempt |
| `…lease` / `ORDER_RECOVERY_LEASE` | `60s` | exclusive ownership of a saga |
| `…stale-after` / `ORDER_RECOVERY_STALE_AFTER` | `60s` | a live saga with no heartbeat this long is abandoned |
| `order.resilience.{inventory,product}.failure-rate-threshold` | `50` | % failures that open the circuit |
| `…sliding-window-size` / `…minimum-calls` | `20` / `10` | measurement window / calls needed first |
| `…wait-duration-in-open-state` | `10s` | open time before trial calls |
| `…permitted-calls-in-half-open-state` | `3` | trial calls |
| `…max-concurrent-calls` / `…max-wait` | `25` / `0ms` | bulkhead |
| `HTTP_CLIENT_CONNECT_TIMEOUT` / `HTTP_CLIENT_READ_TIMEOUT` | `2s` / `5s` | per-call timeouts |
| `MANAGEMENT_ENDPOINTS` | `health,metrics,sagas` | exposed actuator endpoints |

The environment variable for each circuit/bulkhead setting is `ORDER_<INVENTORY|PRODUCT>_CB_*`
(`FAILURE_RATE`, `WINDOW`, `MIN_CALLS`, `OPEN_FOR`, `HALF_OPEN_CALLS`) and `ORDER_<…>_MAX_CONCURRENT` /
`ORDER_<…>_MAX_WAIT`; see `application.properties`.

## 9. Failure scenarios and what happens

| Scenario | Outcome |
|---|---|
| Inventory rejects a reservation (409/404) | earlier items released, order `CANCELLED`, the real error returned |
| Inventory applies a reservation, response lost | lookup finds it, it is released, order `CANCELLED`; client sees 503 |
| Same, but Inventory also cannot be queried | order `PENDING`, saga `COMPENSATING`, stock stays reserved *and recorded*; recovery resolves it once Inventory answers |
| Request never reached Inventory | lookup finds nothing, nothing released, order `CANCELLED` |
| Saving the confirmed order fails after reserving | status checked; if not confirmed, everything released, `CANCELLED`, `500 ORDER_PROCESSING_FAILED` |
| A release fails | saga stays `COMPENSATING`; retried with backoff under the same key |
| Order Service killed mid-saga | after restart (or by another instance) recovery releases the stock and cancels the order |
| Inventory down | requests bounded by timeouts, then the circuit opens and they fail in milliseconds; pending orders are resolved after it returns; the circuit closes |
| Retries exhausted | `RECOVERY_FAILED`, order `PENDING`, visible in `/actuator/sagas` and metrics |
| Two recovery workers at once | one takes the saga; stock is restored exactly once |

## 10. Tests

| Suite | Count | What it proves |
|---|---|---|
| `SagaRecoveryTests` | 12 | durable intent before the call, lost response resolved by lookup, "never reached" resolved, unqueryable Inventory leaves the order pending then resolves, failed release retried with the same key, abandoned saga recovered and key freed, backoff and the attempt limit, idempotent and exclusive recovery, metrics |
| `DownstreamGuardTests` | 8 | circuit opens, fails fast, half-opens, closes, 4xx do not trip it, bulkhead rejects, contradictory settings refused |
| `SagaOperationsTests` | 4 | `/actuator/sagas`, no keys leaked, recovery metrics, circuit gauge |
| `InventoryReservationLookupTests` (Inventory) | 7 | lookup finds, changes nothing, 404 cases |
| **`SagaRecoveryIntegrationTests`** | 7 | **real stack with a fault proxy**: response lost (found and released; also when un-queryable then recovered), request refused, failed compensation then recovery, retries exhausted, six simultaneous workers, `kill -9` of the Order process |
| **`ResilienceIntegrationTests`** | 1 | **real stack**: Inventory stopped, bounded latency, circuit opens, fail-fast, resolves after restart, circuit closes |

The real-stack tests use a Testcontainers PostgreSQL, the real Product and Inventory services as separate
processes, and a small fault-injecting proxy in front of Inventory (drop a response after the work was done,
refuse, delay). The kill test runs Order Service as its own process from its real main classes and
configuration. Every assertion reads PostgreSQL directly.

## 11. Known limitations

- **A late request (closed for the synchronous path).** A reserve still in flight when Order Service gives up on
  it can no longer be applied after the cancel: compensation fences the key at Inventory first (section 3), and
  a reserve under a fenced key is refused. Covered by `SagaRecoveryIntegrationTests` (real stack,
  the reserve is held in the fault proxy until after the cancel) and `InventoryReservationFenceTests` (latch tests
  for both orders of the race, duplicates and concurrency). Fence tombstones are never cleaned up.
- `RECOVERY_FAILED` orders need a person; there is no admin operation to retry or force-cancel them, by design
  (no authentication exists yet).
- Failed attempts leave `CANCELLED` orders behind and idempotency records are never cleaned up.
- A saga that was `CONFIRMED` by the Phase 7 request path before `V3` has no unfinished work, so only
  `PENDING` leftovers are recovered by the migration backfill.
- Retries exist only inside recovery; the live request path still makes single attempts.
- Circuit breaker and bulkhead state is per Order Service instance.
- The bulkhead sheds load: a burst larger than `max-concurrent-calls` gets some `503`s by design.
- No Redis, payment or authentication; Product Service still has no Flyway.
- ASYNC orders (Phase 10) add the `AWAITING_INVENTORY` state and a deadline lookup; see
  [async-reservation.md](async-reservation.md#5-the-deadline). The ASYNC path is not fenced; its
  late reservations are released by compensation, see [async-reservation.md](async-reservation.md#11-known-limitations).
