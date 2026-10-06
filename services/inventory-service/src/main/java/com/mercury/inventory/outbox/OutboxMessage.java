package com.mercury.inventory.outbox;

import java.util.UUID;

/** What the publisher hands to the broker: plain values, no entity, no transaction attached. */
public record OutboxMessage(long seq, UUID eventId, UUID orderId, String eventType, String topic, String payload) {

    /** the Kafka record key: all events of one order share it, so they stay ordered within a partition */
    public String key() {
        return orderId.toString();
    }
}
