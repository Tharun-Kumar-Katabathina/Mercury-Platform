package com.mercury.recommendation;

import com.mercury.recommendation.dto.RecommendedProduct;
import com.mercury.recommendation.service.FeatureStore;
import com.mercury.recommendation.service.IndexSync;
import com.mercury.recommendation.service.RecommendationService;
import com.mercury.recommendation.service.VectorIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole path against a REAL Qdrant and a REAL Redis: purchases -> features -> vectors in the index ->
 * similarity search -> cached answers; and what happens when the index or the cache disappears.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "recommendation.cache.enabled=true")
class RecommendationPipelineTests {

    @Container static final GenericContainer<?> QDRANT = new GenericContainer<>(DockerImageName.parse("qdrant/qdrant:v1.19.1")).withExposedPorts(6333);
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        registry.add("recommendation.qdrant.url", () -> "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
        registry.add("recommendation.qdrant.collection", () -> "products-" + UUID.randomUUID().toString().substring(0, 8));
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private FeatureStore store;
    @Autowired private IndexSync sync;
    @Autowired private VectorIndex index;
    @Autowired private RecommendationService recommendations;
    @Autowired private ScheduledTaskHolder scheduler;

    /** These tests count what THEIR OWN pass pushed, so none of them starts while the scheduler's first pass could still take that work. */
    @BeforeEach
    void afterTheStartupSync() {
        Waiting.untilTheStartupSyncIsOver(scheduler);
    }

    /** two phone cases bought with the same accessories, and an unrelated product bought with other things */
    private record Catalogue(UUID caseA, UUID caseB, UUID charger, UUID cable, UUID screenProtector, UUID kettle, UUID toaster) {
        static Catalogue create() {
            return new Catalogue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        }
    }

    private Catalogue shop() {
        Catalogue c = Catalogue.create();
        Instant now = Instant.now();
        for (int i = 0; i < 4; i++) {
            store.recordPurchase("alice-" + i, List.of(c.caseA(), c.charger(), c.cable(), c.screenProtector()), now);
            store.recordPurchase("bob-" + i, List.of(c.caseB(), c.charger(), c.cable(), c.screenProtector()), now);
            store.recordPurchase("carol-" + i, List.of(c.kettle(), c.toaster()), now);
        }
        assertThat(sync.runOnce()).isGreaterThanOrEqualTo(7);
        return c;
    }

    @Test
    void productsBoughtWithTheSameCompanionsAreFoundAsSimilar() {
        Catalogue c = shop();

        List<RecommendedProduct> similar = recommendations.similar(c.caseA(), 3);

        assertThat(similar).isNotEmpty();
        assertThat(similar.get(0).productId()).isEqualTo(c.caseB());          // the other phone case, though never bought together
        assertThat(similar).extracting(RecommendedProduct::productId).doesNotContain(c.caseA(), c.kettle(), c.toaster());
    }

    @Test
    void theIndexHoldsAVectorForEveryProductThatHasHistory() {
        shop();

        assertThat(index.indexedCount()).isGreaterThanOrEqualTo(7);
        assertThat(store.dirtyProducts(1000)).isEmpty();
    }

    @Test
    void aSecondAskIsServedFromTheCache() {
        Catalogue c = shop();
        List<RecommendedProduct> first = recommendations.similar(c.caseA(), 3);

        List<RecommendedProduct> second = recommendations.similar(c.caseA(), 3);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void whenTheIndexIsDownNothingIsLostAndTheNextRunCatchesUp() {
        Catalogue c = Catalogue.create();
        store.recordPurchase("dave", List.of(c.caseA(), c.charger()), Instant.now());
        QDRANT.getDockerClient().pauseContainerCmd(QDRANT.getContainerId()).exec();
        int pushedWhileDown;
        try {
            pushedWhileDown = sync.runOnce();
        } finally {
            QDRANT.getDockerClient().unpauseContainerCmd(QDRANT.getContainerId()).exec();
        }

        assertThat(pushedWhileDown).isZero();
        assertThat(store.dirtyProducts(1000)).contains(c.caseA(), c.charger());   // still waiting, not forgotten
        assertThat(sync.runOnce()).isGreaterThanOrEqualTo(2);
        assertThat(store.dirtyProducts(1000)).doesNotContain(c.caseA(), c.charger());
    }

    @Test
    void whenRedisIsDownRecommendationsAreStillComputed() {
        Catalogue c = shop();
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertThat(recommendations.boughtTogether(c.caseA(), 5)).isNotEmpty();
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }
}
