package com.mercury.recommendation.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;

/**
 * Every assignment the service's consumer is given, oldest first. Spring Boot hands a bean of this type to the listener
 * container, so a test only has to import this class into its context.
 */
class RecordedAssignments implements ConsumerAwareRebalanceListener {

    /** far less than the five minutes after which a consumer looks for new partitions by itself */
    static final Duration PATIENCE = Duration.ofSeconds(60);

    private final List<Set<Integer>> assignments = new CopyOnWriteArrayList<>();

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        assignments.add(partitions.stream().map(TopicPartition::partition).collect(Collectors.toCollection(TreeSet::new)));
    }

    /**
     * Waits until the consumer has a share of its topic, and returns every share it has had so far, as partition numbers.
     * An empty assignment is not a share and is left out: a group can form an instant before its consumer has heard of the
     * topic, and is then corrected at once. A share of SOME of the partitions is what must never be in here.
     */
    List<Set<Integer>> sharesOnceTheConsumerHasOne() {
        await().atMost(PATIENCE).until(() -> assignments.stream().anyMatch(assignment -> !assignment.isEmpty()));
        return assignments.stream().filter(assignment -> !assignment.isEmpty()).toList();
    }
}
