package com.mercury.notification.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {

    private final Counter consumed;
    private final Counter duplicate;
    private final Counter failed;
    private final Counter dlq;
    private final Counter ignored;

    public NotificationMetrics(MeterRegistry registry) {
        this.consumed = Counter.builder("events.consumed").description("events processed successfully").register(registry);
        this.duplicate = Counter.builder("events.duplicate").description("redelivered events ignored").register(registry);
        this.failed = Counter.builder("events.failed").description("processing attempts that failed").register(registry);
        this.dlq = Counter.builder("events.dlq").description("records sent to the dead-letter topic").register(registry);
        this.ignored = Counter.builder("events.ignored").description("valid events this service does not act on").register(registry);
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

    public void ignored() {
        ignored.increment();
    }
}
