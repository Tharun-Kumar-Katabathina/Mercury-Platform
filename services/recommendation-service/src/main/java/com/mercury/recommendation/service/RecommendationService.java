package com.mercury.recommendation.service;

import com.mercury.recommendation.config.RecommendationProperties;
import com.mercury.recommendation.dto.RecommendedProduct;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The three recommendation questions:
 *  - similar products: nearest neighbours of a product's vector in the index (bought with the same companions);
 *  - bought together: the products most often in the same order as this one (straight from the counts);
 *  - for a customer: their strongest products (decayed by age) each contribute their similar and bought-together
 *    products, weighted by the customer's affinity; products already purchased are left out; popular products fill
 *    in when there is not enough history (the cold start).
 */
@Service
public class RecommendationService {

    private static final TypeReference<List<RecommendedProduct>> LIST = new TypeReference<>() { };

    private final FeatureStore store;
    private final VectorIndex index;
    private final RecommendationCache cache;
    private final RecommendationProperties properties;

    public RecommendationService(FeatureStore store, VectorIndex index, RecommendationCache cache, RecommendationProperties properties) {
        this.store = store;
        this.index = index;
        this.cache = cache;
        this.properties = properties;
    }

    public List<RecommendedProduct> similar(UUID productId, int limit) {
        int n = bounded(limit);
        return cache.through("rec:similar:" + productId + ":" + n, properties.cache().similarTtl(), LIST,
                () -> index.similarTo(productId, n).stream().map(m -> new RecommendedProduct(m.productId(), m.score())).toList());
    }

    public List<RecommendedProduct> boughtTogether(UUID productId, int limit) {
        int n = bounded(limit);
        return cache.through("rec:together:" + productId + ":" + n, properties.cache().similarTtl(), LIST,
                () -> store.boughtTogether(productId, n).stream().map(s -> new RecommendedProduct(s.productId(), s.score())).toList());
    }

    public List<RecommendedProduct> popular(int limit) {
        int n = bounded(limit);
        return cache.through("rec:popular:" + n, properties.cache().popularTtl(), LIST,
                () -> store.popular(n).stream().map(s -> new RecommendedProduct(s.productId(), s.score())).toList());
    }

    public List<RecommendedProduct> forUser(String userId, int limit) {
        int n = bounded(limit);
        return cache.through("rec:user:" + userId + ":" + n, properties.cache().userTtl(), LIST, () -> computeForUser(userId, n));
    }

    private List<RecommendedProduct> computeForUser(String userId, int n) {
        Set<UUID> owned = new HashSet<>(store.purchasedBy(userId));
        Map<UUID, Double> candidates = new LinkedHashMap<>();
        for (FeatureStore.Affinity seed : store.topAffinities(userId, properties.scoring().seedProducts())) {
            if (seed.purchased()) {
                owned.add(seed.productId());
            }
            for (RecommendedProduct r : similar(seed.productId(), n)) {
                candidates.merge(r.productId(), seed.score() * r.score(), Double::sum);
            }
            double strongest = store.boughtTogether(seed.productId(), 1).stream().mapToDouble(FeatureStore.Scored::score).findFirst().orElse(1);
            for (FeatureStore.Scored s : store.boughtTogether(seed.productId(), n)) {
                candidates.merge(s.productId(), seed.score() * (s.score() / strongest), Double::sum);
            }
        }
        candidates.keySet().removeAll(owned);

        List<RecommendedProduct> ranked = new ArrayList<>(candidates.entrySet().stream()
                .map(e -> new RecommendedProduct(e.getKey(), e.getValue()))
                .sorted((a, b) -> Double.compare(b.score(), a.score())).limit(n).toList());

        if (ranked.size() < n) {                                     // not enough history: pad with what is popular
            Set<UUID> have = new HashSet<>(owned);
            ranked.forEach(r -> have.add(r.productId()));
            for (FeatureStore.Scored s : store.popular(n + have.size())) {
                if (ranked.size() >= n) break;
                if (have.add(s.productId())) {
                    ranked.add(new RecommendedProduct(s.productId(), 0));
                }
            }
        }
        return ranked;
    }

    private int bounded(int limit) {
        return Math.min(Math.max(limit, 1), properties.scoring().maxLimit());
    }
}
