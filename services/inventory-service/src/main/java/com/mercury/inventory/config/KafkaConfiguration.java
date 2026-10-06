package com.mercury.inventory.config;

import com.mercury.inventory.exception.InvalidCommandException;
import com.mercury.inventory.kafka.AfterProcessingHook;
import com.mercury.inventory.kafka.InventoryMetrics;
import org.apache.kafka.clients.admin.NewTopic;
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

import java.time.Duration;

@Configuration
public class KafkaConfiguration {

    @Bean
    public AfterProcessingHook afterProcessingHook() {
        return AfterProcessingHook.NONE;
    }

    @Bean
    public NewTopic commandsTopic(
            @Value("${inventory.command-topic}") String topic,
            @Value("${inventory.topic-partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    @Bean
    public NewTopic commandsDeadLetterTopic(@Value("${inventory.command-dlq-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic eventsTopic(
            @Value("${inventory.outbox.topic}") String topic,
            @Value("${inventory.topic-partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    /**
     * A failing command is retried a bounded number of times with growing delays; one that can never
     * succeed (malformed) skips the retries. Either way it ends in the dead-letter topic instead of
     * blocking its partition. A command that was dead-lettered leaves its order waiting; Order
     * Service's recovery deadline then asks Inventory directly and resolves it.
     */
    @Bean
    public CommonErrorHandler errorHandler(
            KafkaTemplate<String, String> template,
            InventoryMetrics metrics,
            @Value("${inventory.command-dlq-topic}") String dlqTopic,
            @Value("${inventory.retry.max-attempts}") int maxAttempts,
            @Value("${inventory.retry.initial-interval}") Duration initialInterval,
            @Value("${inventory.retry.multiplier}") double multiplier,
            @Value("${inventory.retry.max-interval}") Duration maxInterval) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, exception) -> new TopicPartition(dlqTopic, -1));

        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxAttempts(Math.max(0, maxAttempts - 1));

        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            metrics.deadLettered();
            recoverer.accept(record, exception);
        }, backOff);
        handler.addNotRetryableExceptions(InvalidCommandException.class);
        return handler;
    }
}
