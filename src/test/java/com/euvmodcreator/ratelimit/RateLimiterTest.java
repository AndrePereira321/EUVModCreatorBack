package com.euvmodcreator.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private final RateLimiter rateLimiter = new RateLimiter(10, Duration.ofMinutes(1));

    // Greedy refill: after the burst, one token comes back every period / capacity, 6 seconds here.
    @Test
    void allowsTheCapacityThenRejectsUntilTheNextToken() {
        consume("127.0.0.1", 10);

        assertThatThrownBy(() -> rateLimiter.consume("127.0.0.1"))
                .isInstanceOfSatisfying(RateLimitException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 6L));
    }

    @Test
    void keysHaveTheirOwnBuckets() {
        consume("127.0.0.1", 10);

        consume("127.0.0.2", 10);
    }

    @Test
    void resetRefillsEveryBucket() {
        consume("127.0.0.1", 10);
        rateLimiter.reset();

        consume("127.0.0.1", 10);
    }

    private void consume(String key, int times) {
        for (int i = 0; i < times; i++) {
            rateLimiter.consume(key);
        }
    }

}
