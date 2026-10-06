# Mercury Architecture (current state)

This document describes what is **implemented today**. Planned pieces (Order, Payment,
Kafka, Saga, Redis, Resilience4j) are not built and are not described here.

## Services and ports

| Component | Port | Owns | Notes |
|---|---|---|---|
| Product Service | 8081 | Products (`products` table, database `mercury_product`) | Spring Boot 4.0.8, Java 21 |
| Inventory Service | 8082 | Stock and idempotency records (`inventory`, `idempotency_records`, database `mercury_inventory`) | Spring Boot 4.0.8, Java 21 |
| Order Service | 8083 | Orders, order items, order idempotency records (`orders`, `order_items`, `order_idempotency_records`, database `mercury_order`) | Spring Boot 4.0.8, Java 21; see [Order Service](order-service.md) |
| PostgreSQL | 5432 | One database per service, in one server | `postgres:17` via `docker-compose.yml` |

Every service exposes Spring Actuator health at `/actuator/health`.

## Request flow

```
Client
  |
  |  POST /api/v1/products/{id}/reserve   (Idempotency-Key header)
  v
Product Service :8081 ------------------> PostgreSQL / mercury_product
  |   1. Idempotency-Key present?
  |   2. Does the product exist?
  |
  |  HTTP (RestClient)
  |  POST /api/v1/inventory/{id}/reserve   (same Idempotency-Key)
  v
Inventory Service :8082 ----------------> PostgreSQL / mercury_inventory
      3. replay? stock check? reserve?
```

## Service ownership

| Concern | Owner |
|---|---|
| Product data (name, SKU, price, ...) | Product Service |
| Stock (`availableQuantity`, `reservedQuantity`), `@Version`, idempotency records | Inventory Service |

Rules that hold in the code today:

- **Product Service never connects to the Inventory database.** It only calls Inventory
  Service over HTTP, through one class: `InventoryClient`.
- **Product Service never creates inventory.** A product without an inventory record gets
  Inventory's own `404 INVENTORY_NOT_FOUND`. Inventory records are created through
  `POST /api/v1/inventory` on Inventory Service.
- **Each service has its own database** (`mercury_product`, `mercury_inventory`) inside the
  same PostgreSQL server.
- **Product DTOs are separate from Inventory DTOs.** Product Service has its own
  `ReserveProductRequest` (public contract) and its own view of Inventory's responses
  (`InventoryReservationResponse`, `InventoryResponse`); unknown JSON fields are ignored.

### Schema management differs between the services

| Service | Schema | `ddl-auto` |
|---|---|---|
| Inventory | Flyway migrations `V1__initial_schema.sql`, `V2__add_idempotency_records.sql` | `validate` |
| Product | Hibernate creates/updates tables itself (no Flyway yet) | `${DDL_AUTO:update}` |

## Why HTTP (for now)

Services call each other with plain synchronous REST: easy to reason about and test. The Order
Service coordinates Product and Inventory as an orchestrated saga with compensating calls (see
[Order Service](order-service.md)); there is no messaging (Kafka) yet,
client timeouts, and (in Order Service) a circuit breaker, a bulkhead and a durable recovery worker.

## Related documents

- [Product to Inventory reservation flow](product-inventory-flow.md): API, idempotency,
  error propagation, concurrency.
- [Order Service](order-service.md): order creation saga, idempotency, compensation.
- [Saga reliability and recovery](saga-recovery.md): durable saga state, recovery worker, circuit breaker, failure scenarios.
- [Local development](local-development.md): starting everything, environment variables,
  running the tests, caveats.
