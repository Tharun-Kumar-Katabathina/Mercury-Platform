package com.mercury.order.exception;

/** The order could not be finalised after stock was reserved; the reservation was undone. */
public class OrderProcessingException extends RuntimeException {

    public OrderProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
