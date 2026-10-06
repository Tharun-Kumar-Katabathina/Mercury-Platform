package com.mercury.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Outbox publisher settings (inventory.outbox.*), all overridable per environment. */
@ConfigurationProperties(prefix = "inventory.outbox")
public record OutboxProperties(
        /** run the background publisher at all */
        @DefaultValue("true") boolean enabled,
        /** Kafka topic the order events go to */
        @DefaultValue("mercury.inventory.events") String topic,
        /** pause between publisher passes */
        @DefaultValue("1s") Duration interval,
        /** most events claimed per pass */
        @DefaultValue("50") int batchSize,
        /** how long a claimed event is reserved for one publisher */
        @DefaultValue("30s") Duration lease,
        /** wait after the first failed publish of an event */
        @DefaultValue("1s") Duration initialBackoff,
        /** the wait never grows beyond this; events are retried forever, never dropped */
        @DefaultValue("1m") Duration maxBackoff,
        @DefaultValue("2.0") double backoffMultiplier,
        /** how long to wait for Kafka to acknowledge one event */
        @DefaultValue("10s") Duration sendTimeout
) {

    public Duration backoffAfter(int failedAttempts) {
        double millis = initialBackoff.toMillis() * Math.pow(backoffMultiplier, Math.max(0, failedAttempts - 1));
        return Duration.ofMillis((long) Math.min(millis, maxBackoff.toMillis()));
    }
}
