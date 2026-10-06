package com.mercury.order.config;

import com.mercury.order.inbound.AfterProcessingHook;
import com.mercury.order.inbound.InboundMetrics;
import com.mercury.order.inbound.InvalidInventoryEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import org.apache.kafka.clients.admin.NewTopic;

import java.time.Duration;

/**
 * Consuming Inventory's replies. The service starts and serves its REST API with Kafka down: replies
 * wait in Kafka, and the recovery deadline covers anything that never arrives.
 */
@Configuration
public class InboundKafkaConfiguration {

    @Bean
    public AfterProcessingHook afterProcessingHook() {
        return AfterProcessingHook.NONE;
    }

    @Bean
    public NewTopic inventoryEventsTopic(
            @Value("${order.inbound.topic}") String topic,
            @Value("${order.inbound.topic-partitions:3}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryEventsDeadLetterTopic(@Value("${order.inbound.dlq-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }

    /**
     * A failing event is retried a bounded number of times with growing delays; one that can never
     * succeed (malformed) skips the retries. Either way it ends in the dead-letter topic instead of
     * blocking its partition; the order it belonged to is then resolved by the recovery deadline.
     */
    @Bean
    public CommonErrorHandler inventoryEventErrorHandler(
            KafkaTemplate<String, String> template,
            InboundMetrics metrics,
            @Value("${order.inbound.dlq-topic}") String dlqTopic,
            @Value("${order.inbound.retry.max-attempts:4}") int maxAttempts,
            @Value("${order.inbound.retry.initial-interval:500ms}") Duration initialInterval,
            @Value("${order.inbound.retry.multiplier:2.0}") double multiplier,
            @Value("${order.inbound.retry.max-interval:5s}") Duration maxInterval) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, exception) -> new TopicPartition(dlqTopic, -1));

        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxAttempts(Math.max(0, maxAttempts - 1));

        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            metrics.deadLettered();
            recoverer.accept(record, exception);
        }, backOff);
        handler.addNotRetryableExceptions(InvalidInventoryEventException.class);
        return handler;
    }
}
