package com.mercury.recommendation.config;

import com.mercury.recommendation.exception.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

/** Consuming the order events: bounded retries with backoff, then a dead-letter topic; malformed events skip the retries. */
@Configuration
@EnableConfigurationProperties(RecommendationProperties.class)
public class KafkaConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public NewTopic orderEventsDeadLetterTopic(RecommendationProperties properties) {
        return TopicBuilder.name(properties.kafka().dlqTopic()).partitions(1).replicas(1).build();
    }

    @Bean
    public CommonErrorHandler errorHandler(
            KafkaTemplate<String, String> template, RecommendationProperties properties,
            @Value("${recommendation.kafka.retry.max-attempts:4}") int maxAttempts,
            @Value("${recommendation.kafka.retry.initial-interval:500ms}") Duration initialInterval,
            @Value("${recommendation.kafka.retry.max-interval:5s}") Duration maxInterval) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, exception) -> new TopicPartition(properties.kafka().dlqTopic(), -1));
        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval.toMillis(), 2.0);
        backOff.setMaxInterval(maxInterval.toMillis());
        backOff.setMaxAttempts(Math.max(0, maxAttempts - 1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
