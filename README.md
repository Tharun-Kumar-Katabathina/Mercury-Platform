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
- [Saga reliability and recovery](docs/saga-recovery.md): durable saga state, recovery worker, circuit breaker, failure scenarios
- [Local development](docs/local-development.md): run the services, run the tests
