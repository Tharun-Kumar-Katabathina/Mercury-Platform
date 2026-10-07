package com.mercury.product.service;

import com.mercury.product.config.ProductCacheProperties;
import com.mercury.product.dto.ProductResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-through cache of product lookups in Redis. The database stays the source of truth, so the cache is
 * strictly an optimisation: ANY Redis problem (down, slow, garbled value) is counted and treated as a miss, and
 * the request is served from the database. A cache outage can make reads slower, never wrong or unavailable.
 */
@Component
public class ProductCache {

    private static final Logger log = LoggerFactory.getLogger(ProductCache.class);

    private final ProductCacheProperties properties;
    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Counter hit;
    private final Counter miss;
    private final Counter error;

    public ProductCache(
            ProductCacheProperties properties, StringRedisTemplate redis, JsonMapper jsonMapper, MeterRegistry registry) {
        this.properties = properties;
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.hit = Counter.builder("product.cache").tag("result", "hit").description("product lookups served from Redis").register(registry);
        this.miss = Counter.builder("product.cache").tag("result", "miss").description("product lookups that went to the database").register(registry);
        this.error = Counter.builder("product.cache").tag("result", "error").description("Redis failures (treated as misses)").register(registry);
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public Optional<ProductResponse> get(UUID id) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(id));
            if (json == null) {
                miss.increment();
                return Optional.empty();
            }
            hit.increment();
            return Optional.of(jsonMapper.readValue(json, ProductResponse.class));
        } catch (RuntimeException e) {
            failed("read", e);
            return Optional.empty();
        }
    }

    public void put(ProductResponse product) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redis.opsForValue().set(key(product.id()), jsonMapper.writeValueAsString(product), properties.ttl());
        } catch (RuntimeException e) {
            failed("write", e);
        }
    }

    public void evict(UUID id) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redis.delete(key(id));
        } catch (RuntimeException e) {
            failed("evict", e);   // the TTL bounds how long a stale value could live
        }
    }

    private String key(UUID id) {
        return properties.keyPrefix() + id;
    }

    private void failed(String operation, RuntimeException e) {
        error.increment();
        log.warn("product cache {} failed, serving from the database: {}", operation, e.toString());
    }
}
