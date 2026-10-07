package com.mercury.user.exception;

public class TooManyAttemptsException extends RuntimeException {
    public TooManyAttemptsException() {
        super("Too many attempts, please wait a minute");
    }
}
