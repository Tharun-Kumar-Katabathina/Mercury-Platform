package com.mercury.recommendation.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The service declares the topic it consumes, so that the topic has its partitions before the consumer starts (a consumer that
 * subscribes first makes the broker create it with one). What the declaration is worth against a real broker is in
 * OrderEventsTopicKafkaTests: this is only that it is there, and where its partition count comes from. Spring's KafkaAdmin
 * applies every NewTopic bean of the context when the service starts.
 */
class OrderEventsTopicDeclarationTests {

    /** the service's Kafka configuration with the service's real application.properties, and nothing else */
    private final ApplicationContextRunner service = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources().addLast(theServicesOwnProperties());
                context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            })
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))        // the error handler publishes dead letters with it
            .withUserConfiguration(KafkaConfiguration.class);

    private static Map<String, Integer> partitionsByTopic(org.springframework.context.ApplicationContext context) {
        return context.getBeansOfType(NewTopic.class).values().stream().collect(Collectors.toMap(NewTopic::name, NewTopic::numPartitions));
    }

    @Test
    void theServiceDeclaresTheTopicItConsumesWithThreePartitionsAndItsDeadLetterTopicWithOne() {
        service.run(context -> {
            assertThat(partitionsByTopic(context))
                    .containsOnly(Map.entry("mercury.order.events", 3), Map.entry("mercury.order.events.recommendation.dlq", 1));
            assertThat(context.getBean("orderEventsTopic", NewTopic.class).replicationFactor()).isEqualTo((short) 1);
        });
    }

    @Test
    void thePartitionCountFollowsTheVariableOfTheNotificationServicesDeclaration() {
        // one variable for the two services that declare this topic: a count of its own here could disagree with the other one,
        // and a topic that grows under a running consumer is the very thing the declaration is there to prevent
        service.withPropertyValues("NOTIFICATION_TOPIC_PARTITIONS=6").run(context ->
                assertThat(partitionsByTopic(context))
                        .containsOnly(Map.entry("mercury.order.events", 6), Map.entry("mercury.order.events.recommendation.dlq", 1)));
    }

    @Test
    void theDeclarationIsOfTheTopicTheServiceIsConfiguredToConsume() {
        service.withPropertyValues("ORDER_EVENTS_TOPIC=some.other.topic").run(context ->
                assertThat(partitionsByTopic(context)).containsEntry("some.other.topic", 3).doesNotContainKey("mercury.order.events"));
    }

    /** The service's application.properties: the test tree has a file of the same name, which hides it from the tests. */
    private static PropertySource<?> theServicesOwnProperties() {
        try {
            for (URL url : Collections.list(KafkaConfiguration.class.getClassLoader().getResources("application.properties"))) {
                if (!url.getPath().contains("/test-classes/")) {
                    return new PropertiesPropertySource("the service's application.properties", PropertiesLoaderUtils.loadProperties(new UrlResource(url)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalStateException("the service's own application.properties is not on the class path");
    }
}
