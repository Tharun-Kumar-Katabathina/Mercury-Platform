# Event-driven architecture (Phase 9)

Phase 9 adds durable, asynchronous domain events on top of the existing synchronous system. It adds Kafka,
a transactional outbox in Order Service, and a Notification Service that consumes the events. It does
**not** replace anything from Phases 6–8. Everything below describes what is implemented.

## 1. Where Kafka fits

```
Client --REST--> Order Service :8083 --REST--> Product :8081 / Inventory :8082
                      |
                      |  one DB transaction: order state change + outbox row
                      v
                 PostgreSQL (order_outbox)
                      |
                 Outbox publisher
                      |
                      v
                    Kafka  (mercury.order.events, mercury.order.events.dlq)
                      |
                      v
              Notification Service :8084 --> PostgreSQL (notification)
```

**REST stays for synchronous work:** validating products and reserving stock, because the caller needs the
answer now. **Kafka carries facts that already happened** (an order was created, confirmed, cancelled) to
anyone who wants to react, without Order Service knowing who they are.

> **Phase 10 update:** reservation can now also be asynchronous over Kafka (`order.reservation.mode=ASYNC`); see
> [async-reservation.md](async-reservation.md). The text below is the Phase 9 decision, which still describes the
> default SYNC mode.

### Decision: reservation stays synchronous (Option A)
The Phase 8 saga is the source of truth: reservation, compensation, `order_saga`, per-item status, the recovery
worker, optimistic locking, idempotency, the circuit breaker and the bulkhead are all unchanged and still
run on the request path. Kafka sits beside them, not in place of them. Converting reservation itself to
asynchronous events (`OrderCreated` → Inventory → `InventoryReserved`/`InventoryRejected` → Order) is a much
larger saga redesign and is deliberately **not** done here. For that reason Inventory does not consume
events yet.

## 2. Topology

| Topic | Partitions | Key | Purpose |
|---|---|---|---|
| `mercury.order.events` | 3 | `orderId` | `OrderCreated`, `OrderConfirmed`, `OrderCancelled` |
| `mercury.order.events.dlq` | 1 | none | records the consumer could not process |

Consumer group: `notification-service`. Topic names, partition count and group are configurable
(`ORDER_OUTBOX_TOPIC`, `NOTIFICATION_TOPIC`, `NOTIFICATION_DLQ_TOPIC`, `NOTIFICATION_TOPIC_PARTITIONS`,
`NOTIFICATION_CONSUMER_GROUP`). Each record also carries the Kafka headers `event-id` and `event-type`.

**Retry topic: not used, on purpose.** A separate retry topic would let a later event of the same order
overtake an earlier one that is waiting to be retried. Retries therefore happen inside the consumer with
bounded backoff (section 7), and only then does a record go to the dead-letter topic.

## 3. Event contracts

Every event has `eventId` (a UUID, the deduplication key), `eventType`, `occurredAt` (ISO-8601) and `orderId`.
Events carry facts about the order and **never** contain secrets or the client's Idempotency-Key.

`OrderCreated`:
```json
{ "eventId": "…", "eventType": "OrderCreated", "occurredAt": "2026-10-06T10:00:00Z", "orderId": "…",
  "items": [ { "productId": "…", "quantity": 2, "name": "MacBook Pro 16", "sku": "MBP-16-M4", "unitPrice": 2499.00 } ],
  "totalAmount": 4998.00 }
```
`OrderConfirmed`: the common fields only. `OrderCancelled`: the common fields plus `reason`, one of
`INSUFFICIENT_STOCK`, `INVENTORY_NOT_FOUND`, `RESERVATION_REJECTED`, `INVENTORY_UNAVAILABLE`,
`CONFIRMATION_FAILED`, `ORDER_PROCESSING_FAILED`, `RECOVERED_AFTER_INTERRUPTION`, `UNKNOWN`.
(The reason is recorded when compensation begins and kept by the first failure, so a recovery that finishes
the job later still reports the original cause.)

The consumer reads only the fields it needs and ignores unknown ones, so producer and consumer evolve
independently; there is no shared library.

## 4. The transactional outbox

The problem: "commit the database, then publish to Kafka" can crash in between, and the event is lost.

The solution (`order_outbox`, Flyway `V4__add_outbox.sql`): the event is **inserted in the same database
transaction as the state change it describes**.

| State change | Transaction | Event |
|---|---|---|
| Order saved `PENDING` (+ claim + saga) | `OrderTransactions.createPending` | `OrderCreated` |
| Order `CONFIRMED` | `OrderTransactions.confirm` | `OrderConfirmed` |
| Order `CANCELLED` (after stock is given back) | `OrderTransactions.cancel` | `OrderCancelled` |

If the transaction rolls back, the event never existed; if it commits, the event is durable. This is
enforced, not conventional: `OutboxWriter.append` is `@Transactional(propagation = MANDATORY)`, so calling it
outside a transaction fails immediately. A test inserts the `OrderConfirmed` row and then fails the
transaction: both the confirmation and the event disappear.

Columns: `seq` (identity, defines order), `event_id` (unique), `aggregate_id` (the order), `event_type`,
`payload`, `created_at`, `published_at` (null while waiting), `attempt_count`, `next_attempt_at`,
`locked_until`, `last_error`. A replayed request (same Idempotency-Key) writes no new events, and 100
concurrent duplicates produce exactly one `OrderCreated` and one `OrderConfirmed`.

## 5. The publisher

`OutboxPublisher` (run by `OutboxPublisherWorker` on a fixed delay):

1. **Claim**: a short transaction selects due events with row locks (`FOR UPDATE SKIP LOCKED`) and leases them.
2. **Send** to Kafka and wait for the acknowledgement (`acks=all`, idempotent producer). **No database
   transaction is open during this step**, the same rule as Phase 8's remote calls.
3. **Mark**: a short transaction sets `published_at`.

Properties:
- **Never lost.** A failed send schedules a retry with exponential backoff and is retried forever; events are
  never dropped.
- **Per-order ordering.** An event is only eligible when no older event of the same order is still unpublished,
  and all events of an order share a partition key, so they cannot overtake each other, even across failures.
  Different orders are independent: one poisoned order does not block the others.
- **Several publishers are safe.** Row locks + a lease: each event is claimed by one publisher at a time.
- **At-least-once.** If the process dies after Kafka acknowledged an event but before `published_at` was saved,
  the lease expires and the event is sent again. That duplicate is expected and handled by the consumer.
- **Kafka down does not take Order Service down.** Orders are accepted and confirmed exactly as before; events
  accumulate in the outbox and drain when Kafka returns. Producer waits are bounded (`max.block.ms`,
  `request.timeout.ms`, `delivery.timeout.ms`), so a dead broker costs seconds, never a stuck thread.

## 6. Delivery semantics and ordering assumptions

- **At-least-once delivery, idempotent processing.** Kafka may deliver the same event more than once (a
  publisher retry, a consumer that crashed before committing its offset, a rebalance). Each consumer must treat
  `eventId` as the deduplication key.
- **Ordering is per order, not global.** Events of one order arrive in order (same key, same partition).
  There is no ordering guarantee between different orders.
- Consumers must not assume an event is the first they see for an order (a consumer can start later) and must
  tolerate event types they do not know.

## 7. The Notification Service (consumer)

Port `8084`, database `mercury_notification` (Flyway `V1__initial_notification_schema.sql`).

- **What it does:** `OrderConfirmed` → `ORDER_CONFIRMED` notification, `OrderCancelled` → `ORDER_CANCELLED`
  (with the reason). `OrderCreated` and unknown types are valid but need no notification (counted as ignored).
  There is no email provider yet; a notification row with status `RECORDED` is what would be sent.
- **Idempotency:** `notification.event_id` is `UNIQUE`. The handler checks it, inserts, and also treats a unique
  violation as "already done", so even two simultaneous deliveries create one row.
- **Offsets** are committed per record, after processing (`ack-mode=record`, auto-commit off); a consumer that
  starts after events were published still reads them (`auto-offset-reset=earliest`).
- **Retries and dead-lettering.** A failing record is retried in place, up to `NOTIFICATION_RETRY_MAX_ATTEMPTS`
  attempts in total (default 4) with exponential backoff (500 ms, ×2, capped at 5 s). After that, or at once
  for a message that can never succeed (unparseable, or missing `eventId`/`orderId`/`eventType`), the record is
  published to `mercury.order.events.dlq` and the consumer moves on, so one poison message never blocks a
  partition.
- **Read API:** `GET /api/v1/notifications/orders/{orderId}`.
- The service starts and answers its API with Kafka down, and consumes once the broker is back.

There is no automatic replay of the dead-letter topic; inspecting and replaying it is an operational task.

## 8. Failure scenarios

| Scenario | What happens |
|---|---|
| Order state committed, process crashes before publishing | the row is in `order_outbox`; the restarted publisher sends it |
| Kafka accepted the event, crash before `published_at` | the event is sent again; the consumer ignores the duplicate |
| Kafka unavailable | API keeps working; events accumulate, `events.publish.failed` rises, they drain on recovery |
| Same event delivered twice | one notification (unique `event_id`), `events.duplicate` counts the second |
| Consumer crashes after writing the notification, before committing the offset | the event is redelivered; recognised as a duplicate; no second effect |
| Transient consumer failure | retried with backoff, then succeeds; nothing dead-lettered |
| Permanent consumer failure | bounded retries, then the dead-letter topic |
| Malformed message | straight to the dead-letter topic, no retries |
| Saga failure, compensation retried by recovery | the `OrderCancelled` event is written only when the order is actually cancelled |
| 50 concurrent orders on limited stock | no overselling (Phase 8 guarantees hold); each finished order produces exactly one notification |

## 9. Observability

**Order Service**: metrics `events.published`, `events.publish.failed`, `outbox.pending` (gauge),
`outbox.oldest.age.seconds` (gauge); read-only `GET /actuator/outbox` with the pending count, published count,
oldest pending age, highest attempt count and up to 50 waiting events (event id, type, order id, attempts, age,
next attempt, last error). Payloads are never exposed. `/actuator/sagas` (Phase 8) is unchanged.

**Notification Service**: metrics `events.consumed`, `events.duplicate`, `events.failed` (each failed attempt),
`events.dlq`, `events.ignored`.

Logs are `key=value` and never contain the Idempotency-Key:
`outbox eventId=… type=… orderId=… result=PUBLISHED|PUBLISH_FAILED` and
`event eventId=… type=… orderId=… result=RECORDED|DUPLICATE|IGNORED`.
Do not expose actuator endpoints on a public interface (there is no authentication yet).

## 10. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | broker address (both services) |
| `ORDER_OUTBOX_ENABLED` | `true` | run the publisher |
| `ORDER_OUTBOX_TOPIC` | `mercury.order.events` | topic published to |
| `ORDER_OUTBOX_INTERVAL` / `…_BATCH_SIZE` | `1s` / `50` | publisher pass pause / events per pass |
| `ORDER_OUTBOX_LEASE` | `30s` | how long a claimed event is reserved |
| `ORDER_OUTBOX_INITIAL_BACKOFF` / `…_MAX_BACKOFF` / `…_BACKOFF_MULTIPLIER` | `1s` / `1m` / `2.0` | retry backoff |
| `ORDER_OUTBOX_SEND_TIMEOUT` | `10s` | wait for one acknowledgement |
| `KAFKA_PRODUCER_MAX_BLOCK_MS` / `…_REQUEST_TIMEOUT_MS` / `…_DELIVERY_TIMEOUT_MS` | `5000` / `5000` / `10000` | producer waits |
| `NOTIFICATION_DB_URL` | `jdbc:postgresql://localhost:5432/mercury_notification` | database |
| `NOTIFICATION_TOPIC` / `NOTIFICATION_DLQ_TOPIC` | as above | consumed / dead-letter topics |
| `NOTIFICATION_CONSUMER_GROUP` | `notification-service` | consumer group |
| `NOTIFICATION_RETRY_MAX_ATTEMPTS` | `4` | attempts before dead-lettering |
| `NOTIFICATION_RETRY_INITIAL_INTERVAL` / `…_MULTIPLIER` / `…_MAX_INTERVAL` | `500ms` / `2.0` / `5s` | consumer retry backoff |
| `SERVER_PORT` | `8084` | Notification port |
| `MANAGEMENT_ENDPOINTS` | order: `health,metrics,sagas,outbox`; notification: `health,metrics` | exposed actuator endpoints |

## 11. Local development

Ports: Order `8083`, Notification `8084`, Product `8081`, Inventory `8082`, PostgreSQL `5432`, Kafka `9092`.

```bash
docker compose up -d postgres kafka          # Kafka runs in KRaft mode (no ZooKeeper), data in the kafka_data volume
docker exec mercury-postgres psql -U mercury -d mercury -c "CREATE DATABASE mercury_notification"   # once

# from services/<name>, one terminal each (POSTGRES_PASSWORD=mercury matches docker-compose.yml):
POSTGRES_PASSWORD=mercury ./mvnw spring-boot:run     # inventory-service, product-service, order-service, notification-service
```

`docker-compose.yml` publishes the broker on `localhost:9092` for the host and `kafka:29092` for other
containers (a persistent volume, and a health check). Nothing is hardcoded in Java: every service reads
`KAFKA_BOOTSTRAP_SERVERS`.

Watch an order travel:
```bash
curl -i -X POST localhost:8083/api/v1/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' \
     -d '{"items":[{"productId":"<id>","quantity":1}]}'
curl -s localhost:8083/actuator/outbox                      # pending should drain to 0
curl -s localhost:8084/api/v1/notifications/orders/<orderId>
docker exec mercury-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
     --topic mercury.order.events --from-beginning
```

## 12. Tests

Unit/API tests need no broker. The tests that use Kafka start a throw-away broker with Testcontainers
(`apache/kafka:3.9.0`), so no manually started Kafka or PostgreSQL is needed, only Docker.

| Suite | What it proves |
|---|---|
| `OrderEventsTests` (Order) | events written with the state change, in order; payload content; no Idempotency-Key in events; atomicity (event and state roll back together); replays write nothing; 100 concurrent duplicates produce one of each event; the writer refuses to run outside a transaction |
| `OutboxPublisherTests` (Order) | publishes once, to the right topic and key; Kafka down: the event survives and backs off; per-order ordering across failures; independent orders; crash after Kafka accepted the event: sent again; racing publishers send each event once; no transaction open while sending; metrics |
| `OutboxEndpointTests` (Order) | `/actuator/outbox` shows pending events without payloads; metrics exist |
| `NotificationEventHandlerTests` | confirmed/cancelled recorded; created/unknown ignored; duplicates and 30 simultaneous deliveries record once; invalid input rejected |
| `OrderEventConsumerKafkaTests` (Notification, real Kafka) | consumption; duplicate delivery; consumer crash after processing; transient failure retried; permanent failure bounded then dead-lettered; poison message dead-lettered without blocking the next event |
| **`EventFlowIntegrationTests`** (Order, real stack) | `POST /orders` → Order DB → outbox → Kafka → Notification → its DB; cancelled order with reason; Kafka outage (API healthy, outbox accumulates, delivery after recovery); an Order process killed before it ever published, restarted, events published; duplicate and poison messages through real Kafka; 50 concurrent orders on 20 units: no overselling, exactly one notification per finished order |

The real-stack environment (`RealServicesStack`) runs PostgreSQL, Kafka, and the real Product, Inventory and
Notification services as separate processes, with Order Service in the test JVM (or as its own process
where a test must kill it). `KafkaTestBroker` is used instead of Testcontainers' built-in `KafkaContainer`
because the latter advertises `0.0.0.0` on this Docker setup and Kafka refuses to start; it reuses the
listener layout of `docker-compose.yml`. A Kafka outage is simulated by pausing the container (the address and
data are kept).

## 13. Known limitations

- Reservation is synchronous by default. The asynchronous alternative (Phase 10, `order.reservation.mode=ASYNC`)
  is described in [async-reservation.md](async-reservation.md).
- Published outbox rows are kept forever; there is no cleanup or archiving.
- The dead-letter topic has no replay tooling.
- Notifications are only recorded; there is no email/SMS provider.
- Event payloads have no explicit schema version field yet.
- Ordering is per order only. Topic partitions are fixed at creation.
- Kafka has no authentication or TLS in this setup, and neither do the actuator endpoints.
- A Kafka outage is only visible to operators (`/actuator/outbox`, metrics); clients see nothing.
- Order events are written for new orders only; orders that existed before this phase have no `OrderCreated`
  event (their later `OrderConfirmed`/`OrderCancelled` events would still be published).
