package com.mercury.inventory.exception;

import java.util.UUID;

public class OrderReservationNotFoundException extends RuntimeException {

    public OrderReservationNotFoundException(UUID orderId) {
        super("No order-level reservation outcome for order " + orderId);
    }
}
