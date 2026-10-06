package com.mercury.order.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The domain events Order Service publishes. They carry facts about an order and never contain
 * secrets or the client's Idempotency-Key. {@code eventId} is what consumers use to ignore a
 * redelivered event.
 */
public sealed interface OrderEvent permits OrderCreatedEvent, OrderConfirmedEvent, OrderCancelledEvent {

    UUID eventId();

    String eventType();

    Instant occurredAt();

    UUID orderId();
}
