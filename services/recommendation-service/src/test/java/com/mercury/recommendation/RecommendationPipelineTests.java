package com.mercury.recommendation;

import com.mercury.recommendation.dto.RecommendedProduct;
import com.mercury.recommendation.service.EmbeddingService;
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
    @Autowired private EmbeddingService embeddings;

    /** These tests count what THEIR OWN pass pushed, so none of them starts while the scheduler's first pass could still take that work. */
    @BeforeEach
    void afterTheStartupSync() {
        Waiting.untilTheStartupSyncIsOver(scheduler);
    }

    /** two phone cases bought with the same accessories, and an unrelated product bought with other things */
    private record Catalogue(UUID caseA, UUID caseB, UUID charger, UUID cable, UUID screenProtector, UUID kettle, UUID toaster) {

        /**
         * The catalogue of the test that asserts WHICH products are nearest. A vector is built by hashing the ids of a
         * product's companions into 64 dimensions, so with random ids two of them sometimes share a dimension, and an
         * unrelated kettle or toaster then lands among a phone case's three nearest (about 0.3% of random catalogues).
         * These seven ids were taken from the real hash so that every one of them falls in a different dimension
         * (the check in the test says what that buys); keep them fixed, do not replace them with random ids.
         */
        static final Catalogue FIXED = new Catalogue(
                UUID.fromString("ca5e0a00-0000-4000-8000-000000000001"), UUID.fromString("ca5e0b00-0000-4000-8000-000000000001"),
                UUID.fromString("c4a26e00-0000-4000-8000-000000000001"), UUID.fromString("cab1e000-0000-4000-8000-000000000001"),
                UUID.fromString("5c2ee000-0000-4000-8000-000000000001"), UUID.fromString("ce771e00-0000-4000-8000-000000000001"),
                UUID.fromString("70a57e00-0000-4000-8000-000000000001"));

        static Catalogue create() {
            return new Catalogue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        }
    }

    /** the tests that only need data in the index, whichever products it holds */
    private Catalogue shop() {
        return shop(Catalogue.create());
    }

    private Catalogue shop(Catalogue c) {
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
        Catalogue c = shop(Catalogue.FIXED);
        // what the fixed ids guarantee, measured with the real embedding on the rows the store holds: the products that were
        // never bought with the phone case's companions are further from it than every accessory it shares companions with
        double weakestAccessory = Math.min(similarity(c.caseA(), c.charger()), Math.min(similarity(c.caseA(), c.cable()), similarity(c.caseA(), c.screenProtector())));
        assertThat(similarity(c.caseA(), c.kettle())).as("kettle vs the weakest accessory (the fixed ids no longer fit the embedding?)").isLessThan(weakestAccessory);
        assertThat(similarity(c.caseA(), c.toaster())).as("toaster vs the weakest accessory (the fixed ids no longer fit the embedding?)").isLessThan(weakestAccessory);

        List<RecommendedProduct> similar = recommendations.similar(c.caseA(), 3);

        assertThat(similar).isNotEmpty();
        assertThat(similar.get(0).productId()).isEqualTo(c.caseB());          // the other phone case, though never bought together
        assertThat(similar).extracting(RecommendedProduct::productId).doesNotContain(c.caseA(), c.kettle(), c.toaster());
    }

    /** cosine of two products' vectors, from the co-purchase rows the store really holds */
    private double similarity(UUID a, UUID b) {
        float[] x = embeddings.embed(store.cooccurrenceRow(a)).orElseThrow();
        float[] y = embeddings.embed(store.cooccurrenceRow(b)).orElseThrow();
        double dot = 0;
        for (int i = 0; i < x.length; i++) {
            dot += (double) x[i] * y[i];
        }
        return dot;
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
