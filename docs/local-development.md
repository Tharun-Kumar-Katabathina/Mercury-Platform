# Local development: Product and Inventory services

> The Order Service (port 8083) has its own section in [order-service.md](order-service.md#12-local-development).

How to run the Product and Inventory services locally and how to run their tests.
Requirements: Java 21, Docker. Maven is not needed; each service ships its own `./mvnw`.

## Ports

| Component | Port |
|---|---|
| Product Service | 8081 |
| Inventory Service | 8082 |
| PostgreSQL | 5432 |

## Environment variables

Defaults are in each service's `src/main/resources/application.properties`.

### Product Service

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8081` | HTTP port |
| `PRODUCT_DB_URL` | `jdbc:postgresql://localhost:5432/mercury_product` | Database URL |
| `POSTGRES_USER` | `mercury` | Database user |
| `POSTGRES_PASSWORD` | `change-me` | Database password |
| `DDL_AUTO` | `update` | Hibernate schema mode (Product has no Flyway yet) |
| `INVENTORY_SERVICE_URL` | `http://localhost:8082` | Base URL of Inventory Service |

### Inventory Service

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8082` | HTTP port |
| `INVENTORY_DB_URL` | `jdbc:postgresql://localhost:5432/mercury_inventory` | Database URL |
| `POSTGRES_USER` | `mercury` | Database user |
| `POSTGRES_PASSWORD` | `change-me` | Database password |

Inventory's schema is managed by Flyway and `ddl-auto` is fixed to `validate`. The reserve
retry limit is the property `inventory.reserve.max-attempts` (default `5`).

> **Password mismatch to be aware of:** `docker-compose.yml` creates the database user
> `mercury` with the password **`mercury`**, but both services default to **`change-me`**
> (the placeholder in `.env.example`). When running against the compose Postgres, set
> `POSTGRES_PASSWORD=mercury` (as in the commands below).

## 1. Start PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

This starts `mercury-postgres` (`postgres:17`) with user `mercury`, password `mercury`, and a
database named `mercury`. **Compose does not create the two service databases**; create them
once:

```bash
docker exec mercury-postgres psql -U mercury -d mercury -c "CREATE DATABASE mercury_product"
docker exec mercury-postgres psql -U mercury -d mercury -c "CREATE DATABASE mercury_inventory"
```

(If one already exists, that statement just reports an error; it is harmless.)

## 2. Start Inventory Service (terminal 1)

```bash
cd services/inventory-service
POSTGRES_PASSWORD=mercury ./mvnw spring-boot:run
```

On first start Flyway applies `V1` and `V2` to `mercury_inventory`. Check:
`curl localhost:8082/actuator/health` → `"status":"UP"`.

## 3. Start Product Service (terminal 2)

```bash
cd services/product-service
POSTGRES_PASSWORD=mercury ./mvnw spring-boot:run
```

Check: `curl localhost:8081/actuator/health` → `"status":"UP"`. Product Service creates its
`products` table itself on first start.

## 4. Try it end to end

Product Service does not create inventory, so create the product, then an inventory record
with the **same UUID**:

```bash
# 1. create a product (Product Service)
curl -s -X POST http://localhost:8081/api/v1/products \
  -H "Content-Type: application/json" \
  -d '{"name":"MacBook Pro 16","sku":"MBP-16-M4","price":2499.99,"quantity":10}'
# note the "id" in the response, then:
PRODUCT_ID=<id from above>

# 2. give it stock (Inventory Service)
curl -s -X POST http://localhost:8082/api/v1/inventory \
  -H "Content-Type: application/json" \
  -d "{\"productId\":\"$PRODUCT_ID\",\"availableQuantity\":10}"

# 3. reserve 2 through Product Service  -> available 8, reserved 2
curl -i -X POST http://localhost:8081/api/v1/products/$PRODUCT_ID/reserve \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: reserve-001" \
  -d '{"quantity":2}'

# 4. repeat the exact same request -> 200 with "Idempotent-Replayed: true", stock unchanged
curl -i -X POST http://localhost:8081/api/v1/products/$PRODUCT_ID/reserve \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: reserve-001" \
  -d '{"quantity":2}'

# 5. inspect stock
curl -s http://localhost:8082/api/v1/inventory/$PRODUCT_ID
```

Other things to try: the same key with `"quantity":5` (422), `"quantity":50` with a new key (409),
no `Idempotency-Key` header (400), a random UUID as product id (404), stopping Inventory
Service and reserving again (503).

Direct database check:

```bash
docker exec mercury-postgres psql -U mercury -d mercury_inventory \
  -c "select product_id, available_quantity, reserved_quantity, version from inventory"
```

## Running the tests

Run each command inside the service's directory (`services/product-service`,
`services/inventory-service`). No running PostgreSQL, Product or Inventory is needed for any
of them.

### Normal suites

```bash
./mvnw clean test
```

| Service | Tests | Database used |
|---|---|---|
| Inventory | 40 | In-memory H2 (PostgreSQL mode), schema from Flyway, `ddl-auto=validate` |
| Product | 27 (21 normal + 6 integration) | In-memory H2 for the 21 normal tests |

Product's suite includes the real integration test (below) and therefore needs Docker. To run
Product's tests **without** it:

```bash
./mvnw clean test -Dtest='!ProductInventoryIntegrationTests'     # 21 tests
```

### The real Product → Inventory → PostgreSQL integration test

```bash
cd services/product-service
./mvnw clean test -Dtest=ProductInventoryIntegrationTests         # 6 tests
```

What it does automatically (nothing needs to be started by hand):

1. Starts a throw-away `postgres:16-alpine` container (Testcontainers) and creates
   `mercury_product` and `mercury_inventory` in it.
2. Builds Inventory Service by running `../inventory-service/mvnw -B -q package -DskipTests`
   (every run, so a stale jar can never be tested).
3. Launches the Inventory jar as a separate process on a free port, pointed at the container,
   and waits for `/actuator/health` to be `UP` (up to 120 s).
4. Starts Product Service inside the test JVM on a random port, pointed at that Inventory process.
5. Calls Product's HTTP API and verifies the resulting rows in PostgreSQL with JDBC.
6. Deletes its test rows after each test, stops the Inventory process, and lets Testcontainers
   remove the PostgreSQL container when the JVM exits.

It takes roughly 20 seconds. Logs of the child processes are written to
`services/product-service/target/it-logs/` (`inventory-build.log`, `inventory-service.log`).

## Caveats

- **Docker is required** for the integration test. Without Docker it is skipped
  (`@Testcontainers(disabledWithoutDocker = true)`), so a Docker-less run of Product's suite
  silently executes 21 tests instead of 27. The "no Docker" path has not been exercised.
- **The test expects `../inventory-service` next to Product Service** (it resolves the
  directory relative to Product's project directory and runs its `mvnw`). Moving or renaming
  that folder breaks the test.
- **The nested build needs Maven dependencies available** (already cached in `~/.m2`, or
  network access) because the test runs `mvnw package` for Inventory Service.
- It uses its own throw-away PostgreSQL container, **not** the `mercury-postgres` from
  `docker-compose.yml`, so it does not touch your local development data and can run while
  that container is up.
- If a run is killed abruptly, check for a stray Inventory process
  (`pgrep -f 'inventory-service-.*jar'`); a JVM shutdown hook normally prevents this.
- Product's normal tests that use `InventoryClient` mock it (`ProductReservationApiTests`) or stub
  HTTP (`InventoryClientTests`); only the integration test uses the real service.
- Other limitations of the Product/Inventory integration itself (no timeouts or circuit breaker,
  no idempotency-record cleanup, no Flyway for Product, ...) are listed in
  [Product to Inventory reservation flow](product-inventory-flow.md#7-known-limitations).
