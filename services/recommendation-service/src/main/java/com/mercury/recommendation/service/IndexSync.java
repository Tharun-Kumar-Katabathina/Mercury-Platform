package com.mercury.recommendation.service;

import com.mercury.recommendation.config.RecommendationProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Pushes the vectors of products whose purchase profile changed into the index. The "dirty" flag lives in the same
 * database transaction as the purchase that changed it, so an index outage can delay recommendations but never lose
 * an update: the flag stays set and the next run retries. Upserting the same vector twice is harmless.
 */
@Component
public class IndexSync {

    private static final Logger log = LoggerFactory.getLogger(IndexSync.class);

    private final FeatureStore store;
    private final EmbeddingService embeddings;
    private final VectorIndex index;
    private final RecommendationProperties properties;
    private final Counter synced;
    private final Counter failed;

    public IndexSync(FeatureStore store, EmbeddingService embeddings, VectorIndex index,
                     RecommendationProperties properties, MeterRegistry registry) {
        this.store = store;
        this.embeddings = embeddings;
        this.index = index;
        this.properties = properties;
        this.synced = Counter.builder("recommendation.index.synced").description("product vectors pushed to the index").register(registry);
        this.failed = Counter.builder("recommendation.index.failed").description("index updates that failed and will be retried").register(registry);
    }

    @Scheduled(fixedDelayString = "${recommendation.qdrant.sync-interval:5s}")
    public void run() {
        runOnce();
    }

    /** @return how many vectors were pushed */
    public int runOnce() {
        int pushed = 0;
        for (UUID product : store.dirtyProducts(properties.qdrant().syncBatch())) {
            try {
                Optional<float[]> vector = embeddings.embed(store.cooccurrenceRow(product));
                if (vector.isPresent()) {
                    index.upsert(product, vector.get());
                }
                store.markIndexed(product);
                synced.increment();
                pushed++;
            } catch (RuntimeException e) {
                failed.increment();
                log.warn("index sync of product {} failed, will retry: {}", product, e.toString());
                break;                                              // the index is probably down: stop this round, try again later
            }
        }
        return pushed;
    }
}
