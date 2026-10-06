package com.mercury.order.outbox;

import com.mercury.order.config.OutboxProperties;
import org.springframework.stereotype.Component;

/** Which Kafka topic each outbox event type is published to. */
@Component
public class EventTopics {

    private final OutboxProperties properties;

    public EventTopics(OutboxProperties properties) {
        this.properties = properties;
    }

    public String topicFor(String eventType) {
        return "InventoryReservationRequested".equals(eventType)
                ? properties.inventoryCommandsTopic()
                : properties.topic();
    }
}
