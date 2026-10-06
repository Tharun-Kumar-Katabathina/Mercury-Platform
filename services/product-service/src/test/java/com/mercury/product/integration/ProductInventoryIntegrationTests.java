package com.mercury.product.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Product -> Inventory -> PostgreSQL flow, with no mocks:
 *
 *   test --HTTP--> Product Service (in this JVM) --HTTP--> Inventory Service (own process)
 *                         |                                        |
 *                         +------------- PostgreSQL (Testcontainers) +
 *
 * Complements ProductReservationApiTests (mocked InventoryClient); it does not replace it.
 * Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductInventoryIntegrationTests {

    @DynamicPropertySource
    static void realInfrastructure(DynamicPropertyRegistry registry) {
        RealInventoryStack stack = RealInventoryStack.start();
        registry.add("spring.datasource.url", stack::productJdbcUrl);
        registry.add("spring.datasource.username", stack::dbUsername);
        registry.add("spring.datasource.password", stack::dbPassword);
        registry.add("inventory.service.url", stack::inventoryBaseUrl);
    }

    @AfterAll
    static void stopInventoryService() {
        RealInventoryStack.stopInventoryService();
    }

    @Value("${local.server.port}")
    private int productPort;

    @Autowired
    private JsonMapper jsonMapper;

    /** products created by the running test; removed again in {@link #cleanUp()} */
    private final List<UUID> createdProducts = new ArrayList<>();

    private record ApiResponse(int status, String replayedHeader, JsonNode body) {
    }

    private record StockRow(int available, int reserved, long version) {
    }

    // ---- scenarios -----------------------------------------------------------------------

    @Test
    void reservationFlowsThroughRealInventoryServiceIntoPostgres() throws Exception {
        UUID productId = newProductWithInventory(10);

        ApiResponse response = reserve(productId, "reserve-" + UUID.randomUUID(), 3);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.replayedHeader()).isNull();
        assertThat(response.body().path("productId").asString()).isEqualTo(productId.toString());
        assertThat(response.body().path("quantityReserved").asInt()).isEqualTo(3);
        assertThat(response.body().path("availableQuantity").asInt()).isEqualTo(7);
        assertThat(response.body().path("reservedQuantity").asInt()).isEqualTo(3);

        // the source of truth is PostgreSQL, not the API response
        assertThat(stock(productId)).isEqualTo(new StockRow(7, 3, 1));
        assertThat(idempotencyRecords(productId)).isEqualTo(1);
        assertThat(productRowExists(productId)).isTrue();
    }

    @Test
    void replayedRequestDoesNotDeductStockTwice() throws Exception {
        UUID productId = newProductWithInventory(10);
        String key = "reserve-001-" + UUID.randomUUID();

        ApiResponse first = reserve(productId, key, 2);
        ApiResponse second = reserve(productId, key, 2);
        ApiResponse third = reserve(productId, key, 2);

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.replayedHeader()).isNull();
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.replayedHeader()).isEqualTo("true");
        assertThat(third.replayedHeader()).isEqualTo("true");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(third.body()).isEqualTo(first.body());

        // 10 -> 8, never 10 -> 6 -> 4: one deduction, one version bump, one record
        assertThat(stock(productId)).isEqualTo(new StockRow(8, 2, 1));
        assertThat(idempotencyRecords(productId)).isEqualTo(1);
    }

    @Test
    void insufficientStockReturns409AndChangesNothing() throws Exception {
        UUID productId = newProductWithInventory(2);

        ApiResponse response = reserve(productId, "reserve-" + UUID.randomUUID(), 5);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().path("error").asString()).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(stock(productId)).isEqualTo(new StockRow(2, 0, 0));
        assertThat(idempotencyRecords(productId)).isZero();   // failures are not stored
    }

    @Test
    void sameKeyWithDifferentQuantityReturns422AndChangesNothingMore() throws Exception {
        UUID productId = newProductWithInventory(10);
        String key = "reserve-001-" + UUID.randomUUID();
        assertThat(reserve(productId, key, 2).status()).isEqualTo(200);

        ApiResponse mismatch = reserve(productId, key, 5);

        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.body().path("error").asString()).isEqualTo("IDEMPOTENCY_KEY_MISMATCH");
        assertThat(stock(productId)).isEqualTo(new StockRow(8, 2, 1));
        assertThat(idempotencyRecords(productId)).isEqualTo(1);
    }

    @Test
    void productWithoutInventoryReturnsInventoryNotFound() throws Exception {
        UUID productId = newProduct();   // exists in Product Service, never given any stock

        ApiResponse response = reserve(productId, "reserve-" + UUID.randomUUID(), 1);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().path("error").asString()).isEqualTo("INVENTORY_NOT_FOUND");
        assertThat(inventoryRows(productId)).isZero();        // Product never creates inventory
    }

    @Test
    void unknownProductReturns404WithoutTouchingInventory() throws Exception {
        UUID unknown = UUID.randomUUID();
        String key = "reserve-" + UUID.randomUUID();

        ApiResponse response = reserve(unknown, key, 1);

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().path("error").asString()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(inventoryRows(unknown)).isZero();
        assertThat(idempotencyRecords(unknown)).isZero();
    }

    // ---- test data: unique per test, removed afterwards ------------------------------------

    @AfterEach
    void cleanUp() throws Exception {
        RealInventoryStack stack = RealInventoryStack.start();
        for (UUID productId : createdProducts) {
            execute(stack.openInventoryDb(), "DELETE FROM idempotency_records WHERE product_id = ?", productId);
            execute(stack.openInventoryDb(), "DELETE FROM inventory WHERE product_id = ?", productId);
            execute(stack.openProductDb(), "DELETE FROM products WHERE id = ?", productId);
        }
        createdProducts.clear();
    }

    private UUID newProduct() {
        ApiResponse created = call(RestClient.create("http://localhost:" + productPort),
                "/api/v1/products", null, """
                        {"name":"MacBook Pro 16","sku":"MBP-%s","price":2499.99,"quantity":10}
                        """.formatted(UUID.randomUUID()));
        assertThat(created.status()).isEqualTo(201);
        UUID productId = UUID.fromString(created.body().path("id").asString());
        createdProducts.add(productId);
        return productId;
    }

    /** Product Service does not create inventory, so seed it through Inventory's own API. */
    private UUID newProductWithInventory(int availableQuantity) {
        UUID productId = newProduct();
        ApiResponse inventory = call(
                RestClient.create(RealInventoryStack.start().inventoryBaseUrl()),
                "/api/v1/inventory", null,
                "{\"productId\":\"%s\",\"availableQuantity\":%d}".formatted(productId, availableQuantity));
        assertThat(inventory.status()).isEqualTo(201);
        return productId;
    }

    private ApiResponse reserve(UUID productId, String idempotencyKey, int quantity) {
        return call(RestClient.create("http://localhost:" + productPort),
                "/api/v1/products/" + productId + "/reserve", idempotencyKey,
                "{\"quantity\":" + quantity + "}");
    }

    private ApiResponse call(RestClient client, String path, String idempotencyKey, String json) {
        RestClient.RequestBodySpec request = client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.body(json)
                .exchange((req, res) -> {
                    String body = new String(res.getBody().readAllBytes());
                    HttpStatusCode status = res.getStatusCode();
                    return new ApiResponse(
                            status.value(),
                            res.getHeaders().getFirst("Idempotent-Replayed"),
                            jsonMapper.readTree(body.isBlank() ? "{}" : body));
                });
    }

    // ---- direct PostgreSQL checks ----------------------------------------------------------

    private StockRow stock(UUID productId) throws SQLException {
        try (Connection c = RealInventoryStack.start().openInventoryDb();
             PreparedStatement s = c.prepareStatement(
                     "SELECT available_quantity, reserved_quantity, version FROM inventory WHERE product_id = ?")) {
            s.setObject(1, productId);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).as("inventory row for %s", productId).isTrue();
                return new StockRow(rs.getInt(1), rs.getInt(2), rs.getLong(3));
            }
        }
    }

    private int inventoryRows(UUID productId) throws SQLException {
        return count(RealInventoryStack.start().openInventoryDb(),
                "SELECT count(*) FROM inventory WHERE product_id = ?", productId);
    }

    private int idempotencyRecords(UUID productId) throws SQLException {
        return count(RealInventoryStack.start().openInventoryDb(),
                "SELECT count(*) FROM idempotency_records WHERE product_id = ?", productId);
    }

    private boolean productRowExists(UUID productId) throws SQLException {
        return count(RealInventoryStack.start().openProductDb(),
                "SELECT count(*) FROM products WHERE id = ?", productId) == 1;
    }

    private static int count(Connection connection, String sql, UUID id) throws SQLException {
        try (connection; PreparedStatement s = connection.prepareStatement(sql)) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, UUID id) throws SQLException {
        try (connection; PreparedStatement s = connection.prepareStatement(sql)) {
            s.setObject(1, id);
            s.executeUpdate();
        }
    }
}
