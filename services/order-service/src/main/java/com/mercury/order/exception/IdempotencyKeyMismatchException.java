package com.mercury.order.exception;

public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException() {
        super("Idempotency-Key was already used with a different order request");
    }
}
