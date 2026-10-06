package com.mercury.inventory.exception;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID productId, String idempotencyKey) {
        super("No reservation for product " + productId + " under the given idempotency key");
    }
}
