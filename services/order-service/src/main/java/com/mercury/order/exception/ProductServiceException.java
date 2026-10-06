package com.mercury.order.exception;

import org.springframework.http.HttpStatusCode;

/** Product Service answered with an error, or could not be reached (status 503). */
public class ProductServiceException extends RuntimeException {

    private final HttpStatusCode status;
    private final String responseBody;

    public ProductServiceException(HttpStatusCode status, String responseBody) {
        this(status, responseBody, null);
    }

    public ProductServiceException(HttpStatusCode status, String responseBody, Throwable cause) {
        super("Product Service returned " + status.value(), cause);
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
