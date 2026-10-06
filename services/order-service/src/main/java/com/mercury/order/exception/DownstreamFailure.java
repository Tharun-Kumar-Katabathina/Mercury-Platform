package com.mercury.order.exception;

import org.springframework.http.HttpStatusCode;

/** Implemented by the exceptions that represent a failed call to another service. */
public interface DownstreamFailure {

    HttpStatusCode getStatus();

    /**
     * True when the request certainly never left this service (circuit open, bulkhead full), so the
     * remote side cannot have applied it. Only false-or-unknown outcomes need to be resolved later.
     */
    boolean wasNeverSent();
}
