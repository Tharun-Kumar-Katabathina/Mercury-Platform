package com.mercury.inventory.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Runs after a command was fully processed and committed, but BEFORE Kafka is told the record is
 * done. Does nothing in production; lets tests simulate a consumer that crashes at exactly that
 * point and prove the redelivery has no second effect.
 */
@FunctionalInterface
public interface AfterProcessingHook {

    void afterProcessed(ConsumerRecord<String, String> record);

    AfterProcessingHook NONE = record -> { };
}
