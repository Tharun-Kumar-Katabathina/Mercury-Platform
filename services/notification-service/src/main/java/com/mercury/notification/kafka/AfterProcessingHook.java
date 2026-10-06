package com.mercury.notification.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Runs after an event was fully processed and committed to the database, but BEFORE Kafka is told
 * the record is done. Does nothing in production. It exists so tests can simulate a consumer that
 * crashes at exactly that point and prove the redelivery has no second effect.
 */
@FunctionalInterface
public interface AfterProcessingHook {

    void afterProcessed(ConsumerRecord<String, String> record);

    AfterProcessingHook NONE = record -> { };
}
