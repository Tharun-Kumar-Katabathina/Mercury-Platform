package com.mercury.recommendation.service;

import com.mercury.recommendation.config.RecommendationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** The product vectors in Qdrant (REST API): create the collection once, upsert vectors, search the nearest. */
@Component
public class VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(VectorIndex.class);

    private static final String DISTANCE = "Cosine";
    /** between two looks at a collection that Qdrant cannot show yet: see {@link #confirmCreatedByAnother} */
    private static final Duration CONFIRMATION_PAUSE = Duration.ofMillis(20);

    private final RestClient http;
    private final String collection;
    private final int dimensions;
    private final Duration timeout;
    private final AtomicBoolean ready = new AtomicBoolean();
    /** the initialization that is under way, if any: whoever arrives meanwhile waits for it instead of starting another */
    private final AtomicReference<CompletableFuture<Void>> initializing = new AtomicReference<>();

    public VectorIndex(RecommendationProperties properties) {
        RecommendationProperties.Qdrant q = properties.qdrant();
        this.collection = q.collection();
        this.dimensions = q.dimensions();
        this.timeout = q.timeout();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(q.timeout()).build());
        factory.setReadTimeout(q.timeout());
        this.http = RestClient.builder().baseUrl(q.url()).requestFactory(factory).build();
    }

    public record Match(UUID productId, double score) { }

    /**
     * Creates the collection if it is not there. Safe to call repeatedly and from several threads at once; cheap once
     * it succeeded.
     *
     * The sync and the first requests all arrive here together when the service starts. In this instance one of them
     * does the work and the others wait for its outcome, success or failure, instead of each checking and creating on
     * its own. A failure is not remembered: the next call tries again.
     */
    public void ensureCollection() {
        if (ready.get()) {
            return;
        }
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> underWay = initializing.compareAndExchange(null, mine);
        if (underWay != null) {
            try {
                underWay.join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException failure ? failure : e;
            }
            return;
        }
        try {
            if (!ready.get()) {                                     // it may have finished just before this turn was taken
                initialize();
                ready.set(true);
            }
            mine.complete(null);
        } catch (Throwable e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            initializing.compareAndSet(mine, null);
        }
    }

    /**
     * Another INSTANCE can create the collection between our check and our create; Qdrant then refuses ours with 409.
     * It refuses with 409 whatever the existing collection holds, so that answer alone proves nothing: it is taken as
     * success only once the collection is confirmed to be there with the vectors this service writes.
     */
    private void initialize() {
        if (existsAsExpected()) {
            return;
        }
        try {
            http.put().uri("/collections/{c}", collection)
                    .body(Map.of("vectors", Map.of("size", dimensions, "distance", DISTANCE)))
                    .retrieve().toBodilessEntity();
            log.info("created the vector collection {} ({} dimensions, cosine)", collection, dimensions);
        } catch (HttpClientErrorException.Conflict refused) {
            confirmCreatedByAnother(refused);
            log.info("the vector collection {} was created by another instance at the same moment", collection);
        }
    }

    /**
     * The look at the collection after Qdrant refused our create. For a few milliseconds Qdrant cannot show the
     * collection the other instance is still creating, and answers 5xx. That answer, and only that one, is waited
     * out: the look is repeated after a short pause, for no longer than one request to Qdrant may take, and when that
     * time is up the last 5xx is thrown. Everything else ends it at once: the expected collection (success), one that
     * holds other vectors, no collection at all (the refusal then stands), and any other error.
     */
    private void confirmCreatedByAnother(HttpClientErrorException.Conflict refused) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                if (existsAsExpected()) {
                    return;
                }
                throw refused;
            } catch (HttpServerErrorException notShownYet) {
                if (deadline - System.nanoTime() < CONFIRMATION_PAUSE.toNanos() || !paused()) {
                    throw notShownYet;
                }
            }
        }
    }

    /** @return false when the thread was interrupted instead of pausing */
    private static boolean paused() {
        try {
            Thread.sleep(CONFIRMATION_PAUSE);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** @return false when there is no such collection; fails when there is one that holds other vectors than ours */
    private boolean existsAsExpected() {
        JsonNode vectors;
        try {
            vectors = http.get().uri("/collections/{c}", collection).retrieve().body(JsonNode.class)
                    .path("result").path("config").path("params").path("vectors");
        } catch (HttpClientErrorException.NotFound missing) {
            return false;
        }
        if (vectors.path("size").asInt(-1) != dimensions || !DISTANCE.equalsIgnoreCase(vectors.path("distance").asString(""))) {
            throw new IllegalStateException("the vector collection " + collection + " holds " + vectors
                    + " but this service writes " + dimensions + "-dimensional " + DISTANCE + " vectors");
        }
        return true;
    }

    public void upsert(UUID productId, float[] vector) {
        ensureCollection();
        List<Float> values = new ArrayList<>(vector.length);
        for (float f : vector) {
            values.add(f);
        }
        http.put().uri("/collections/{c}/points?wait=true", collection)
                .body(Map.of("points", List.of(Map.of("id", productId.toString(), "vector", values,
                        "payload", Map.of("productId", productId.toString())))))
                .retrieve().toBodilessEntity();
    }

    /** The products whose vectors are nearest to the given product's own (the product itself excluded). */
    public List<Match> similarTo(UUID productId, int limit) {
        ensureCollection();
        JsonNode body;
        try {
            body = http.post().uri("/collections/{c}/points/query", collection)
                    .body(Map.of("query", productId.toString(), "limit", limit, "with_payload", false,
                            "filter", Map.of("must_not", List.of(Map.of("has_id", List.of(productId.toString()))))))
                    .retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest notIndexed) {
            return List.of();                                    // this product has no vector yet
        }
        List<Match> matches = new ArrayList<>();
        for (JsonNode point : body.path("result").path("points")) {
            matches.add(new Match(UUID.fromString(point.path("id").asString()), point.path("score").asDouble()));
        }
        return matches;
    }

    public long indexedCount() {
        ensureCollection();
        return http.post().uri("/collections/{c}/points/count", collection).body(Map.of("exact", true))
                .retrieve().body(JsonNode.class).path("result").path("count").asLong();
    }
}
