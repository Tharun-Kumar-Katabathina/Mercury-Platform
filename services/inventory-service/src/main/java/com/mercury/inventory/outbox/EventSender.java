package com.mercury.inventory.outbox;

/**
 * The only place Kafka is touched. {@link #send} returns normally only once the broker has
 * acknowledged the event, and throws otherwise. Kept as an interface so the publisher's behaviour
 * under every failure can be tested deterministically.
 */
public interface EventSender {

    void send(OutboxMessage message) throws Exception;
}
