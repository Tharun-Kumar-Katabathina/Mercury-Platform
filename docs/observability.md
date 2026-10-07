# Observability (Phase 12)

Mercury is observable the way a real distributed system has to be: numbers (Prometheus + Grafana), the path of
one request across services and Kafka (distributed traces in Jaeger), and logs that carry the trace id.

```
 services ──/actuator/prometheus──> Prometheus ──> Grafana (7 dashboards)
    │                                  ▲
    │ OTLP traces                      ├── kafka-exporter  (consumer lag, topics, DLQ depth)
    ▼                                  ├── postgres-exporter (connections, transactions, locks)
  Jaeger  <── Grafana (datasource)     └── cAdvisor (containers)
    ▲
    └── trace context travels in REST headers and in Kafka message headers (W3C traceparent)
```

## 1. Running it

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml --profile platform up -d --build
```

| URL | What |
|---|---|
| http://localhost:3000 | Grafana, folder **Mercury** (`admin` / `$GRAFANA_ADMIN_PASSWORD`, default `admin`, local only) |
| http://localhost:9090 | Prometheus |
| http://localhost:16686 | Jaeger (traces) |

The overlay also turns trace export on and samples every request. In production set
`TRACING_SAMPLING_PROBABILITY` (default `0.1`) and point `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` at your collector.
Without the overlay, services still create traces (so log lines carry ids) but export nothing
(`TRACING_EXPORT_ENABLED=false`).

## 2. Metrics

Every service exposes `/actuator/prometheus`; every series is tagged `application`. Scraped every 5 s.

**Application metrics**

| Metric (Prometheus name) | Meaning |
|---|---|
| `orders_created_total`, `orders_confirmed_total`, `orders_cancelled_total` | order outcomes. The app counter is `orders.created`; the Prometheus client reserves the `_created` suffix, so Prometheus relabels `orders_total` back to `orders_created_total` |
| `inventory_reservations_total{path,result}` | reservations: `path` = `rest`/`async`, `result` = `reserved`/`replayed`; counted once per decision, after commit, never per optimistic-lock retry |
| `inventory_rejections_total{path,reason}` | `INSUFFICIENT_STOCK`, `INVENTORY_NOT_FOUND` |
| `inventory_oversell_attempts_total{path}` | requests for more stock than existed. Refused; nothing was oversold. A rising number is demand, a non-zero `reserved + available` drift would be a bug |
| `orders_recovery_pending`, `orders_recovery_exhausted`, `orders_recovery_lag_seconds` | sagas due for recovery, sagas that gave up, and how long the longest-waiting due saga has waited (0 = none) |
| `orders_saga_count{state}` | sagas per state (incl. `AWAITING_INVENTORY`, `RECOVERY_FAILED`) |
| `saga_compensation_success_total` / `_failure_total`, `orders_recovery_success_total` / `_failed_total` | compensation and recovery work |
| `order_inventory_events_*`, `order_inventory_late_reservations_total`, `inventory_commands_*` | the asynchronous reservation edge: consumed, duplicate, failed, dead-lettered, late |
| `events_published_total`, `events_publish_failed_total`, `outbox_pending` | the transactional outbox (both Order and Inventory) |
| `http_server_requests_seconds_*` | rate, latency histogram (buckets from 25 ms to 5 s), errors, by `uri`, `method`, `status` |

**Added in later phases:** `product_cache_total{result=hit|miss|error}` (Redis read-through cache), `recommendation_cache_total`, `recommendation_events_consumed_total` / `_failed_total`,
`recommendation_index_synced_total` / `_failed_total` (vectors pushed to Qdrant), `redis_*` (Redis exporter), `tomcat_threads_busy_threads`.

**Alerts:** 18 rules in `infrastructure/observability/prometheus/alerts.yml` (service down or flapping, 5xx rate, slow requests, orders stuck in recovery, recovery gave up,
compensation failing, cancellation spike, Kafka lag, dead letters, outbox backlog, PostgreSQL down, pool saturation, deadlocks, cache errors, heap) each with a runbook entry in
[operations.md](operations.md). They are evaluated by Prometheus (visible under Alerts); routing them to a pager or chat needs an Alertmanager, which is not configured here.

**Infrastructure metrics:** JVM memory, GC pauses, threads, process CPU, Tomcat threads, HikariCP pools
(`hikaricp_*`), Kafka (`kafka_consumergroup_lag`, topic offsets, brokers), PostgreSQL (`pg_*`), containers
(`container_*`). Redis panels exist and fill in when Redis is added.

## 3. Dashboards (`infrastructure/observability/grafana/dashboards`, provisioned as code)

| Dashboard | Answers |
|---|---|
| **Mercury Overview** | are all services up, how many orders in the last 15 min, success ratio, request rate/latency/errors per service, recovery lag, Kafka lag |
| **Order Service** | POST /orders p50/p95/p99, status codes, order outcomes, sagas by state, compensation, async replies, outbox, JVM, DB pool |
| **Inventory** | reservations by path/result, rejections, oversell attempts, command and reply flow, latency |
| **Kafka** | consumer lag, throughput by topic, dead-letter topic depth, outbox backlog |
| **Database** | connections, transactions/s, rows written, size, deadlocks, pool saturation and time-to-connection per service |
| **Infrastructure** | Docker CPU/memory/network, per-container series, JVM heap/GC/threads/CPU, Tomcat threads |
| **Saga and Recovery** | sagas by state, due/exhausted/lag, recovery and compensation outcomes, late reservations, duplicates, Inventory timeouts |

## 4. Traces

- **REST:** `RestClient` calls (Order → Product, Order → Inventory, Product → Inventory) are observed and carry
  `traceparent` automatically.
- **Kafka:** producing and consuming are observed (`spring.kafka.*.observation-enabled`), so each message carries
  the context to its consumer.
- **The outbox hop.** Events are published long after the request that wrote them, on another thread, so the
  request's trace would normally end at the database. Instead, the outbox row stores the writing request's W3C
  trace context (`trace_parent`, Flyway Order `V6` / Inventory `V5`), and the publisher sends inside a span whose
  parent is that context. The result is a single trace from `POST /orders` through Kafka to Inventory and back and
  on to Notification. Rows without a context are published untraced.

## 5. Logs

Every log line carries `[traceId-spanId]`. `docker logs <service> | grep <traceId>` follows one order through every
service that logs it. (Product and Inventory log little per request by design; their work is visible in the trace.)

## 6. The checkpoint: `scripts/observability-check.sh`

Starts the platform with the overlay and proves, with assertions:

1. every scrape target is up (the seven services, Kafka, PostgreSQL, Redis, the containers and Prometheus itself);
2. a **synchronous** order produces one trace spanning Order, Product and Inventory;
3. `orders.created`, `inventory.reservations` and the HTTP request counter moved;
4. an **asynchronous** order produces one trace spanning Order, Inventory, Notification and Product across Kafka and the outbox;
5. the same trace id is in the logs of Order, Inventory and Notification;
6. the asynchronous-path counters moved (`inventory.reservations{path=async}`, `order.inventory.events.consumed`);
7. Grafana has the 7 dashboards, **every one of their queries runs through Grafana's datasource without error**, and the
   overview's "orders created (15m)" reflects the orders just placed.

## 7. Known limitations

- Container names are not available from cAdvisor on Docker Desktop's VM (it works on a Linux host and in
  Kubernetes); the Infrastructure dashboard falls back to the Docker-wide totals and short container ids there.
  Per-service CPU and memory come from each service's own JVM metrics.
- Traces are kept in Jaeger's memory (lost on restart) and sampled at 100 % in the overlay: demo settings.
- No logs backend (Loki) yet: logs are correlated by trace id but searched with `docker logs` / `kubectl logs`.
- Alert rules exist and are evaluated by Prometheus, but nothing delivers them (no Alertmanager, pager or chat integration).
- Grafana's default password `admin` is for local use (bound to 127.0.0.1); Phase 15 removes default credentials.
- Dashboards are regenerated from a script during development but the JSON files are the source of truth.
