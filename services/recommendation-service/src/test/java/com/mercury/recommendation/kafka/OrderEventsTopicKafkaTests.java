package com.mercury.recommendation.kafka;

import com.mercury.recommendation.service.FeatureStore;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.mercury.recommendation.kafka.RecordedAssignments.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The order events topic on a FRESH broker, as in CI and in any new environment: nothing has created it yet.
 * <p>
 * A consumer that subscribes to a topic that does not exist makes the broker create it, with ONE partition (the broker's
 * default). The Notification Service declares the same topic with three; when it does so a moment later, a consumer that is
 * already there keeps its one partition until its next metadata refresh, five minutes on, and until then every order whose
 * events are on the other two partitions teaches this service nothing. That failed the platform's smoke test ("the
 * recommendation service did not learn from order ..."). So this service declares the topic itself, which happens before its
 * consumer starts: these tests hold it to that.
 * <p>
 * Unlike the other Kafka tests in this repository, this one must NOT create the topic for the service: that is the point.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // the service as it really runs: consuming, and declaring its topics. The test properties switch both off, for the
        // tests that have no broker
        "recommendation.kafka.enabled=true",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        // a database of its own, because this context is closed with the class (below). The other tests share one in-memory
        // database, and it does not survive the closing of the context that created it: H2 checks a table's CHECK constraint
        // with the connection that created the table
        "spring.datasource.url=jdbc:h2:mem:order-events-topic;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@Import(RecordedAssignments.class)
@DirtiesContext     // closed with the class, while its broker is still there: a consumer stopped later, without one, waits out its timeouts
class OrderEventsTopicKafkaTests {

    @Container
    static final KafkaTestBroker KAFKA = new KafkaTestBroker();

    private static Set<String> topicsBeforeTheServiceStarted;

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        topicsBeforeTheServiceStarted = KAFKA.topics();
        registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
    }

    @Autowired private RecordedAssignments assignments;
    @Autowired private KafkaTemplate<String, String> producer;
    @Autowired private ConsumerFactory<?, ?> consumers;
    @Autowired private FeatureStore store;
    @Value("${recommendation.kafka.topic}") private String topic;

    @Test
    void theServiceCreatesTheTopicWithThreePartitionsBeforeItsConsumerTakesAShareOfIt() throws Exception {
        assertThat(topicsBeforeTheServiceStarted).as("a fresh broker: only the service can have created the topic").doesNotContain(topic);

        List<Set<Integer>> shares = assignments.sharesOnceTheConsumerHasOne();

        assertThat(KAFKA.partitionsOf(topic)).isEqualTo(3);
        // the consumer found all three the first time it looked: it has never had a share of fewer
        assertThat(shares).allSatisfy(share -> assertThat(share).containsExactly(0, 1, 2));
    }

    @Test
    void ordersOnEveryPartitionAreLearnedFromAtOnceAlsoAfterTheNotificationServiceHasDeclaredTheTopic() throws Exception {
        assignments.sharesOnceTheConsumerHasOne();
        // what the Notification Service does when it starts a moment after this service: it declares the same topic with three
        // partitions. Where the topic had fewer, this adds the missing ones under a consumer that is already subscribed
        new KafkaAdmin(Map.of("bootstrap.servers", KAFKA.bootstrapServers()))
                .createOrModifyTopics(TopicBuilder.name(topic).partitions(3).replicas(1).build());

        List<Order> orders = new ArrayList<>();
        for (int partition = 0; partition < 3; partition++) {
            Order order = new Order(UUID.randomUUID(), "customer-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            orders.add(order);
            // keyed by the order as the Order Service does, so both events of an order are on one partition, in this order
            send(partition, order.id(), created(order));
            send(partition, order.id(), confirmed(order));
        }

        await().atMost(PATIENCE).untilAsserted(() -> {
            for (int partition = 0; partition < 3; partition++) {
                Order order = orders.get(partition);
                assertThat(store.purchasedBy(order.customer())).as("the order on partition %d", partition)
                        .containsExactlyInAnyOrder(order.first(), order.second());
            }
        });
        // and not because the consumer now looks for new partitions more often: that interval is still Kafka's own five minutes
        assertThat(consumers.getConfigurationProperties()).doesNotContainKey(ConsumerConfig.METADATA_MAX_AGE_CONFIG);
    }

    private record Order(UUID id, String customer, UUID first, UUID second) { }

    private void send(int partition, UUID orderId, String event) throws Exception {
        producer.send(new ProducerRecord<>(topic, partition, orderId.toString(), event)).get(10, TimeUnit.SECONDS);
    }

    private static String created(Order order) {
        return ("{\"eventId\":\"%s\",\"eventType\":\"OrderCreated\",\"occurredAt\":\"2026-10-06T10:00:00Z\",\"orderId\":\"%s\","
                + "\"items\":[{\"productId\":\"%s\",\"quantity\":1},{\"productId\":\"%s\",\"quantity\":1}],\"totalAmount\":10,\"customerId\":\"%s\"}")
                .formatted(UUID.randomUUID(), order.id(), order.first(), order.second(), order.customer());
    }

    private static String confirmed(Order order) {
        return "{\"eventId\":\"%s\",\"eventType\":\"OrderConfirmed\",\"occurredAt\":\"2026-10-06T10:00:01Z\",\"orderId\":\"%s\"}"
                .formatted(UUID.randomUUID(), order.id());
    }
}
