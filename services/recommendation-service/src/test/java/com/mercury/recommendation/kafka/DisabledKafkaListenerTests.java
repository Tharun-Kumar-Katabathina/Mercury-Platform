package com.mercury.recommendation.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestContextManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The tests run with Kafka switched off. The test framework keeps the contexts of the test classes in a cache, pauses a context
 * that no test class is using any more when a test class that needs another one is run, and restarts it when a test class needs
 * it again; what it restarts must not include the Kafka listener that is switched off, whose consumer would connect to
 * localhost:9092.
 * <p>
 * The pause and the restart here are the framework's own: this test is three test classes, one after the other, in the order that
 * makes the framework do it, whichever order the test classes of the module run in.
 */
class DisabledKafkaListenerTests {

    /** The context of the application with Kafka switched off, as the tests have it. Its own, because it is paused and restarted. */
    @SpringBootTest(properties = "recommendation.qdrant.sync-interval=2h")
    static class KafkaIsOff {
    }

    /** Any other context: the test class that needs it is what makes the framework pause the one before. */
    @ContextConfiguration(classes = Nothing.class)
    static class SomethingElse {
    }

    @Configuration
    static class Nothing {
    }

    @Test
    void aContextThatTheTestFrameworkPausesAndRestartsDoesNotStartTheListenerThatIsSwitchedOff() throws Exception {
        TestContextManager kafkaIsOff = new TestContextManager(KafkaIsOff.class);
        TestContextManager somethingElse = new TestContextManager(SomethingElse.class);
        try {
            // the first test class, with Kafka switched off, runs and is done: its context stays in the cache, unused
            ConfigurableApplicationContext context = (ConfigurableApplicationContext) kafkaIsOff.getTestContext().getApplicationContext();
            KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);
            assertThat(context.getBeansOfType(DisabledListenersStayStopped.class))
                    .as("the configuration is scanned in, in a context where Kafka is switched off").hasSize(1);
            assertThat(registry.getListenerContainers()).as("the listener is there to be kept stopped").isNotEmpty();
            assertThat(registry.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);
            kafkaIsOff.afterTestClass();

            // a second test class needs another context: the framework pauses the unused one
            somethingElse.getTestContext().getApplicationContext();
            somethingElse.afterTestClass();
            assumeFalse(context.isRunning(), "the framework did not pause the context (spring.test.context.cache.pause=never?)");

            // a third test class needs the first one's context again: the framework restarts it
            kafkaIsOff.getTestContext().getApplicationContext();
            assertThat(context.isRunning()).as("the framework restarted the context").isTrue();
            assertThat(registry.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);
        } finally {
            kafkaIsOff.getTestContext().markApplicationContextDirty(null);          // closed, as a context of this test only
            somethingElse.getTestContext().markApplicationContextDirty(null);
        }
    }
}
