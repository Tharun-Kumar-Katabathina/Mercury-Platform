package com.mercury.order.client;

import com.mercury.order.config.ResilienceProperties;
import com.mercury.order.exception.DownstreamFailure;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Protects the LIVE request path from a failing or slow downstream service:
 *
 *  - a circuit breaker stops calling a service that keeps failing, so requests fail fast
 *    (CLOSED -> OPEN after too many failures, -> HALF_OPEN after a wait, -> CLOSED on success);
 *  - a bulkhead caps how many calls to that service are in flight, so one sick dependency cannot
 *    use up every thread.
 *
 * Only infrastructure failures count against the circuit: a 5xx or an unreachable service. A 4xx is
 * the service answering normally ("no", "not found") and counts as healthy.
 *
 * This is not a substitute for durable recovery. It protects requests; recovery finishes work that
 * was already accepted.
 */
public class DownstreamGuard {

    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    private DownstreamGuard(CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
    }

    public static DownstreamGuard create(
            String name, ResilienceProperties.Downstream settings, MeterRegistry meters) {

        // Resilience4j would silently cap minimum-calls at the window size, which makes the circuit
        // open far earlier than configured. Refuse the contradiction instead of hiding it.
        if (settings.minimumCalls() > settings.slidingWindowSize()) {
            throw new IllegalArgumentException("order.resilience." + name + ".minimum-calls ("
                    + settings.minimumCalls() + ") cannot exceed sliding-window-size ("
                    + settings.slidingWindowSize() + ")");
        }

        CircuitBreaker breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumCalls())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordException(e -> e instanceof DownstreamFailure failure
                        && failure.getStatus().is5xxServerError())
                .build());

        Bulkhead bulkhead = Bulkhead.of(name, BulkheadConfig.custom()
                .maxConcurrentCalls(settings.maxConcurrentCalls())
                .maxWaitDuration(settings.maxWait())
                .build());

        if (meters != null) {
            Gauge.builder("downstream.circuit.state", breaker, b -> switch (b.getState()) {
                        case CLOSED, DISABLED, METRICS_ONLY -> 0;
                        case HALF_OPEN -> 1;
                        case OPEN, FORCED_OPEN -> 2;
                    })
                    .description("0 = closed, 1 = half-open, 2 = open")
                    .tag("service", name).register(meters);
            Gauge.builder("downstream.bulkhead.available", bulkhead,
                            b -> b.getMetrics().getAvailableConcurrentCalls())
                    .tag("service", name).register(meters);
        }
        return new DownstreamGuard(breaker, bulkhead);
    }

    /** No protection at all; for tests of the client's HTTP behaviour on its own. */
    public static DownstreamGuard passThrough() {
        return new DownstreamGuard(null, null);
    }

    /**
     * @param rejected builds the exception for a call that was refused locally; it must report
     *                 {@code wasNeverSent() == true}
     */
    public <T> T execute(Supplier<T> call, Function<Throwable, RuntimeException> rejected) {
        if (circuitBreaker == null) {
            return call.get();
        }
        Supplier<T> guarded = Bulkhead.decorateSupplier(bulkhead,
                CircuitBreaker.decorateSupplier(circuitBreaker, call));
        try {
            return guarded.get();
        } catch (CallNotPermittedException | BulkheadFullException e) {
            throw rejected.apply(e);
        }
    }

    public CircuitBreaker.State state() {
        return circuitBreaker == null ? CircuitBreaker.State.CLOSED : circuitBreaker.getState();
    }
}
