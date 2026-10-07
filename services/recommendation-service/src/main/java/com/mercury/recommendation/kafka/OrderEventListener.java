package com.mercury.recommendation.kafka;

import com.mercury.recommendation.service.OrderEventHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventListener {

    private final OrderEventHandler handler;
    private final Counter consumed;
    private final Counter failed;

    public OrderEventListener(OrderEventHandler handler, MeterRegistry registry) {
        this.handler = handler;
        this.consumed = Counter.builder("recommendation.events.consumed").register(registry);
        this.failed = Counter.builder("recommendation.events.failed").register(registry);
    }

    @KafkaListener(topics = "${recommendation.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}",
            autoStartup = "${recommendation.kafka.enabled:true}")
    public void onEvent(ConsumerRecord<String, String> record) {
        try {
            handler.handle(record.value());
            consumed.increment();
        } catch (RuntimeException e) {
            failed.increment();
            throw e;                      // the container retries with backoff, then dead-letters
        }
    }
}
