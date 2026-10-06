package com.mercury.order.exception;

import java.util.UUID;

/**
 * An order cannot be finished while Inventory may still hold stock for it. Raised instead of cancelling,
 * so a reservation that turns up late can never be left without an owner.
 */
public class StockStillHeldException extends RuntimeException {

    public StockStillHeldException(UUID orderId) {
        super("Order " + orderId + " still has reserved stock that has not been released");
    }
}
