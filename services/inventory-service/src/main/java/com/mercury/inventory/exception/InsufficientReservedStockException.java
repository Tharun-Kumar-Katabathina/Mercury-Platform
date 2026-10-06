package com.mercury.inventory.exception;

import java.util.UUID;

/** A release asked to give back more than is currently reserved. */
public class InsufficientReservedStockException extends RuntimeException {

    public InsufficientReservedStockException(UUID productId, int requested, int reserved) {
        super("Cannot release " + requested + " for product " + productId
                + ": only " + reserved + " reserved");
    }
}
