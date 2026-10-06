package com.mercury.inventory.outbox;

import com.mercury.inventory.config.OutboxProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaEventSender implements EventSender {

    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;

    public KafkaEventSender(KafkaTemplate<String, String> kafka, OutboxProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    @Override
    public void send(OutboxMessage message) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                message.topic(), null, message.key(), message.payload(),
                List.of(new RecordHeader("event-id", message.eventId().toString().getBytes(StandardCharsets.UTF_8)),
                        new RecordHeader("event-type", message.eventType().getBytes(StandardCharsets.UTF_8))));
        kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
