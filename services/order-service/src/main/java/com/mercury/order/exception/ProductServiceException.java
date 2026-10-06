package com.mercury.order.exception;

import org.springframework.http.HttpStatusCode;

/** Product Service answered with an error, or could not be reached (status 503). */
public class ProductServiceException extends RuntimeException implements DownstreamFailure {

    private final HttpStatusCode status;
    private final String responseBody;
    private final boolean neverSent;

    public ProductServiceException(HttpStatusCode status, String responseBody) {
        this(status, responseBody, null);
    }

    public ProductServiceException(HttpStatusCode status, String responseBody, Throwable cause) {
        this(status, responseBody, cause, false);
    }

    /** @param neverSent the request was rejected locally (circuit open, bulkhead full) and never sent */
    public ProductServiceException(HttpStatusCode status, String responseBody, Throwable cause, boolean neverSent) {
        super("Product Service returned " + status.value(), cause);
        this.status = status;
        this.responseBody = responseBody;
        this.neverSent = neverSent;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public String getResponseBody() {
        return responseBody;
    }

    @Override
    public boolean wasNeverSent() {
        return neverSent;
    }
}
