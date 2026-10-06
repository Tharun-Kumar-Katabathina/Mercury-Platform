package com.mercury.order.integration;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP and direct-PostgreSQL helpers shared by the real-stack failure tests. */
final class StackClient {

    record Api(int status, String replayed, JsonNode body) {
    }

    record Stock(int available, int reserved) {
    }

    private final RealServicesStack stack = RealServicesStack.start();
    private final JsonMapper jsonMapper;

    StackClient(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    // ---- HTTP -----------------------------------------------------------------------------

    Api call(String method, String baseUrl, String path, String idempotencyKey, String json) {
        RestClient.RequestBodySpec request = RestClient.create(baseUrl)
                .method(HttpMethod.valueOf(method))
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
            return new Api(res.getStatusCode().value(),
                    res.getHeaders().getFirst("Idempotent-Replayed"),
                    jsonMapper.readTree(body.isBlank() ? "{}" : body));
        });
    }

    UUID createProduct(String name, String price) {
        Api created = call("POST", stack.productBaseUrl(), "/api/v1/products", null,
                "{\"name\":\"%s\",\"sku\":\"SKU-%s\",\"price\":%s,\"quantity\":1}"
                        .formatted(name, UUID.randomUUID(), price));
        assertThat(created.status()).isEqualTo(201);
        return UUID.fromString(created.body().path("id").asString());
    }

    UUID productWithStock(String name, String price, int stock) {
        UUID productId = createProduct(name, price);
        Api inventory = call("POST", stack.inventoryBaseUrl(), "/api/v1/inventory", null,
                "{\"productId\":\"%s\",\"availableQuantity\":%d}".formatted(productId, stock));
        assertThat(inventory.status()).isEqualTo(201);
        return productId;
    }

    static String orderJson(Object... productAndQuantity) {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"productId\":\"%s\",\"quantity\":%d}"
                    .formatted(productAndQuantity[i], productAndQuantity[i + 1]));
        }
        return json.append("]}").toString();
    }

    static String key() {
        return "key-" + UUID.randomUUID();
    }

    // ---- PostgreSQL -----------------------------------------------------------------------

    Stock stock(UUID productId) throws SQLException {
        try (Connection c = stack.open(RealServicesStack.INVENTORY_DB);
             PreparedStatement s = c.prepareStatement(
                     "SELECT available_quantity, reserved_quantity FROM inventory WHERE product_id = ?")) {
            s.setObject(1, productId);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).as("inventory row for %s", productId).isTrue();
                return new Stock(rs.getInt(1), rs.getInt(2));
            }
        }
    }

    /** idempotency records Inventory holds for this product, optionally only keys ending in a suffix */
    int inventoryRecords(UUID productId, String operation) throws SQLException {
        return scalar(RealServicesStack.INVENTORY_DB,
                "SELECT count(*) FROM idempotency_records WHERE product_id = ? AND operation = '" + operation + "'",
                productId);
    }

    UUID onlyOrderFor(String orderDb, UUID productId) throws SQLException {
        try (Connection c = stack.open(orderDb);
             PreparedStatement s = c.prepareStatement(
                     "SELECT DISTINCT order_id FROM order_items WHERE product_id = ?")) {
            s.setObject(1, productId);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).as("an order for %s", productId).isTrue();
                UUID id = rs.getObject(1, UUID.class);
                assertThat(rs.next()).as("exactly one order for %s", productId).isFalse();
                return id;
            }
        }
    }

    java.util.List<UUID> orderIdsFor(String orderDb, UUID productId) throws SQLException {
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        try (Connection c = stack.open(orderDb);
             PreparedStatement s = c.prepareStatement(
                     "SELECT DISTINCT order_id FROM order_items WHERE product_id = ?")) {
            s.setObject(1, productId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        }
        return ids;
    }

    int ordersFor(String orderDb, UUID productId) throws SQLException {
        return scalar(orderDb, "SELECT count(DISTINCT order_id) FROM order_items WHERE product_id = ?", productId);
    }

    String orderStatus(String orderDb, UUID orderId) throws SQLException {
        return string(orderDb, "SELECT status FROM orders WHERE id = ?", orderId);
    }

    String sagaState(String orderDb, UUID orderId) throws SQLException {
        return string(orderDb, "SELECT state FROM order_saga WHERE order_id = ?", orderId);
    }

    int sagaAttempts(String orderDb, UUID orderId) throws SQLException {
        return scalar(orderDb, "SELECT attempt_count FROM order_saga WHERE order_id = ?", orderId);
    }

    String itemStatus(String orderDb, UUID orderId, UUID productId) throws SQLException {
        try (Connection c = stack.open(orderDb);
             PreparedStatement s = c.prepareStatement(
                     "SELECT reservation_status FROM order_items WHERE order_id = ? AND product_id = ?")) {
            s.setObject(1, orderId);
            s.setObject(2, productId);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    int claims(String orderDb, UUID orderId) throws SQLException {
        return scalar(orderDb, "SELECT count(*) FROM order_idempotency_records WHERE order_id = ?", orderId);
    }

    /** as if the saga's backoff and lease had elapsed: recovery may take it now */
    void makeDue(String orderDb, UUID orderId) throws SQLException {
        try (Connection c = stack.open(orderDb);
             PreparedStatement s = c.prepareStatement(
                     "UPDATE order_saga SET next_attempt_at = now() - interval '1 minute', "
                             + "locked_until = NULL WHERE order_id = ?")) {
            s.setObject(1, orderId);
            s.executeUpdate();
        }
    }

    void cleanUp(String orderDb, UUID... products) throws SQLException {
        for (UUID product : products) {
            for (String sql : new String[]{
                    "DELETE FROM order_idempotency_records WHERE order_id IN (SELECT order_id FROM order_items WHERE product_id = ?)",
                    "DELETE FROM order_saga WHERE order_id IN (SELECT order_id FROM order_items WHERE product_id = ?)",
                    "DELETE FROM order_items WHERE product_id = ?"}) {
                update(orderDb, sql, product);
            }
            update(orderDb, "DELETE FROM orders WHERE id NOT IN (SELECT order_id FROM order_items)", null);
            update(RealServicesStack.INVENTORY_DB, "DELETE FROM idempotency_records WHERE product_id = ?", product);
            update(RealServicesStack.INVENTORY_DB, "DELETE FROM inventory WHERE product_id = ?", product);
            update(RealServicesStack.PRODUCT_DB, "DELETE FROM products WHERE id = ?", product);
        }
    }

    private int scalar(String database, String sql, UUID id) throws SQLException {
        try (Connection c = stack.open(database); PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
    }

    private String string(String database, String sql, UUID id) throws SQLException {
        try (Connection c = stack.open(database); PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).as("row for %s", id).isTrue();
                return rs.getString(1);
            }
        }
    }

    private void update(String database, String sql, UUID id) throws SQLException {
        try (Connection c = stack.open(database); PreparedStatement s = c.prepareStatement(sql)) {
            if (id != null) {
                s.setObject(1, id);
            }
            s.executeUpdate();
        }
    }
}
