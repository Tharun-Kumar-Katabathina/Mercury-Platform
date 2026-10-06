package com.mercury.inventory.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The stock-reservation numbers that say whether Inventory is healthy, for both reservation paths
 * (path=rest for the per-item REST reserve, path=async for the whole-order Kafka command).
 * Counted once per request AFTER its transaction committed, never per optimistic-lock retry.
 *
 *  inventory.reservations{path,result}   reserved | replayed
 *  inventory.rejections{path,reason}     INSUFFICIENT_STOCK | INVENTORY_NOT_FOUND
 *  inventory.oversell.attempts{path}     requests for more stock than was available: refused, nothing oversold
 */
@Component
public class ReservationMetrics {

    private final MeterRegistry registry;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        // present (at zero) from the first scrape, so dashboards and rate() have a series to start from
        for (String path : new String[]{"rest", "async"}) {
            registry.counter("inventory.reservations", "path", path, "result", "reserved");
            registry.counter("inventory.reservations", "path", path, "result", "replayed");
            registry.counter("inventory.rejections", "path", path, "reason", "INSUFFICIENT_STOCK");
            registry.counter("inventory.rejections", "path", path, "reason", "INVENTORY_NOT_FOUND");
            registry.counter("inventory.oversell.attempts", "path", path);
        }
    }

    public void reserved(String path, boolean replayed) {
        registry.counter("inventory.reservations", "path", path, "result", replayed ? "replayed" : "reserved").increment();
    }

    public void rejected(String path, String reason) {
        registry.counter("inventory.rejections", "path", path, "reason", reason).increment();
        if ("INSUFFICIENT_STOCK".equals(reason)) {
            registry.counter("inventory.oversell.attempts", "path", path).increment();
        }
    }
}
