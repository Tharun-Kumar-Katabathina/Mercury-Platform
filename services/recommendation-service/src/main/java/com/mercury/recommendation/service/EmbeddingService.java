package com.mercury.recommendation.service;

import com.mercury.recommendation.config.RecommendationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a product's co-purchase profile (which other products it was bought with, and how often) into a fixed-length
 * vector by feature hashing: each other product adds log(1 + count) to one of N dimensions, with a pseudo-random sign.
 * Two products that are bought with the SAME other products end up with nearby vectors even if they were never bought
 * together, which is the classic item-to-item collaborative-filtering signal, and the vector index finds them fast.
 *
 * Deterministic (same profile, same vector) and free of any training step, so it can be recomputed at any time.
 */
@Component
public class EmbeddingService {

    private final int dimensions;

    public EmbeddingService(RecommendationProperties properties) {
        this.dimensions = properties.qdrant().dimensions();
    }

    /** @return the unit-length vector, or empty when the product has no co-purchase history yet */
    public Optional<float[]> embed(Map<UUID, Double> cooccurrence) {
        if (cooccurrence.isEmpty()) {
            return Optional.empty();
        }
        double[] v = new double[dimensions];
        cooccurrence.forEach((other, score) -> {
            long h = hash(other);
            int index = (int) Long.remainderUnsigned(h >>> 1, dimensions);
            double sign = (h & 1) == 0 ? 1 : -1;
            v[index] += sign * Math.log1p(score);
        });
        double norm = 0;
        for (double x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            return Optional.empty();
        }
        norm = Math.sqrt(norm);
        float[] out = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return Optional.of(out);
    }

    /** FNV-1a 64-bit: stable across JVMs and releases (String.hashCode is not specified to stay the same, this is) */
    static long hash(UUID id) {
        long h = 0xcbf29ce484222325L;
        for (byte b : id.toString().getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
