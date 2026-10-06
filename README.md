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
- [Local development](docs/local-development.md): run the services, run the tests
