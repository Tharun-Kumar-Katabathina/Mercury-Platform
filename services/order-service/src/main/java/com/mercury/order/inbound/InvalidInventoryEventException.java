package com.mercury.order.inbound;

/** An Inventory event that can never be processed (unreadable, missing fields): not retried, dead-lettered. */
public class InvalidInventoryEventException extends RuntimeException {

    public InvalidInventoryEventException(String message) {
        super(message);
    }

    public InvalidInventoryEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
