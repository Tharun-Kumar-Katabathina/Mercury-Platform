package com.mercury.inventory.outbox;

import com.mercury.inventory.event.InventoryEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;

/**
 * Writes an event into the outbox. MANDATORY: it must run inside the transaction of the state
 * change the event describes, so the two commit or roll back together. Calling it without one fails
 * immediately rather than silently losing atomicity.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OutboxWriter(OutboxRepository outbox, JsonMapper jsonMapper, Clock clock) {
        this.outbox = outbox;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(InventoryEvent event) {
        outbox.save(OutboxEvent.of(
                event.eventId(),
                event.orderId(),
                event.eventType(),
                jsonMapper.writeValueAsString(event),
                Instant.now(clock)));
    }
}
