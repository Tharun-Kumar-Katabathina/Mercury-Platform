package com.mercury.product.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

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