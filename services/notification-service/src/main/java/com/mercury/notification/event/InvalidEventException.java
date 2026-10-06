package com.mercury.notification.event;

/** The message can never be processed (malformed, missing fields). Retrying cannot help: dead-letter it. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidEventException(String message) {
        super(message);
    }
}
