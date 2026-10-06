package com.mercury.order.integration;

import com.mercury.order.service.OrderTransactions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * The real flow, with no mocks of Product or Inventory:
 *
 *   test --HTTP--> Order Service (this JVM) --HTTP--> Product Service   (own process)
 *                        |                  \--HTTP--> Inventory Service (own process)
 *                        +---------- PostgreSQL (Testcontainers), one database per service
 *
 * Every assertion about stock and orders is read straight from PostgreSQL. Skipped automatically
 * when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderFlowIntegrationTests {

    @DynamicPropertySource
    static void realInfrastructure(DynamicPropertyRegistry registry) {
        RealServicesStack stack = RealServicesStack.start();
        registry.add("spring.datasource.url", stack::orderJdbcUrl);
        registry.add("spring.datasource.username", stack::dbUsername);
        registry.add("spring.datasource.password", stack::dbPassword);
        registry.add("product.service.url", stack::productBaseUrl);
        registry.add("inventory.service.url", stack::inventoryBaseUrl);
    }

    @AfterAll
    static void stopServices() {
        RealServicesStack.stopServices();
    }

    @Value("${local.server.port}")
    private int orderPort;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoSpyBean
    private OrderTransactions transactions;

    private final List<UUID> createdProducts = new ArrayList<>();

    private record Api(int status, String replayed, JsonNode body) {
    }

    private record Stock(int available, int reserved) {
    }

    // ---- 1. successful order ---------------------------------------------------------------

    @Test
    void successfulOrderReservesStockAndPersistsASnapshot() throws Exception {
        UUID laptop = productWithStock("MacBook Pro 16", "2499.00", 10);
        UUID mouse = productWithStock("Mouse", "19.99", 5);

        Api created = placeOrder(key(), item(laptop, 2), item(mouse, 3));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.replayed()).isNull();
        assertThat(created.body().path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(created.body().path("totalAmount").decimalValue()).isEqualByComparingTo("5057.97");
        assertThat(created.body().path("items").size()).isEqualTo(2);
        UUID orderId = UUID.fromString(created.body().path("id").asString());

        // Inventory's own database: stock moved, one reservation recorded under the order's key
        assertThat(stock(laptop)).isEqualTo(new Stock(8, 2));
        assertThat(stock(mouse)).isEqualTo(new Stock(2, 3));
        assertThat(inventoryRecords("order:" + orderId + ":product:" + laptop)).isEqualTo(1);
        assertThat(inventoryRecords("order:" + orderId + ":product:" + mouse)).isEqualTo(1);

        // Order's own database: CONFIRMED, with the name/sku/price snapshot
        assertThat(orderStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(count(RealServicesStack.ORDER_DB,
                "SELECT count(*) FROM order_items WHERE order_id = ? AND product_name = 'Mouse' "
                        + "AND unit_price = 19.99 AND quantity = 3", orderId)).isEqualTo(1);

        // and the order can be read back
        Api fetched = call("GET", orderBase(), "/api/v1/orders/" + orderId, null, null);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.body().path("status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void theOrderKeepsTheOriginalPriceWhenTheProductPriceChangesLater() throws Exception {
        UUID product = productWithStock("MacBook Pro 16", "2499.00", 10);
        UUID orderId = UUID.fromString(
                placeOrder(key(), item(product, 1)).body().path("id").asString());

        Api repriced = call("PUT", RealServicesStack.start().productBaseUrl(),
                "/api/v1/products/" + product, null,
                "{\"name\":\"MacBook Pro 16\",\"price\":2699.00,\"quantity\":10}");
        assertThat(repriced.status()).isEqualTo(200);

        Api order = call("GET", orderBase(), "/api/v1/orders/" + orderId, null, null);
        assertThat(order.body().path("items").get(0).path("unitPrice").decimalValue())
                .isEqualByComparingTo("2499.00");
        assertThat(order.body().path("totalAmount").decimalValue()).isEqualByComparingTo("2499.00");
    }

    // ---- 2. same idempotency key -----------------------------------------------------------

    @Test
    void sameIdempotencyKeyReturnsTheSameOrderAndDeductsStockOnce() throws Exception {
        UUID product = productWithStock("Laptop", "100.00", 10);
        String key = key();

        Api first = placeOrder(key, item(product, 2));
        Api second = placeOrder(key, item(product, 2));
        Api third = placeOrder(key, item(product, 2));

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.replayed()).isNull();
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.replayed()).isEqualTo("true");
        assertThat(third.replayed()).isEqualTo("true");
        assertThat(second.body()).isEqualTo(first.body());

        assertThat(stock(product)).isEqualTo(new Stock(8, 2));          // 10 -> 8, never 6 or 4
        assertThat(ordersFor(product)).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentPayloadIs422AndChangesNothing() throws Exception {
        UUID product = productWithStock("Laptop", "100.00", 10);
        String key = key();
        placeOrder(key, item(product, 2));

        Api mismatch = placeOrder(key, item(product, 5));

        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.body().path("error").asString()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(stock(product)).isEqualTo(new Stock(8, 2));
        assertThat(ordersFor(product)).isEqualTo(1);
    }

    // ---- 3. insufficient inventory (and real compensation of a half-reserved order) ---------

    @Test
    void insufficientStockIs409AndTheOrderIsCancelled() throws Exception {
        UUID product = productWithStock("Laptop", "100.00", 2);

        Api rejected = placeOrder(key(), item(product, 5));

        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.body().path("error").asString()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(stock(product)).isEqualTo(new Stock(2, 0));
        assertThat(orderStatusFor(product)).isEqualTo("CANCELLED");
        assertThat(count(RealServicesStack.ORDER_DB,
                "SELECT count(*) FROM order_idempotency_records r JOIN order_items i "
                        + "ON i.order_id = r.order_id WHERE i.product_id = ?", product)).isZero();
    }

    @Test
    void anOrderThatFailsOnItsSecondItemGivesBackTheFirstItemsStock() throws Exception {
        UUID p1 = productWithoutStock("Product one", "10.00");
        UUID p2 = productWithoutStock("Product two", "10.00");
        // Order Service reserves in productId order: the lower id first, so only the higher one
        // runs out. The first reservation therefore really happens before the failure.
        UUID reservedFirst = p1.compareTo(p2) < 0 ? p1 : p2;
        UUID shortOfStock = reservedFirst.equals(p1) ? p2 : p1;
        giveStock(reservedFirst, 10);
        giveStock(shortOfStock, 1);

        Api rejected = placeOrder(key(), item(reservedFirst, 4), item(shortOfStock, 5));

        assertThat(rejected.status()).isEqualTo(409);
        assertThat(rejected.body().path("error").asString()).isEqualTo("INSUFFICIENT_STOCK");

        // the first item WAS reserved, then given back: stock is exactly what it was
        assertThat(stock(reservedFirst)).isEqualTo(new Stock(10, 0));
        assertThat(stock(shortOfStock)).isEqualTo(new Stock(1, 0));
        assertThat(orderStatusFor(reservedFirst)).isEqualTo("CANCELLED");
        assertThat(inventoryRecordsLike("order:%:product:" + reservedFirst)).isEqualTo(1);          // reserved...
        assertThat(inventoryRecordsLike("order:%:product:" + reservedFirst + ":release")).isEqualTo(1); // ...released
        assertThat(inventoryRecordsLike("order:%:product:" + shortOfStock)).isZero();               // never reserved
    }

    // ---- 4/5. unknown product, missing inventory ---------------------------------------------

    @Test
    void unknownProductIs404AndNothingIsCreatedOrReserved() throws Exception {
        UUID real = productWithStock("Real", "10.00", 10);
        UUID unknown = UUID.randomUUID();

        Api rejected = placeOrder(key(), item(real, 1), item(unknown, 1));

        assertThat(rejected.status()).isEqualTo(404);
        assertThat(rejected.body().path("error").asString()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(stock(real)).isEqualTo(new Stock(10, 0));
        assertThat(ordersFor(real)).isZero();   // validated before anything was saved
    }

    @Test
    void productWithoutInventoryIs404InventoryNotFoundAndTheOrderIsCancelled() throws Exception {
        UUID product = productWithoutStock("No stock record", "10.00");

        Api rejected = placeOrder(key(), item(product, 1));

        assertThat(rejected.status()).isEqualTo(404);
        assertThat(rejected.body().path("error").asString()).isEqualTo("INVENTORY_NOT_FOUND");
        assertThat(orderStatusFor(product)).isEqualTo("CANCELLED");
    }

    // ---- 6. inventory unavailable -----------------------------------------------------------

    @Test
    void inventoryUnavailableIs503TheOrderIsCancelledAndOrdersWorkAgainOnceItIsBack() throws Exception {
        UUID product = productWithStock("Laptop", "100.00", 10);
        RealServicesStack stack = RealServicesStack.start();

        stack.stopInventoryService();
        try {
            Api down = placeOrder(key(), item(product, 1));
            assertThat(down.status()).isEqualTo(503);
            assertThat(down.body().path("error").asString()).isEqualTo("INVENTORY_SERVICE_UNAVAILABLE");
            assertThat(orderStatusFor(product)).isEqualTo("CANCELLED");
        } finally {
            stack.startInventoryService();
        }

        assertThat(stock(product)).isEqualTo(new Stock(10, 0));
        Api recovered = placeOrder(key(), item(product, 1));
        assertThat(recovered.status()).isEqualTo(201);
        assertThat(stock(product)).isEqualTo(new Stock(9, 1));
    }

    // ---- 7. compensation: stock reserved, then the ORDER side fails --------------------------

    @Test
    void ifTheOrderCannotBePersistedAfterReservingTheReservationIsReleased() throws Exception {
        UUID product = productWithStock("Laptop", "100.00", 10);
        String key = key();
        doThrow(new IllegalStateException("simulated order database failure"))
                .when(transactions).confirm(any(), org.mockito.ArgumentMatchers.eq(key));

        Api failed = placeOrder(key, item(product, 3));

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.body().path("error").asString()).isEqualTo("ORDER_PROCESSING_FAILED");

        // no orphaned reservation: Inventory's database is back to exactly where it started
        assertThat(stock(product)).isEqualTo(new Stock(10, 0));
        assertThat(inventoryRecordsLike("order:%:product:" + product)).isEqualTo(1);           // it was reserved...
        assertThat(inventoryRecordsLike("order:%:product:" + product + ":release")).isEqualTo(1); // ...and released
        assertThat(orderStatusFor(product)).isEqualTo("CANCELLED");
    }

    // ---- 8. concurrency ---------------------------------------------------------------------

    @Test
    void oneHundredConcurrentRequestsWithTheSameKeyCreateOneOrderAndOneReservation() throws Exception {
        UUID product = productWithStock("Laptop", "100.00", 10);
        String key = key();

        List<Api> responses = runConcurrently(100, i -> () -> placeOrder(key, item(product, 1)));

        assertThat(responses).allMatch(r -> r.status() == 201);
        assertThat(responses.stream().filter(r -> r.replayed() == null)).hasSize(1);
        assertThat(responses.stream().filter(r -> "true".equals(r.replayed()))).hasSize(99);
        assertThat(responses.stream().map(r -> r.body().path("id").asString()).distinct()).hasSize(1);

        assertThat(ordersFor(product)).isEqualTo(1);
        assertThat(stock(product)).isEqualTo(new Stock(9, 1));
        assertThat(inventoryRecordsLike("order:%:product:" + product)).isEqualTo(1);
    }

    @Test
    void manyConcurrentOrdersWithDifferentKeysNeverOversellAndLeaveNoOrphanReservations() throws Exception {
        int stock = 10;
        UUID product = productWithStock("Scarce", "10.00", stock);

        List<Api> responses = runConcurrently(30, i -> () -> placeOrder(key(), item(product, 1)));

        long confirmed = responses.stream().filter(r -> r.status() == 201).count();
        // every non-201 is a clean 409 (out of stock, or it lost the lock race): nothing else
        assertThat(responses.stream().filter(r -> r.status() != 201))
                .allMatch(r -> r.status() == 409);

        Stock result = stock(product);
        assertThat(confirmed).isLessThanOrEqualTo(stock);
        assertThat(result.available()).isGreaterThanOrEqualTo(0);
        assertThat(result.available() + result.reserved()).isEqualTo(stock);   // nothing lost or leaked
        assertThat(result.reserved()).isEqualTo((int) confirmed);              // only CONFIRMED orders hold stock
        assertThat(count(RealServicesStack.ORDER_DB,
                "SELECT count(*) FROM orders o JOIN order_items i ON i.order_id = o.id "
                        + "WHERE i.product_id = ? AND o.status = 'CONFIRMED'", product))
                .isEqualTo((int) confirmed);
        assertThat(count(RealServicesStack.ORDER_DB,
                "SELECT count(*) FROM orders o JOIN order_items i ON i.order_id = o.id "
                        + "WHERE i.product_id = ? AND o.status = 'PENDING'", product)).isZero();
    }

    // ---- test data: unique per test, removed afterwards --------------------------------------

    @AfterEach
    void cleanUp() throws Exception {
        RealServicesStack stack = RealServicesStack.start();
        for (UUID product : createdProducts) {
            List<UUID> orders = new ArrayList<>();
            try (Connection c = stack.open(RealServicesStack.ORDER_DB);
                 PreparedStatement s = c.prepareStatement(
                         "SELECT DISTINCT order_id FROM order_items WHERE product_id = ?")) {
                s.setObject(1, product);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        orders.add(rs.getObject(1, UUID.class));
                    }
                }
            }
            for (UUID order : orders) {
                update(RealServicesStack.ORDER_DB, "DELETE FROM order_idempotency_records WHERE order_id = ?", order);
                update(RealServicesStack.ORDER_DB, "DELETE FROM order_items WHERE order_id = ?", order);
                update(RealServicesStack.ORDER_DB, "DELETE FROM orders WHERE id = ?", order);
            }
            update(RealServicesStack.INVENTORY_DB, "DELETE FROM idempotency_records WHERE product_id = ?", product);
            update(RealServicesStack.INVENTORY_DB, "DELETE FROM inventory WHERE product_id = ?", product);
            update(RealServicesStack.PRODUCT_DB, "DELETE FROM products WHERE id = ?", product);
        }
        createdProducts.clear();
    }

    // ---- building blocks -------------------------------------------------------------------

    private String orderBase() {
        return "http://localhost:" + orderPort;
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private record Item(UUID productId, int quantity) {
    }

    private static Item item(UUID productId, int quantity) {
        return new Item(productId, quantity);
    }

    private UUID productWithoutStock(String name, String price) {
        Api created = call("POST", RealServicesStack.start().productBaseUrl(), "/api/v1/products", null,
                "{\"name\":\"%s\",\"sku\":\"SKU-%s\",\"price\":%s,\"quantity\":1}"
                        .formatted(name, UUID.randomUUID(), price));
        assertThat(created.status()).isEqualTo(201);
        UUID productId = UUID.fromString(created.body().path("id").asString());
        createdProducts.add(productId);
        return productId;
    }

    private UUID productWithStock(String name, String price, int availableQuantity) {
        UUID productId = productWithoutStock(name, price);
        giveStock(productId, availableQuantity);
        return productId;
    }

    private void giveStock(UUID productId, int availableQuantity) {
        Api inventory = call("POST", RealServicesStack.start().inventoryBaseUrl(), "/api/v1/inventory", null,
                "{\"productId\":\"%s\",\"availableQuantity\":%d}".formatted(productId, availableQuantity));
        assertThat(inventory.status()).isEqualTo(201);
    }

    private Api placeOrder(String idempotencyKey, Item... items) {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"productId\":\"%s\",\"quantity\":%d}"
                    .formatted(items[i].productId(), items[i].quantity()));
        }
        json.append("]}");
        return call("POST", orderBase(), "/api/v1/orders", idempotencyKey, json.toString());
    }

    private Api call(String method, String baseUrl, String path, String idempotencyKey, String json) {
        RestClient.RequestBodySpec request = RestClient.create(baseUrl)
                .method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (json != null) {
            request.body(json);
        }
        return request.exchange((req, res) -> {
            String body = new String(res.getBody().readAllBytes());
            return new Api(
                    res.getStatusCode().value(),
                    res.getHeaders().getFirst("Idempotent-Replayed"),
                    jsonMapper.readTree(body.isBlank() ? "{}" : body));
        });
    }

    private <T> List<T> runConcurrently(int tasks, IntFunction<Callable<T>> factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        CountDownLatch ready = new CountDownLatch(tasks);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            Callable<T> task = factory.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    // ---- direct PostgreSQL reads ---------------------------------------------------------------

    private Stock stock(UUID productId) throws SQLException {
        try (Connection c = RealServicesStack.start().open(RealServicesStack.INVENTORY_DB);
             PreparedStatement s = c.prepareStatement(
                     "SELECT available_quantity, reserved_quantity FROM inventory WHERE product_id = ?")) {
            s.setObject(1, productId);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).as("inventory row for %s", productId).isTrue();
                return new Stock(rs.getInt(1), rs.getInt(2));
            }
        }
    }

    private String orderStatus(UUID orderId) throws SQLException {
        return string(RealServicesStack.ORDER_DB, "SELECT status FROM orders WHERE id = ?", orderId);
    }

    /** status of the single order that contains this product */
    private String orderStatusFor(UUID productId) throws SQLException {
        assertThat(ordersFor(productId)).as("orders containing %s", productId).isEqualTo(1);
        return string(RealServicesStack.ORDER_DB,
                "SELECT o.status FROM orders o JOIN order_items i ON i.order_id = o.id "
                        + "WHERE i.product_id = ?", productId);
    }

    private int ordersFor(UUID productId) throws SQLException {
        return count(RealServicesStack.ORDER_DB,
                "SELECT count(DISTINCT order_id) FROM order_items WHERE product_id = ?", productId);
    }

    private int inventoryRecords(String exactKey) throws SQLException {
        try (Connection c = RealServicesStack.start().open(RealServicesStack.INVENTORY_DB);
             PreparedStatement s = c.prepareStatement(
                     "SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?")) {
            s.setString(1, exactKey);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** SQL LIKE over the key, where % stands for the order id */
    private int inventoryRecordsLike(String pattern) throws SQLException {
        try (Connection c = RealServicesStack.start().open(RealServicesStack.INVENTORY_DB);
             PreparedStatement s = c.prepareStatement(
                     "SELECT count(*) FROM idempotency_records WHERE idempotency_key LIKE ?")) {
            s.setString(1, pattern);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int count(String database, String sql, UUID id) throws SQLException {
        try (Connection c = RealServicesStack.start().open(database);
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String string(String database, String sql, UUID id) throws SQLException {
        try (Connection c = RealServicesStack.start().open(database);
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private void update(String database, String sql, UUID id) throws SQLException {
        try (Connection c = RealServicesStack.start().open(database);
             PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            s.executeUpdate();
        }
    }
}
