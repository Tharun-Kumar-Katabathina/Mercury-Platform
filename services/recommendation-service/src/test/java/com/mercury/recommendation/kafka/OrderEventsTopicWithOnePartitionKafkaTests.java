package com.mercury.recommendation.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other order of events at start-up: the topic is already there with ONE partition, which is how the broker creates it for
 * whoever asks first. A producer's first message is enough, and the Order Service does not declare the topic it publishes to.
 * The service's declaration then adds the missing partitions, still before its consumer starts, so here too the consumer never
 * has a share of fewer than three. (The fresh broker, and what is consumed, are in OrderEventsTopicKafkaTests.)
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "recommendation.kafka.enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        // a database of its own, for the reason given in OrderEventsTopicKafkaTests: this context is closed with the class
        "spring.datasource.url=jdbc:h2:mem:order-events-topic-with-one-partition;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@Import(RecordedAssignments.class)
@DirtiesContext     // closed with the class, while its broker is still there: a consumer stopped later, without one, waits out its timeouts
class OrderEventsTopicWithOnePartitionKafkaTests {

    private static final String TOPIC = "mercury.order.events";

    @Container
    static final KafkaTestBroker KAFKA = new KafkaTestBroker();

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) throws Exception {
        KAFKA.topics();                                 // until the broker answers
        KAFKA.createTopic(TOPIC, 1);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        registry.add("recommendation.kafka.topic", () -> TOPIC);
    }

    @Autowired private RecordedAssignments assignments;

    @Test
    void aTopicThatIsAlreadyThereWithOnePartitionHasItsThreeBeforeTheConsumerTakesAShareOfIt() throws Exception {
        List<Set<Integer>> shares = assignments.sharesOnceTheConsumerHasOne();

        assertThat(KAFKA.partitionsOf(TOPIC)).isEqualTo(3);
        assertThat(shares).allSatisfy(share -> assertThat(share).containsExactly(0, 1, 2));
    }
}
