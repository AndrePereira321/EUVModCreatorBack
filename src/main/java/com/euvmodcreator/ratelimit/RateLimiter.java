package com.euvmodcreator.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

import java.time.Duration;

public class RateLimiter {

    private final Bandwidth limit;

    private final Cache<String, Bucket> buckets;

    public RateLimiter(long capacity, Duration period) {
        this.limit = Bandwidth.builder().capacity(capacity).refillGreedy(capacity, period).build();
        this.buckets = Caffeine.newBuilder()
                .maximumSize(25_000)
                .expireAfterAccess(period)
                .build();
    }

    public void consume(String key) {
        Bucket bucket = buckets.get(key, k -> Bucket.builder().addLimit(limit).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new RateLimitException(Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L));
        }
    }

    public void reset() {
        buckets.invalidateAll();
    }
}
