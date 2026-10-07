package com.mercury.recommendation.service;

import com.mercury.recommendation.config.RecommendationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** The product vectors in Qdrant (REST API): create the collection once, upsert vectors, search the nearest. */
@Component
public class VectorIndex {

    private static final Logger log = LoggerFactory.getLogger(VectorIndex.class);

    private final RestClient http;
    private final String collection;
    private final int dimensions;
    private final AtomicBoolean ready = new AtomicBoolean();

    public VectorIndex(RecommendationProperties properties) {
        RecommendationProperties.Qdrant q = properties.qdrant();
        this.collection = q.collection();
        this.dimensions = q.dimensions();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(q.timeout()).build());
        factory.setReadTimeout(q.timeout());
        this.http = RestClient.builder().baseUrl(q.url()).requestFactory(factory).build();
    }

    public record Match(UUID productId, double score) { }

    /** Creates the collection if it is not there. Safe to call repeatedly; cheap once it succeeded. */
    public void ensureCollection() {
        if (ready.get()) {
            return;
        }
        try {
            http.get().uri("/collections/{c}", collection).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound missing) {
            http.put().uri("/collections/{c}", collection)
                    .body(Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")))
                    .retrieve().toBodilessEntity();
            log.info("created the vector collection {} ({} dimensions, cosine)", collection, dimensions);
        }
        ready.set(true);
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
