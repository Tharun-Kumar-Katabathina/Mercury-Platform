package com.mercury.inventory.exception;

import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InventoryNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleInventoryNotFound(
            InventoryNotFoundException exception) {

        return body(404, "INVENTORY_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleReservationNotFound(ReservationNotFoundException exception) {

        return body(404, "RESERVATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(DuplicateInventoryException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleDuplicateInventory(
            DuplicateInventoryException exception) {

        return body(409, "DUPLICATE_INVENTORY", exception.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleInsufficientStock(
            InsufficientStockException exception) {

        return body(409, "INSUFFICIENT_STOCK", exception.getMessage());
    }

    @ExceptionHandler(InsufficientReservedStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleInsufficientReservedStock(
            InsufficientReservedStockException exception) {

        return body(409, "INSUFFICIENT_RESERVED_STOCK", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, Object> handleIdempotencyKeyMismatch(
            IdempotencyKeyMismatchException exception) {

        return body(422, "IDEMPOTENCY_KEY_MISMATCH", exception.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleOptimisticLock(
            ObjectOptimisticLockingFailureException exception) {

        return body(409, "CONCURRENT_MODIFICATION",
                "Inventory was modified by another request, please retry");
    }

    private Map<String, Object> body(int status, String error, String message) {
        return Map.of(
                "timestamp", Instant.now(),
                "status", status,
                "error", error,
                "message", message
        );
    }
}
