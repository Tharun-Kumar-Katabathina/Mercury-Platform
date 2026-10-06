package com.mercury.notification.kafka;

import com.mercury.notification.service.NotificationEventHandler;
import com.mercury.notification.service.NotificationMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

    private final NotificationEventHandler handler;
    private final NotificationMetrics metrics;
    private final AfterProcessingHook afterProcessing;

    public OrderEventListener(
            NotificationEventHandler handler, NotificationMetrics metrics, AfterProcessingHook afterProcessing) {
        this.handler = handler;
        this.metrics = metrics;
        this.afterProcessing = afterProcessing;
    }

    @KafkaListener(topics = "${notification.topic}", groupId = "${spring.kafka.consumer.group-id}")
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
