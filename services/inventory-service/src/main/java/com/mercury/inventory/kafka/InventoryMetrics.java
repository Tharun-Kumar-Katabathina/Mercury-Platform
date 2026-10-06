package com.mercury.inventory.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class InventoryMetrics {

    private final Counter consumed;
    private final Counter duplicate;
    private final Counter failed;
    private final Counter dlq;
    private final Counter reserved;
    private final Counter rejected;

    public InventoryMetrics(MeterRegistry registry) {
        this.consumed = Counter.builder("inventory.commands.consumed").description("reservation commands processed").register(registry);
        this.duplicate = Counter.builder("inventory.commands.duplicate").description("redelivered commands ignored").register(registry);
        this.failed = Counter.builder("inventory.commands.failed").description("processing attempts that failed").register(registry);
        this.dlq = Counter.builder("inventory.commands.dlq").description("commands sent to the dead-letter topic").register(registry);
        this.reserved = Counter.builder("inventory.order.reserved").description("orders reserved in full").register(registry);
        this.rejected = Counter.builder("inventory.order.rejected").description("orders rejected (nothing reserved)").register(registry);
    }

    public void consumed() {
        consumed.increment();
    }

    public void duplicate() {
        duplicate.increment();
    }

    public void failed() {
        failed.increment();
    }

    public void deadLettered() {
        dlq.increment();
    }

    public void reserved() {
        reserved.increment();
    }

    public void rejected() {
        rejected.increment();
    }
}
