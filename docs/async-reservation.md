# Asynchronous inventory reservation (Phase 10)

Phase 10 adds a second way to reserve stock for an order: instead of calling Inventory over REST during the
request (the Phase 7/8 saga, called **SYNC** here), Order Service can hand the whole order to Inventory over
Kafka and finish it when Inventory answers (**ASYNC**). It is a strangler migration: both paths exist, SYNC is
the default and is unchanged, and nothing from Phases 6–9 was removed or weakened. Everything below describes
what is implemented.

## 1. Choosing the mode

```properties
order.reservation.mode=${ORDER_RESERVATION_MODE:SYNC}                     # SYNC | ASYNC
order.reservation.async-deadline=${ORDER_RESERVATION_ASYNC_DEADLINE:60s}  # how long an ASYNC order waits for the reply
```

- The mode applies to **new** orders only. Each order stores the mode it was created with
  (`orders.reservation_mode`), so switching the setting while orders are in flight cannot corrupt them: an
  ASYNC order is always finished by the ASYNC machinery, a SYNC order by the SYNC saga.
- The reply consumer runs in both modes, so replies for ASYNC orders created earlier are still handled after
  switching back to SYNC.

## 2. The flow

```
Client ──POST /orders──> Order Service
                           1 transaction: PENDING order + idempotency claim + saga AWAITING_INVENTORY
                                          + outbox: OrderCreated, InventoryReservationRequested
          <── 202 Accepted + Location ──

Order outbox ──> mercury.inventory.commands ──> Inventory Service
                                                  1 transaction: reserve ALL items or none
                                                                 (key order:{orderId}), store RESERVED/REJECTED,
                                                                 processed-event marker, outbox: reply
Inventory outbox ──> mercury.inventory.events ──> Order Service (consumer)
                                                  1 transaction: processed-event marker + order CONFIRMED
                                                                 (or CANCELLED) + saga + OrderConfirmed/Cancelled event
```

Two decisions make this safe:

- **A dedicated command, not `OrderCreated`.** `OrderCreated` is a fact that other consumers rely on. If
  Inventory reserved on it, every SYNC order (which already reserved over REST) would be reserved twice. The
  command `InventoryReservationRequested` is written only for ASYNC orders.
- **One atomic, all-or-nothing reservation per order in Inventory.** Inventory reserves every item in one
  transaction under the key `order:{orderId}` and stores the outcome (RESERVED or REJECTED, with the reason and
  the product that failed). A redelivered or repeated command finds the stored outcome and changes nothing, so a
  REJECTED order can never become RESERVED later, and a RESERVED one is never reserved twice. This is
  independent of the per-item REST reservations (`idempotency_records`), which are untouched.

## 3. Client contract

| Situation | Response |
|---|---|
| New ASYNC order | `202 Accepted`, `Location: /api/v1/orders/{id}`, body = the order, `PENDING` |
| Retry (same key and payload) while still waiting | `202` again, same order, `Idempotent-Replayed: true`; never waits |
| Retry after the order is final (`CONFIRMED` or `CANCELLED`) | `200 OK`, the final order, `Idempotent-Replayed: true` |
| Same key, different payload | `422 IDEMPOTENCY_KEY_MISMATCH` (as before) |
| SYNC order | `201 Created`, replays `201` (unchanged) |

The client polls `GET /api/v1/orders/{id}` (the `Location`) until the status is `CONFIRMED` or `CANCELLED`.
A **cancelled ASYNC order keeps its idempotency key**: a replay returns the cancelled order, and a new attempt
needs a new key. (In SYNC mode a cancelled attempt frees its key, as before.) The cancellation reason travels
in the `OrderCancelled` event (`INSUFFICIENT_STOCK`, `INVENTORY_NOT_FOUND`, `RESERVATION_TIMEOUT`, …).

## 4. Order Service state

- **Saga state `AWAITING_INVENTORY`** (new): an ASYNC order waiting for Inventory. `next_attempt_at` is the
  waiting deadline (`created + async-deadline`). It is `active`, so recovery sees it, but only once the deadline
  has passed. The other states are unchanged.
- **`order_processed_events`**: ids of Inventory events already handled, written in the same transaction as
  their effect (consumer idempotency).
- **Flyway `V5`** adds `orders.reservation_mode`, the new saga state and outbox event type, and the processed
  events table. `V1`–`V4` are untouched.

### Handling a reply (`OrderTransactions.applyInventoryReserved` / `applyInventoryRejected`)

| Reply | Order is… | Result |
|---|---|---|
| `InventoryReserved` | `PENDING`, saga `AWAITING_INVENTORY` | all items `RESERVED`, order `CONFIRMED`, `OrderConfirmed` written |
| `InventoryReserved` | `CANCELLED`, or `PENDING` but already being cancelled / given up | items marked held and the saga **reopened as `COMPENSATING`** (see section 6) |
| `InventoryReserved` | `CONFIRMED` | ignored: the stock it reports is the order's own |
| `InventoryRejected` | `PENDING`, `AWAITING_INVENTORY` | order `CANCELLED` with Inventory's reason, `OrderCancelled` written |
| any | unknown, or a SYNC order | ignored (and remembered) |
| same event id again | any | ignored (`DUPLICATE`) |

## 5. The deadline

If no reply has arrived by the deadline (the command or reply was lost, dead-lettered, or Kafka was down),
recovery asks Inventory directly: `GET /api/v1/inventory/reservations/orders/{orderId}` (read-only).

| Inventory says | Order |
|---|---|
| `RESERVED` | `CONFIRMED` |
| `REJECTED` + reason | `CANCELLED` with that reason |
| nothing decided yet (`404 ORDER_RESERVATION_NOT_FOUND`) | `CANCELLED` with `RESERVATION_TIMEOUT` |
| unreachable / error | stays `AWAITING_INVENTORY`, retried with the existing exponential backoff; after `order.recovery.max-attempts` it becomes `RECOVERY_FAILED` (needs a person). **Never cancelled on a guess.** |

If a reply arrives first, the order is no longer waiting and the deadline path leaves it alone (the state
checks are inside the same transaction as the change).

## 6. A reservation that turns up late

"Nothing decided yet" is not a promise that Inventory never will: the command may still be queued. So an order
can be cancelled for `RESERVATION_TIMEOUT` and **then** Inventory reserves for it. Order Service handles this
without a new mechanism:

1. The late `InventoryReserved` reply finds a finished order, marks its items as held and reopens the saga as
   `COMPENSATING`, due immediately.
2. The existing Phase 8 recovery runs the normal compensation: `release` per item with the idempotent release
   key, then `CANCELLED`. Retries use the same backoff and bound.
3. Cancelling is idempotent: an already-cancelled order is not announced twice (`OrderCancelled` is written
   once), and `cancel` **refuses to finish while any item may still be held** (`StockStillHeldException`), so
   a reservation that turns up between a lookup and a cancel can never be left without an owner.

## 7. Delivery guarantees

| Hop | Guarantee |
|---|---|
| Order → command | outbox row in the order's transaction; at-least-once; per-order ordering |
| Command → Inventory | manual commit after processing; duplicate commands change nothing |
| Inventory → reply | Inventory's own outbox (a copy of Order's, by design: no shared library) |
| Reply → Order | manual commit after processing; effect and marker in one transaction; duplicates ignored |
| Failures | bounded retries with backoff, then the dead-letter topic; malformed messages skip the retries |

A consumer that crashes after committing the effect but before acknowledging is redelivered; the marker makes
the second delivery a no-op (tested).

### Topics

| Topic | Partitions | Key | Content |
|---|---|---|---|
| `mercury.inventory.commands` | 3 | `orderId` | `InventoryReservationRequested` |
| `mercury.inventory.commands.dlq` | 1 | – | commands Inventory could not process |
| `mercury.inventory.events` | 3 | `orderId` | `InventoryReserved`, `InventoryRejected` |
| `mercury.inventory.events.dlq` | 1 | – | replies Order could not process |

A dead-lettered command or reply leaves its order waiting; the deadline lookup resolves it.

## 8. Configuration (all overridable)

Order Service: `ORDER_RESERVATION_MODE`, `ORDER_RESERVATION_ASYNC_DEADLINE`, `ORDER_INBOUND_ENABLED`,
`INVENTORY_COMMAND_TOPIC`, `INVENTORY_EVENT_TOPIC`, `INVENTORY_EVENT_DLQ_TOPIC`, `INVENTORY_TOPIC_PARTITIONS`,
`ORDER_INBOUND_RETRY_{MAX_ATTEMPTS,INITIAL_INTERVAL,MULTIPLIER,MAX_INTERVAL}`, `ORDER_CONSUMER_GROUP`; recovery
and outbox settings are the existing `ORDER_RECOVERY_*` and `ORDER_OUTBOX_*`.

Inventory Service: `KAFKA_BOOTSTRAP_SERVERS`, `INVENTORY_CONSUMER_GROUP`, `INVENTORY_COMMAND_TOPIC`,
`INVENTORY_COMMAND_DLQ_TOPIC`, `INVENTORY_OUTBOX_TOPIC`, `INVENTORY_OUTBOX_*`, `INVENTORY_RETRY_*`,
`INVENTORY_TOPIC_PARTITIONS`, `KAFKA_PRODUCER_*`. Both services start and serve REST with Kafka down.

## 9. Observability

- Inventory: `inventory.commands.{consumed,duplicate,failed,dlq}`, `inventory.order.{reserved,rejected}`,
  `events.published`, `events.publish.failed`, `outbox.pending`; read-only `/actuator/outbox` and
  `GET /api/v1/inventory/reservations/orders/{orderId}`.
- Order: `order.inventory.events.{consumed,duplicate,ignored,failed,dlq}`,
  `order.inventory.late.reservations`, plus `orders.saga.count{state=AWAITING_INVENTORY}`,
  `orders.recovery.pending` (now includes waiting orders past their deadline) and `/actuator/sagas`.

## 10. Tests

| Where | Suite | Count | Covers |
|---|---|---|---|
| Inventory | `OrderReservationServiceTests`, `OrderReservationEndpointTests`, `OutboxPublisherTests`, `OutboxEndpointTests` | 27 | atomic all-or-nothing reservation, idempotency, concurrency, lookup, outbox |
| Inventory | `ReservationCommandKafkaTests` (real Kafka) | 10 | one reply per command, duplicates, consumer crash, transient failure, poison, Kafka outage, publisher crash |
| Order | `AsyncReservationTests` | 20 | accept, replies, replays, late reservations, cancel guard |
| Order | `AsyncReservationRecoveryTests` | 7 | deadline lookup (confirm / reject / timeout), Inventory unreachable, `RECOVERY_FAILED` |
| Order | `AsyncOrderApiTests`, `InventoryClientTests` (+3) | 6 + 3 | 202 / 200 / 422 contract, lookup client |
| Order | `InventoryEventKafkaTests` (real Kafka) | 7 | reply consumer: duplicates, crash after commit, retries, poison, late reservation |
| Order | `AsyncReservationIntegrationTests` (**real stack**) | 9 | accept → reserve → confirm; insufficient stock; replays; 20 concurrent orders for 5 units; Kafka down; Inventory down; duplicate and poison commands |
| Order | `AsyncFailureInjectionIntegrationTests` (**real stack**, Order as a killable process) | 4 | `kill -9` between request and reply; reply that never arrives; reservation after timeout released once; Inventory unreachable at the deadline |

The real-stack tests run PostgreSQL, Kafka, Product, Inventory and Notification as real processes and read
PostgreSQL directly.

## 11. Known limitations

- **No Inventory-side cancellation fence for ASYNC orders.** (The synchronous path has one since
  `V6__fenced_reservations.sql`: [saga-recovery.md](saga-recovery.md#3-ambiguous-reservations).) Order cancels on a timeout and Inventory may reserve afterwards;
  the late reply is released through compensation (section 6), which is correct but leaves the stock held
  until that reply is consumed and the release runs. A fence ("Order cancelled this id: refuse the command")
  would close that window; it was deliberately deferred. The idempotent release remains the safety mechanism.
- **The late reply must be consumed.** The release in section 6 starts from the late `InventoryReserved` reply. With
  `ORDER_INBOUND_ENABLED=false` nothing consumes it, so a reservation that turns up after a `RESERVATION_TIMEOUT` stays held
  at Inventory and only the deadline lookup is left; that switch is for tests that isolate the lookup, not for running Order.
- Compensation after a late reservation releases per product with the existing release endpoint; Inventory's
  `order_reservations` row stays `RESERVED` (it records that the decision was made, not that it was undone).
- A `RECOVERY_FAILED` ASYNC order that later receives `InventoryRejected` is left for a person; one that
  receives `InventoryReserved` is released and cancelled.
- `order_processed_events`, Inventory's `processed_events`, outbox rows and `order_reservations` are never
  cleaned up.
- The waiting deadline is a single value for all orders; there is no per-order override.
- Dead-lettered commands and replies have no replay tooling.
- ASYNC does not report progress beyond `PENDING`: the client polls.
- Kafka has no authentication or TLS in this setup.
