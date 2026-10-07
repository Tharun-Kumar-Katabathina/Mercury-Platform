package com.mercury.recommendation;

import com.mercury.recommendation.config.RecommendationProperties;
import com.mercury.recommendation.service.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingServiceTests {

    private final EmbeddingService embeddings = new EmbeddingService(new RecommendationProperties(
            new RecommendationProperties.Qdrant("http://x", "products", 64, null, null, 10), null, null, null));

    private static Map<UUID, Double> profile(UUID[] companions, double... counts) {
        Map<UUID, Double> row = new LinkedHashMap<>();
        for (int i = 0; i < companions.length; i++) {
            row.put(companions[i], counts[i]);
        }
        return row;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;     // both are unit length
    }

    private static UUID[] ids(int n) {
        UUID[] ids = new UUID[n];
        for (int i = 0; i < n; i++) {
            ids[i] = UUID.nameUUIDFromBytes(("companion-" + i).getBytes());
        }
        return ids;
    }

    @Test
    void aProductWithNoHistoryHasNoVector() {
        assertThat(embeddings.embed(Map.of())).isEmpty();
    }

    @Test
    void vectorsAreUnitLengthAndDeterministic() {
        UUID[] c = ids(6);
        float[] first = embeddings.embed(profile(c, 5, 3, 2, 1, 1, 1)).orElseThrow();
        float[] again = embeddings.embed(profile(c, 5, 3, 2, 1, 1, 1)).orElseThrow();

        assertThat(first).hasSize(64).containsExactly(again);
        double norm = 0;
        for (float x : first) norm += x * x;
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void productsBoughtWithTheSameCompanionsAreCloserThanProductsBoughtWithDifferentOnes() {
        UUID[] c = ids(40);
        UUID[] shared = java.util.Arrays.copyOfRange(c, 0, 10);
        UUID[] other = java.util.Arrays.copyOfRange(c, 20, 30);
        double[] counts = {4, 3, 3, 2, 2, 2, 1, 1, 1, 1};

        float[] a = embeddings.embed(profile(shared, counts)).orElseThrow();
        float[] b = embeddings.embed(profile(shared, 3, 3, 4, 2, 2, 1, 2, 1, 1, 1)).orElseThrow();   // same companions, similar counts
        float[] z = embeddings.embed(profile(other, counts)).orElseThrow();                          // entirely different companions

        assertThat(cosine(a, b)).isGreaterThan(0.9);
        assertThat(cosine(a, z)).isLessThan(0.5);
    }
}
