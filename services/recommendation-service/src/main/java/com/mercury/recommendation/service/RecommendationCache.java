package com.mercury.recommendation.service;

import com.mercury.recommendation.config.RecommendationProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Redis cache of finished recommendation lists. A Redis failure is a miss, never an error: answers are recomputed. */
@Component
public class RecommendationCache {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCache.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final boolean enabled;
    private final Counter hit;
    private final Counter miss;
    private final Counter error;

    public RecommendationCache(StringRedisTemplate redis, JsonMapper json, RecommendationProperties properties, MeterRegistry registry) {
        this.redis = redis;
        this.json = json;
        this.enabled = properties.cache().enabled();
        this.hit = Counter.builder("recommendation.cache").tag("result", "hit").register(registry);
        this.miss = Counter.builder("recommendation.cache").tag("result", "miss").register(registry);
        this.error = Counter.builder("recommendation.cache").tag("result", "error").register(registry);
    }

    public <T> List<T> through(String key, Duration ttl, TypeReference<List<T>> type, Supplier<List<T>> compute) {
        if (!enabled) {
            return compute.get();
        }
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                hit.increment();
                return json.readValue(cached, type);
            }
            miss.increment();
        } catch (RuntimeException e) {
            fail("read", e);
        }
        List<T> fresh = compute.get();
        try {
            redis.opsForValue().set(key, json.writeValueAsString(fresh), ttl);
        } catch (RuntimeException e) {
            fail("write", e);
        }
        return fresh;
    }

    public void evict(String key) {
        if (enabled) {
            try {
                redis.delete(key);
            } catch (RuntimeException e) {
                fail("evict", e);
            }
        }
    }

    private void fail(String operation, RuntimeException e) {
        error.increment();
        log.warn("recommendation cache {} failed, computing instead: {}", operation, e.toString());
    }

    Optional<String> raw(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }
}
