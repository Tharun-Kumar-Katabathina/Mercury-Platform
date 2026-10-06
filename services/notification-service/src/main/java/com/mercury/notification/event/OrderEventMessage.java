package com.mercury.notification.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * This service's own reading of an Order Service event: only the fields it needs, unknown ones
 * ignored. It is not shared with the Order Service, so the two can evolve independently.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEventMessage(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String reason) {
}
