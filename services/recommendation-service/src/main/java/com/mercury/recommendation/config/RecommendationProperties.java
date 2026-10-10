package com.mercury.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** recommendation.*: the vector index, caching and scoring knobs. */
@ConfigurationProperties(prefix = "recommendation")
public record RecommendationProperties(
        @DefaultValue Qdrant qdrant,
        @DefaultValue Cache cache,
        @DefaultValue Scoring scoring,
        @DefaultValue Kafka kafka
) {

    public record Qdrant(
            @DefaultValue("http://localhost:6333") String url,
            @DefaultValue("products") String collection,
            /** length of every product vector (co-purchase profile hashed into this many dimensions) */
            @DefaultValue("64") int dimensions,
            @DefaultValue("2s") Duration timeout,
            /** how often products with changed vectors are pushed to the index */
            @DefaultValue("5s") Duration syncInterval,
            @DefaultValue("200") int syncBatch
    ) {
    }

    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5m") Duration similarTtl,
            @DefaultValue("60s") Duration userTtl,
            @DefaultValue("2m") Duration popularTtl
    ) {
    }

    public record Scoring(
            @DefaultValue("1") double viewWeight,
            @DefaultValue("3") double cartWeight,
            @DefaultValue("5") double purchaseWeight,
            /** an interaction counts half as much after this many days */
            @DefaultValue("30") double halfLifeDays,
            /** how many of a customer's strongest products seed their recommendations */
            @DefaultValue("8") int seedProducts,
            @DefaultValue("50") int maxLimit
    ) {
    }

    public record Kafka(
            @DefaultValue("mercury.order.events") String topic,
            /** how many partitions the topic is declared with: the same number as in the Notification Service, which declares it too */
            @DefaultValue("3") int topicPartitions,
            @DefaultValue("mercury.order.events.recommendation.dlq") String dlqTopic
    ) {
    }
}
