package com.mercury.product.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Inventory Service answered with an error (or could not be reached). Keeps the original
 * status and body so the API layer can decide how to present it; it never leaks Spring's
 * raw RestClient exceptions out of the client.
 */
public class InventoryServiceException extends RuntimeException {

    private final HttpStatusCode status;
    private final String responseBody;

    public InventoryServiceException(HttpStatusCode status, String responseBody) {
        this(status, responseBody, null);
    }

    public InventoryServiceException(
            HttpStatusCode status, String responseBody, Throwable cause) {

        super("Inventory Service returned " + status.value(), cause);
        this.status = status;
        this.responseBody = responseBody;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
