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

    /**
     * The topic this service consumes, declared here as every consumer in the platform declares its own. The declaration is
     * applied before the listener starts, so the consumer finds every partition the first time it looks.
     * <p>
     * Without it, a consumer that subscribes on a fresh broker makes the broker create the topic with ONE partition (the
     * broker's default). When the Notification Service then declares it with three, the consumer that is already there keeps
     * its one partition until its next metadata refresh, five minutes later, and until then learns nothing from the orders
     * whose events are on the other two.
     */
    @Bean
    public NewTopic orderEventsTopic(RecommendationProperties properties) {
        RecommendationProperties.Kafka kafka = properties.kafka();
        return TopicBuilder.name(kafka.topic()).partitions(kafka.topicPartitions()).replicas(1).build();
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
