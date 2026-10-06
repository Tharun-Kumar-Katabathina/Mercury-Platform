package com.mercury.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Operational settings for saga recovery; every value can be overridden per environment
 * (for example ORDER_RECOVERY_INTERVAL=30s). Nothing here is hardcoded in the logic.
 */
@ConfigurationProperties(prefix = "order.recovery")
public record RecoveryProperties(
        /** run the background worker at all */
        @DefaultValue("true") boolean enabled,
        /** pause between worker passes */
        @DefaultValue("10s") Duration interval,
        /** most sagas taken per pass */
        @DefaultValue("20") int batchSize,
        /** failed recovery attempts before a saga becomes RECOVERY_FAILED */
        @DefaultValue("10") int maxAttempts,
        /** wait after the first failed attempt */
        @DefaultValue("1s") Duration initialBackoff,
        /** the wait never grows beyond this */
        @DefaultValue("5m") Duration maxBackoff,
        /** wait multiplier per further failed attempt (2.0 = 1s, 2s, 4s, 8s...) */
        @DefaultValue("2.0") double backoffMultiplier,
        /** how long one worker (or a live request) exclusively owns a saga */
        @DefaultValue("60s") Duration lease,
        /** a saga still RESERVING after this long without a heartbeat is treated as abandoned */
        @DefaultValue("60s") Duration staleAfter
) {

    /** Wait before attempt number {@code failedAttempts + 1}. */
    public Duration backoffAfter(int failedAttempts) {
        double millis = initialBackoff.toMillis() * Math.pow(backoffMultiplier, Math.max(0, failedAttempts - 1));
        return Duration.ofMillis((long) Math.min(millis, maxBackoff.toMillis()));
    }
}
