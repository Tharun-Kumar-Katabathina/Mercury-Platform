package com.mercury.inventory.exception;

import java.util.UUID;

/** The idempotency key was fenced by the caller before this reserve arrived: nothing may be reserved under it. */
public class ReservationFencedException extends RuntimeException {

    public ReservationFencedException(UUID productId) {
        super("The reservation for product " + productId + " under this idempotency key was fenced (cancelled) by its caller");
    }
}
