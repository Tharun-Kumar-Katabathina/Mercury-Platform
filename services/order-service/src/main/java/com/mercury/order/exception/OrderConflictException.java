package com.mercury.order.exception;

/** The same Idempotency-Key is still being processed by another request. */
public class OrderConflictException extends RuntimeException {

    public OrderConflictException(String message) {
        super(message);
    }
}
