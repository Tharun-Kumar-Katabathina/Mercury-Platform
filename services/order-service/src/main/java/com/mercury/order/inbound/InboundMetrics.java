package com.mercury.order.inbound;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counters for the Inventory events Order Service consumes. */
@Component
public class InboundMetrics {

    private final Counter consumed;
    private final Counter duplicate;
    private final Counter ignored;
    private final Counter lateReleased;
    private final Counter failed;
    private final Counter dlq;

    public InboundMetrics(MeterRegistry registry) {
        this.consumed = Counter.builder("order.inventory.events.consumed").description("Inventory events processed").register(registry);
        this.duplicate = Counter.builder("order.inventory.events.duplicate").description("redelivered events ignored").register(registry);
        this.ignored = Counter.builder("order.inventory.events.ignored").description("events with nothing to do (unknown order, already final)").register(registry);
        this.lateReleased = Counter.builder("order.inventory.late.reservations").description("reservations that arrived for a finished order and are being released").register(registry);
        this.failed = Counter.builder("order.inventory.events.failed").description("processing attempts that failed").register(registry);
        this.dlq = Counter.builder("order.inventory.events.dlq").description("events sent to the dead-letter topic").register(registry);
    }

    public void consumed() { consumed.increment(); }

    public void duplicate() { duplicate.increment(); }

    public void ignored() { ignored.increment(); }

    public void lateReservation() { lateReleased.increment(); }

    public void failed() { failed.increment(); }

    public void deadLettered() { dlq.increment(); }
}
