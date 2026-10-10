package com.mercury.recommendation.kafka;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * In the tests Kafka is switched off ({@code recommendation.kafka.enabled=false}), which only makes the listener's container
 * not start with the context. A test context that is used again after another one was loaded is paused and restarted by the
 * test framework, and the restart starts the registry of the containers, which then starts every container it holds, the
 * switched-off one too, unless it is told to start only the ones that start with the context. The container's consumer would
 * connect to localhost:9092: to whatever broker is there, such as the one of the development stack, join the consumer group
 * and take its events.
 * <p>
 * It is scanned in like any other class of the tests, and applies to the contexts where Kafka is switched off only: the tests
 * that run against a broker of their own switch it on, and keep their listener's usual behaviour.
 */
@Configuration
@ConditionalOnProperty(name = "recommendation.kafka.enabled", havingValue = "false")
class DisabledListenersStayStopped {

    @Autowired
    void startOnlyTheListenersThatStartWithTheContext(KafkaListenerEndpointRegistry registry) {
        registry.setAlwaysStartAfterRefresh(false);
    }
}
