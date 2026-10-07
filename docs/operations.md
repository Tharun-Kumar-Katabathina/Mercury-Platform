# Operations runbook (Phase 19)

What to watch, what each alert means, and what to do. The alert rules are in
`infrastructure/observability/prometheus/alerts.yml` (18 rules, validated with `promtool`); the dashboards are in
[observability.md](observability.md). Everything the platform does on its own (retries, circuit breakers, recovery, the outbox) is described
in [saga-recovery.md](saga-recovery.md), [event-driven-architecture.md](event-driven-architecture.md) and [async-reservation.md](async-reservation.md);
this page is about the moments it cannot fix by itself.

## Service levels (starting objectives)

| Objective | Target | Measured by |
|---|---|---|
| Order API availability (non-5xx of `POST /orders`) | 99.5 % per month | `http_server_requests_seconds_count{uri="/api/v1/orders",method="POST"}` |
| Order API latency, synchronous mode | p95 under 1 s, p99 under 2 s | the same histogram |
| Orders reach a final state | 99.9 % within 2 minutes (30 minutes at most) | `orders_saga_count`, `orders_recovery_lag_seconds` |
| No overselling, no stranded stock | 100 % (an invariant, not an objective) | the failure matrix; `inventory_oversell_attempts_total` counts refusals |
| Catalogue reads | p95 under 100 ms (cached) | `GET product` latency |

These are objectives to measure against, not promises backed by production traffic: the platform has not served real users.

## First look (any incident)

1. **Grafana → Mercury Overview**: which service, which signal (errors, latency, lag, recovery)?
2. **Prometheus → Alerts**: what is firing, since when?
3. **Jaeger**: find a failing request's trace id in the logs (`[traceId-spanId]` in every line) and open it: which hop is slow or failing?
4. `docker compose -f docker-compose.yml --profile platform ps` / `kubectl -n mercury get pods`: is anything restarting?
5. `GET /actuator/sagas` (Order, admin or service token): unfinished orders and their states; `GET /actuator/outbox`: unpublished events.

## Alerts

### Service down
`ServiceDown`, `ServiceFlapping`. A container that restarts repeatedly is usually out of memory (check `docker inspect ... .State.OOMKilled`
or the pod's last state) or cannot reach its database (readiness fails first). Raise the memory limit or fix the dependency. The platform
degrades predictably meanwhile: Order stays up with Notification or Recommendation down; with Product down orders are refused `503` without being saved;
with Inventory down synchronous orders fail fast (circuit breaker) and asynchronous orders wait in Kafka; with the user-service down logged-in
customers keep working (tokens are verified locally) but nobody can log in. See the [failure matrix](failure-matrix.md).

### High error rate
`HighErrorRate`. Check which `uri`/`status` on the Order or Inventory dashboard. `503 DATABASE_UNAVAILABLE` means PostgreSQL; `503
INVENTORY_SERVICE_UNAVAILABLE` / `PRODUCT_SERVICE_UNAVAILABLE` means the circuit is open or the service is down; `409 ORDER_CONFLICT` means duplicate
requests racing. Errors from rate limiting are `429`, not `5xx`.

### Slow requests
`SlowRequests`. Compare Order p95 with Inventory and Product: the slow hop is the cause. Check the connection pool (below) and the
database dashboard (locks, deadlocks). If only the first requests after a restart are slow, that is JIT warm-up.

### Orders stuck
`OrdersStuckInRecovery`, `CompensationFailing`. Orders are due for recovery but the worker is not finishing them. Check `/actuator/sagas`; the
worker log (`recovery orderId=...`) says what it is retrying. Usual causes: Inventory unreachable (compensation cannot release stock, retried with
backoff) or Order's recovery worker down. Nothing is lost: every unfinished order is in PostgreSQL and is retried until `order.recovery.max-attempts`.

### Recovery failed
`RecoveryGaveUp`. An order exhausted its attempts and is `RECOVERY_FAILED`. There is deliberately no automatic path left. Read its `last_error`
(`/actuator/sagas`), fix the cause (usually a dependency that was down longer than the retries), then make it due again
(`UPDATE order_saga SET state='COMPENSATING', next_attempt_at=now(), attempt_count=0 WHERE order_id=...` for an order whose stock must be released, after
checking Inventory's view: `GET /api/v1/inventory/{product}/reservations/{key}`), and the worker finishes it. Never edit stock by hand.

### Cancellation spike
`OrderCancellationsSpike`. Look at the cancel reasons in the `OrderCancelled` events (`INSUFFICIENT_STOCK`, `INVENTORY_UNAVAILABLE`,
`RESERVATION_TIMEOUT`): out-of-stock is business, the others are dependency failures.

### Late reservations
`LateReservationsReleased`. In asynchronous mode Inventory reserved after the order's waiting deadline had passed and the order was cancelled; the stock was
given back automatically. Frequent occurrences mean the deadline (`ORDER_RESERVATION_ASYNC_DEADLINE`, default 60 s) is shorter than Inventory's
real response time under load.

### Kafka lag
`KafkaConsumerLag`. A consumer group is behind. Check that the consuming service is running and not failing every message (see dead letters);
scale the consumer (more instances, up to the partition count) if it is just slow.

### Dead letters
`DeadLetters`. Messages failed repeatedly or were malformed and are in a `.dlq` topic. Inspect them
(`kafka-console-consumer.sh --topic <dlq> --from-beginning`), fix the cause, and re-publish the corrected message to the original topic; consumers are
idempotent, so replaying is safe. There is no replay tool yet.

### Outbox backlog
`OutboxBacklog`. Events are waiting in an outbox table because Kafka is unreachable or the publisher is stuck. Events are never dropped; they are published
in order once Kafka returns (the publisher backs off exponentially, never giving up). Fix Kafka first.

### Postgres down
`PostgresDown`. Services report not-ready and answer `503 DATABASE_UNAVAILABLE` with `Retry-After`. They reconnect on their own when the database returns;
in-flight sagas are resumed by recovery. Restore from backup only if the data is lost (below).

### Pool saturated
`ConnectionPoolSaturated`. Threads wait for a connection. Either raise `DB_POOL_SIZE` (and check PostgreSQL's `max_connections`: replicas × pool size must stay
below it) or find the slow query/lock that holds connections.

### Deadlocks
`Deadlocks`. Rare; PostgreSQL aborts one transaction, the service retries (optimistic locking and idempotency make this safe). A sustained rate needs a look at
the two code paths involved.

### Cache errors
`CacheErrors`. Redis is failing; requests are served from the database (slower, never wrong). Restore Redis; nothing needs replaying (it is a cache).

### Oversell attempts
`OversellAttemptsRising`. Requests for more stock than exists, all refused. Not an incident by itself: an item is out of stock, or someone is hammering one product.

### Memory
`JvmHeapNearLimit`. Raise the container memory limit (the JVM heap follows it: `MaxRAMPercentage=70`) or look for a leak in the heap trend on the Infrastructure dashboard.

## Backup and restore

```bash
scripts/backup-databases.sh [dir]                    # pg_dump (custom format) of every database
scripts/restore-database.sh <dump> <database>        # into an EMPTY database; refuses to overwrite
```

- Restore into a **new** database first (`mercury_order_restored`), compare, then switch the service's `*_DB_URL` or swap names.
- The databases are independent (one per service), so they can be restored independently; after restoring the Order database, run the platform
  check and look at `/actuator/sagas`: any unfinished sagas resume by themselves. Inventory is the authority on stock: if the Order database is older than
  Inventory's, orders created in between exist only as reservations in Inventory (`order_reservations` for asynchronous orders, `idempotency_records` for
  synchronous ones), which is the information needed to reconcile them.
- Kafka and Redis are not backed up: Kafka's events are re-derivable from the outbox tables only for rows not yet published, so treat a lost Kafka volume as an
  incident for the consumers' offsets, not for the order data. Qdrant's vectors are rebuilt from PostgreSQL (mark every product `index_dirty = true`).
- **A backup you have not restored is not a backup:** the restore path is exercised in the platform check's data-survival step and by the commands above; schedule
  a periodic restore test in your environment. (Backups are not scheduled by this repository; use your platform's scheduler and encrypt them at rest.)

## Upgrades and rollback

- Services are stateless and shut down gracefully (25 s), so a rolling update loses no request: Kubernetes keeps `maxUnavailable: 0` and waits for readiness.
- Database migrations are Flyway, **additive and backward compatible** by convention (new columns nullable or defaulted, nothing renamed in place), so the previous
  version keeps working against the new schema and a rollback is just redeploying the previous image tag (`kubectl rollout undo`, which the pipeline does automatically).
- Order of a risky change: schema (additive) → write both → read new → drop old, each as its own release.
- Switching `ORDER_RESERVATION_MODE` is safe while orders are in flight: each order keeps the mode it was created with.

## Capacity (from the load tests)

See [performance.md](performance.md) for measured throughput and latency at 100 to 10,000 concurrent users and what limited each level. Rules of thumb that hold regardless:
replicas × `DB_POOL_SIZE` must stay under PostgreSQL's `max_connections`; the outbox publisher and recovery worker are cluster-safe (leased row claims) so more replicas
only add capacity; Kafka consumers scale to the partition count (3 by default).
