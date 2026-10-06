# Mercury Platform

Microservices e-commerce platform.

## Layout

- `services/` – user, product, order, inventory, recommendation, notification services
- `gateway/` – API gateway
- `infrastructure/` – docker, terraform, kubernetes
- `messaging/` – event/message broker config
- `load-tests/` – performance tests
- `docs/` – documentation
- `scripts/` – helper scripts

## Documentation

- [Architecture](docs/architecture.md): services, ports, ownership
- [Product to Inventory reservation flow](docs/product-inventory-flow.md): API, idempotency, errors, concurrency
- [Order Service](docs/order-service.md): order creation saga, idempotency, compensation
- [Event-driven architecture](docs/event-driven-architecture.md): Kafka, transactional outbox, idempotent consumer, dead-lettering
- [Asynchronous inventory reservation](docs/async-reservation.md): Phase 10, ASYNC order flow over Kafka, deadline recovery, late reservations
- [Saga reliability and recovery](docs/saga-recovery.md): durable saga state, recovery worker, circuit breaker, failure scenarios
- [Production infrastructure](docs/infrastructure.md): Docker images, production-like Compose stack, Kubernetes manifests, health and graceful shutdown
- [Observability](docs/observability.md): Prometheus metrics, Grafana dashboards, distributed traces across REST and Kafka, log correlation
- [Local development](docs/local-development.md): run the services, run the tests
