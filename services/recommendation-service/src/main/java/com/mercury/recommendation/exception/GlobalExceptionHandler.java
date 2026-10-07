package com.mercury.recommendation.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, ValidationFailedException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        return reply(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is not valid");
    }

    /** the vector index or the database is down: recommendations are unavailable, nothing else is */
    @ExceptionHandler({org.springframework.web.client.ResourceAccessException.class,
            org.springframework.web.client.HttpServerErrorException.class,
            org.springframework.dao.DataAccessResourceFailureException.class})
    public ResponseEntity<Map<String, Object>> unavailable(Exception e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(body(HttpStatus.SERVICE_UNAVAILABLE, "RECOMMENDATIONS_UNAVAILABLE", "Recommendations are temporarily unavailable"));
    }

    private static ResponseEntity<Map<String, Object>> reply(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(body(status, error, message));
    }

    private static Map<String, Object> body(HttpStatus status, String error, String message) {
        return Map.of("timestamp", Instant.now(), "status", status.value(), "error", error, "message", message);
    }
}
