package com.mercury.order.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final JsonMapper jsonMapper;

    public GlobalExceptionHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleOrderNotFound(OrderNotFoundException exception) {

        return body(404, "ORDER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleMissingIdempotencyKey(
            MissingIdempotencyKeyException exception) {

        return body(400, "MISSING_IDEMPOTENCY_KEY", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> handleIdempotencyKeyMismatch(
            IdempotencyKeyMismatchException exception) {

        return body(422, "IDEMPOTENCY_KEY_MISMATCH", exception.getMessage());
    }

    @ExceptionHandler(OrderConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleOrderConflict(OrderConflictException exception) {

        return body(409, "ORDER_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(OrderProcessingException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleOrderProcessing(OrderProcessingException exception) {

        return body(500, "ORDER_PROCESSING_FAILED", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException exception) {

        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));

        return body(400, "INVALID_ORDER", message.isBlank() ? "invalid order request" : message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleUnreadableRequest(Exception exception) {

        return body(400, "INVALID_ORDER", "request could not be read: malformed body or parameter");
    }

    /**
     * Inventory 4xx (404 INVENTORY_NOT_FOUND, 409 INSUFFICIENT_STOCK, 422 IDEMPOTENCY_KEY_MISMATCH,
     * ...) keeps its own status, code and message. Inventory failing or unreachable is 502 / 503.
     */
    @ExceptionHandler(InventoryServiceException.class)
    public ResponseEntity<Map<String, Object>> handleInventoryService(
            InventoryServiceException exception) {

        return downstream(exception.getStatus(), exception.getResponseBody(),
                exception.getMessage(), "INVENTORY_REQUEST_REJECTED",
                "INVENTORY_SERVICE_UNAVAILABLE", "Inventory Service is unavailable",
                "INVENTORY_SERVICE_ERROR", "Inventory Service failed to process the request");
    }

    @ExceptionHandler(InvalidOrderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleInvalidOrder(InvalidOrderException exception) {

        return body(400, "INVALID_ORDER", exception.getMessage());
    }

    /**
     * Product 4xx (404 PRODUCT_NOT_FOUND, ...) keeps its own status, code and message.
     * Product failing or being unreachable is reported as 502 / 503.
     */
    @ExceptionHandler(ProductServiceException.class)
    public ResponseEntity<Map<String, Object>> handleProductService(
            ProductServiceException exception) {

        return downstream(exception.getStatus(), exception.getResponseBody(),
                exception.getMessage(), "PRODUCT_REQUEST_REJECTED",
                "PRODUCT_SERVICE_UNAVAILABLE", "Product Service is unavailable",
                "PRODUCT_SERVICE_ERROR", "Product Service failed to process the request");
    }

    private ResponseEntity<Map<String, Object>> downstream(
            HttpStatusCode status, String responseBody, String fallbackMessage,
            String rejectedCode,
            String unavailableCode, String unavailableMessage,
            String errorCode, String errorMessage) {

        if (status.is4xxClientError()) {
            JsonNode error = parse(responseBody);
            String code = error.path("error").asString("");
            String message = error.path("message").asString("");
            return ResponseEntity.status(status).body(body(
                    status.value(),
                    code.isBlank() ? rejectedCode : code,
                    message.isBlank() ? fallbackMessage : message));
        }
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(body(503, unavailableCode, unavailableMessage));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(body(502, errorCode, errorMessage));
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

    /**
     * The database is unreachable or saturated (stopped, restarting, connection pool exhausted, query timed out).
     * That is a temporary condition of the platform, not a bug in the request: 503 with Retry-After, never 500.
     */
    @ExceptionHandler({
            org.springframework.transaction.CannotCreateTransactionException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.dao.QueryTimeoutException.class})
    public ResponseEntity<Map<String, Object>> handleDatabaseUnavailable(Exception exception) {

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 503,
                        "error", "DATABASE_UNAVAILABLE",
                        "message", "The database is temporarily unavailable, please retry shortly"));
    }

    /**
     * A transaction that fails to commit or roll back is reported by Spring as a system exception, whatever the reason.
     * When the reason is that the database closed or killed the connection (it restarted, or failed over) this is the same
     * temporary outage as above: 503. Any other persistence failure is a bug and stays a 500 with nothing revealed.
     */
    @ExceptionHandler({org.springframework.orm.jpa.JpaSystemException.class,
            org.springframework.transaction.TransactionSystemException.class})
    public ResponseEntity<Map<String, Object>> handlePersistenceSystemFailure(Exception exception) {

        if (isLostConnection(exception)) {
            log.warn("database connection lost during a transaction: {}", exception.getMessage());
            return handleDatabaseUnavailable(exception);
        }
        log.error("persistence failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "timestamp", Instant.now(),
                        "status", 500,
                        "error", "INTERNAL_ERROR",
                        "message", "An unexpected error occurred"));
    }

    /** SQLSTATE class 08 (connection exception) and 57P (the server shut down or cannot connect), or a connection the pool reports closed. */
    private static boolean isLostConnection(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.startsWith("57P"))) {
                    return true;
                }
                if ("Connection is closed".equals(sql.getMessage())) {
                    return true;
                }
            }
        }
        return false;
    }
}
