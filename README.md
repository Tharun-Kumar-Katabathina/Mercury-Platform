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
- [Security](docs/security.md): JWT, roles, gateway, secrets, scanning, and what is not covered
- [Resilience](docs/resilience.md) and the [failure matrix](docs/failure-matrix.md): every failure injected, what happened, what was checked
- [Performance](docs/performance.md): load tests from 100 to 10,000 concurrent users, baseline vs optimized
- [Recommendations](docs/recommendations.md): purchase features, vector search, personalised results
- [CI/CD](docs/ci-cd.md): the pipeline and its gates
- [Local development](docs/local-development.md): run the services, run the tests
