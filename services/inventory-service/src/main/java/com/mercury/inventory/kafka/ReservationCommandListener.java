package com.mercury.inventory.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ReservationCommandListener {

    private final ReservationCommandHandler handler;
    private final InventoryMetrics metrics;
    private final AfterProcessingHook afterProcessing;

    public ReservationCommandListener(
            ReservationCommandHandler handler, InventoryMetrics metrics, AfterProcessingHook afterProcessing) {
        this.handler = handler;
        this.metrics = metrics;
        this.afterProcessing = afterProcessing;
    }

    @KafkaListener(topics = "${inventory.command-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onCommand(ConsumerRecord<String, String> record) {
        try {
            handler.handle(record.value());
            afterProcessing.afterProcessed(record);
        } catch (RuntimeException e) {
            metrics.failed();
            throw e;   // the container's error handler retries with backoff, then dead-letters
        }
    }
}
