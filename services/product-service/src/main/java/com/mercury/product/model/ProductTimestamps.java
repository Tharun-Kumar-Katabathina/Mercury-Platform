package com.mercury.product.model;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Stamps a product when its row is inserted and when it is updated.
 *
 * The timestamp columns keep microseconds, while the clock can be finer: nanoseconds on Linux, where the service
 * runs, microseconds on macOS. A product stamped with the finer value differs from its own row (the database rounds
 * what it cannot keep), so the caller who creates it would be handed a timestamp that no later read returns. The
 * value is therefore cut to what the column keeps before it is written, and the entity and its row always agree.
 *
 * Hibernate asks Spring to create this listener, which is how it gets the {@link Clock}.
 */
public class ProductTimestamps {

    private final Clock clock;

    public ProductTimestamps(Clock clock) {
        this.clock = clock;
    }

    @PrePersist
    void onCreate(Product product) {
        product.stampCreated(now());
    }

    @PreUpdate
    void onUpdate(Product product) {
        product.stampUpdated(now());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
