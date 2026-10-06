package com.mercury.product.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final JsonMapper jsonMapper;

    public GlobalExceptionHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleMissingIdempotencyKey(
            MissingIdempotencyKeyException exception) {

        return body(400, "MISSING_IDEMPOTENCY_KEY", exception.getMessage());
    }

    /**
     * 4xx from Inventory (404 INVENTORY_NOT_FOUND, 409 INSUFFICIENT_STOCK,
     * 422 IDEMPOTENCY_KEY_MISMATCH, ...) is passed through with its own status, error code
     * and message. Inventory being down or failing is reported as 503/502, not as our bug.
     */
    @ExceptionHandler(InventoryServiceException.class)
    public ResponseEntity<Map<String, Object>> handleInventoryService(
            InventoryServiceException exception) {

        HttpStatusCode status = exception.getStatus();

        if (status.is4xxClientError()) {
            JsonNode inventoryError = parse(exception.getResponseBody());
            String error = inventoryError.path("error").asString("");
            String message = inventoryError.path("message").asString("");
            return ResponseEntity.status(status).body(body(
                    status.value(),
                    error.isBlank() ? "INVENTORY_REQUEST_REJECTED" : error,
                    message.isBlank() ? exception.getMessage() : message));
        }

        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body(
                    503, "INVENTORY_SERVICE_UNAVAILABLE",
                    "Inventory Service is unavailable"));
        }

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body(
                502, "INVENTORY_SERVICE_ERROR",
                "Inventory Service failed to process the request"));
    }

    private JsonNode parse(String json) {
        try {
            return jsonMapper.readTree(json == null ? "" : json);
        } catch (RuntimeException e) {
            return jsonMapper.createObjectNode();
        }
    }

    private Map<String, Object> body(int status, String error, String message) {
        return Map.of(
                "timestamp", Instant.now(),
                "status", status,
                "error", error,
                "message", message
        );
    }

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleProductNotFound(
            ProductNotFoundException exception) {

        return Map.of(
                "timestamp", Instant.now(),
                "status", 404,
                "error", "PRODUCT_NOT_FOUND",
                "message", exception.getMessage()
        );
    }

    @ExceptionHandler(DuplicateSkuException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleDuplicateSku(
            DuplicateSkuException exception) {

        return Map.of(
                "timestamp", Instant.now(),
                "status", 409,
                "error", "DUPLICATE_SKU",
                "message", exception.getMessage()
        );
    }
}