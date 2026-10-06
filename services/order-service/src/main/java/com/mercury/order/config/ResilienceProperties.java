package com.mercury.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Circuit breaker and bulkhead settings for each downstream service
 * (order.resilience.product.*, order.resilience.inventory.*). All overridable per environment.
 */
@ConfigurationProperties(prefix = "order.resilience")
public record ResilienceProperties(
        @DefaultValue Downstream product,
        @DefaultValue Downstream inventory) {

    public record Downstream(
            /** open the circuit when at least this percentage of recorded calls failed */
            @DefaultValue("50") float failureRateThreshold,
            /** how many of the most recent calls the failure rate is measured over */
            @DefaultValue("20") int slidingWindowSize,
            /** calls needed before the failure rate is evaluated at all */
            @DefaultValue("10") int minimumCalls,
            /** how long the circuit stays open before letting trial calls through */
            @DefaultValue("10s") Duration waitDurationInOpenState,
            /** trial calls allowed while half-open */
            @DefaultValue("3") int permittedCallsInHalfOpenState,
            /** most simultaneous in-flight calls to this service */
            @DefaultValue("25") int maxConcurrentCalls,
            /** how long a call may wait for a free slot (0 = reject at once) */
            @DefaultValue("0ms") Duration maxWait) {
    }
}
