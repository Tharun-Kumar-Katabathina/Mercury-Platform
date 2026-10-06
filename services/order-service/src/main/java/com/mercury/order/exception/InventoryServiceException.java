package com.mercury.order.exception;

import org.springframework.http.HttpStatusCode;

/** Inventory Service answered with an error, or could not be reached (status 503). */
public class InventoryServiceException extends RuntimeException {

    private final HttpStatusCode status;
    private final String responseBody;

    public InventoryServiceException(HttpStatusCode status, String responseBody) {
        this(status, responseBody, null);
    }

    public InventoryServiceException(HttpStatusCode status, String responseBody, Throwable cause) {
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
