package com.mercury.recommendation.exception;

/** An event that can never be processed: not retried, dead-lettered. */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
