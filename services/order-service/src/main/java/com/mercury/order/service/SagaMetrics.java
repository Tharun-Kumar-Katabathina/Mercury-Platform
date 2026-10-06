package com.mercury.order.service;

import com.mercury.order.model.SagaState;
import com.mercury.order.repository.OrderSagaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** The numbers that say whether recovery is healthy. Exposed through /actuator/metrics. */
@Component
public class SagaMetrics {

    private final MeterRegistry registry;
    private final Counter created;
    private final Counter confirmed;
    private final Counter cancelled;
    private final Counter recoverySuccess;
    private final Counter recoveryFailed;
    private final Counter compensationSuccess;
    private final Counter compensationFailure;

    public SagaMetrics(MeterRegistry registry, OrderSagaRepository sagas, Clock clock) {
        this.registry = registry;
        this.created = Counter.builder("orders.created").description("orders accepted (saved PENDING)").register(registry);
        this.confirmed = Counter.builder("orders.confirmed").register(registry);
        this.cancelled = Counter.builder("orders.cancelled").register(registry);
        this.recoverySuccess = Counter.builder("orders.recovery.success")
                .description("sagas driven to a final state by recovery").register(registry);
        this.recoveryFailed = Counter.builder("orders.recovery.failed")
                .description("recovery attempts that did not finish").register(registry);
        this.compensationSuccess = Counter.builder("saga.compensation.success")
                .description("reservations given back").register(registry);
        this.compensationFailure = Counter.builder("saga.compensation.failure")
                .description("releases that failed").register(registry);

        Gauge.builder("orders.recovery.pending", () ->
                        sagas.countDue(List.of(SagaState.RESERVING, SagaState.AWAITING_INVENTORY, SagaState.COMPENSATING), Instant.now(clock)))
                .description("sagas that are due for recovery right now").register(registry);
        Gauge.builder("orders.recovery.lag.seconds", () -> {
                    Instant now = Instant.now(clock);
                    return sagas.oldestDue(List.of(SagaState.RESERVING, SagaState.AWAITING_INVENTORY, SagaState.COMPENSATING), now)
                            .map(due -> (double) Math.max(0, now.getEpochSecond() - due.getEpochSecond()))
                            .orElse(0.0);
                })
                .description("how long the longest-waiting due saga has been waiting for recovery (0 = nothing waiting)")
                .baseUnit("seconds").register(registry);
        Gauge.builder("orders.recovery.exhausted", () -> sagas.countByState(SagaState.RECOVERY_FAILED))
                .description("sagas that gave up and need a person").register(registry);
        for (SagaState state : SagaState.values()) {
            Gauge.builder("orders.saga.count", () -> sagas.countByState(state))
                    .tag("state", state.name()).register(registry);
        }
    }

    public void orderCreated() {
        created.increment();
    }

    public void orderConfirmed() {
        confirmed.increment();
    }

    public void orderCancelled() {
        cancelled.increment();
    }

    public void recoverySucceeded() {
        recoverySuccess.increment();
    }

    public void recoveryAttemptFailed() {
        recoveryFailed.increment();
    }

    public void compensationSucceeded() {
        compensationSuccess.increment();
    }

    public void compensationFailed() {
        compensationFailure.increment();
    }

    /** An Inventory call that could not be completed (timeout, unreachable, 5xx). */
    public void inventoryTimeout(String operation) {
        Counter.builder("inventory." + operation + ".timeout")
                .description("Inventory " + operation + " calls with an unknown outcome")
                .register(registry).increment();
    }
}
