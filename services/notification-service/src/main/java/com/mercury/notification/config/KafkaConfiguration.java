package com.mercury.notification.config;

import com.mercury.notification.event.InvalidEventException;
import com.mercury.notification.kafka.AfterProcessingHook;
import com.mercury.notification.service.NotificationMetrics;
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

import java.time.Clock;
import java.time.Duration;

@Configuration
public class KafkaConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public AfterProcessingHook afterProcessingHook() {
        return AfterProcessingHook.NONE;
    }

    @Bean
    public NewTopic orderEventsTopic(
            @Value("${notification.topic}") String topic,
            @Value("${notification.topic-partitions}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsDeadLetterTopic(@Value("${notification.dlq-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }

    /**
     * A failing record is retried a bounded number of times with growing delays; a record that can
     * never succeed (malformed) skips the retries. Either way it ends in the dead-letter topic instead
     * of blocking the partition, and the consumer moves on.
     */
    @Bean
    public CommonErrorHandler errorHandler(
            KafkaTemplate<String, String> template,
            NotificationMetrics metrics,
            @Value("${notification.dlq-topic}") String dlqTopic,
            @Value("${notification.retry.max-attempts}") int maxAttempts,
            @Value("${notification.retry.initial-interval}") Duration initialInterval,
            @Value("${notification.retry.multiplier}") double multiplier,
            @Value("${notification.retry.max-interval}") Duration maxInterval) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, exception) -> new TopicPartition(dlqTopic, -1));

        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), multiplier);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxAttempts(Math.max(0, maxAttempts - 1));   // retries after the first attempt

        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            metrics.deadLettered();
            recoverer.accept(record, exception);
        }, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
