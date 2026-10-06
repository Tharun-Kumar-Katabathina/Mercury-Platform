package com.mercury.inventory.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class EventMetrics {

    private final Counter published;
    private final Counter publishFailed;

    public EventMetrics(MeterRegistry registry, OutboxRepository outbox, Clock clock) {
        this.published = Counter.builder("events.published")
                .description("events acknowledged by Kafka").register(registry);
        this.publishFailed = Counter.builder("events.publish.failed")
                .description("publish attempts that failed and will be retried").register(registry);
        Gauge.builder("outbox.pending", outbox::countByPublishedAtIsNull)
                .description("events written but not yet published").register(registry);
        Gauge.builder("outbox.oldest.age.seconds", () -> outbox.oldestUnpublished()
                        .map(oldest -> (double) Duration.between(oldest, Instant.now(clock)).toSeconds()).orElse(0.0))
                .description("age of the oldest unpublished event").register(registry);
    }

    public void published() {
        published.increment();
    }

    public void publishFailed() {
        publishFailed.increment();
    }
}
