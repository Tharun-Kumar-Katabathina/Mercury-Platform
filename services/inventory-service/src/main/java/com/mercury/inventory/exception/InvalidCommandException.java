package com.mercury.inventory.exception;

/** A reservation command that can never be processed (malformed, empty, non-positive quantity). Do not retry. */
public class InvalidCommandException extends RuntimeException {

    public InvalidCommandException(String message) {
        super(message);
    }

    public InvalidCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
