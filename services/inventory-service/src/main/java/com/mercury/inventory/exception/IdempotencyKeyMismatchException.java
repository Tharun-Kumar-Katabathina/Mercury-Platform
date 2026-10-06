package com.mercury.inventory.exception;

public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey
                + "' was already used with a different request");
    }
}
