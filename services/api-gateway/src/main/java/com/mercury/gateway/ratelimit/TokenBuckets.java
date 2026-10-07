package com.mercury.gateway.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token buckets per key: each key may burst up to `capacity` requests and then proceed at `refillPerSecond`.
 * In memory and per gateway instance (state is bounded: idle buckets are dropped), which is the standard trade-off
 * of a local limiter: with N gateway replicas a client can get up to N times the limit.
 */
public final class TokenBuckets {

    private static final class Bucket {
        double tokens;
        long lastNanos;
        Bucket(double tokens, long lastNanos) { this.tokens = tokens; this.lastNanos = lastNanos; }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private final int maxKeys;

    public TokenBuckets(double refillPerSecond, int capacity, LongSupplier nanoClock, int maxKeys) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000d;
        this.nanoClock = nanoClock;
        this.maxKeys = maxKeys;
    }

    public TokenBuckets(double refillPerSecond, int capacity) {
        this(refillPerSecond, capacity, System::nanoTime, 100_000);
    }

    /** @return 0 when the request may proceed, otherwise how many whole seconds to wait (at least 1) */
    public long tryConsume(String key) {
        long now = nanoClock.getAsLong();
        if (buckets.size() > maxKeys) {
            evictIdle(now);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.lastNanos) * refillPerNano);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return 0;
            }
            double missing = 1 - bucket.tokens;
            return Math.max(1, (long) Math.ceil(missing / (refillPerNano * 1_000_000_000d)));
        }
    }

    private void evictIdle(long now) {
        double idleNanos = capacity / refillPerNano;          // a bucket idle this long is full again: forgetting it is lossless
        buckets.entrySet().removeIf(e -> now - e.getValue().lastNanos > idleNanos);
    }

    public int size() {
        return buckets.size();
    }
}
