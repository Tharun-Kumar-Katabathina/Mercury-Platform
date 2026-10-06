package com.mercury.order.inbound;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventListener {

    private final InventoryEventHandler handler;
    private final InboundMetrics metrics;
    private final AfterProcessingHook afterProcessing;

    public InventoryEventListener(
            InventoryEventHandler handler, InboundMetrics metrics, AfterProcessingHook afterProcessing) {
        this.handler = handler;
        this.metrics = metrics;
        this.afterProcessing = afterProcessing;
    }

    @KafkaListener(
            id = "order-inventory-events",
            topics = "${order.inbound.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            autoStartup = "${order.inbound.enabled:true}")
    public void onEvent(ConsumerRecord<String, String> record) {
        try {
            handler.handle(record.value());
            afterProcessing.afterProcessed(record);
        } catch (RuntimeException e) {
            metrics.failed();
            throw e;   // the container's error handler retries with backoff, then dead-letters
        }
    }
}
