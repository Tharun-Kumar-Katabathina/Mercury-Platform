package com.mercury.user.exception;

/** Wrong email, wrong password, locked or disabled account: always the same answer, so none can be told apart. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
