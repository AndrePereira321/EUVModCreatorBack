package com.euvmodcreator.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class Lockout {

    private final int maxAttempts;

    private final Duration lockDuration;

    private final Clock clock;

    private final Cache<String, Attempts> attempts;

    public Lockout(int maxAttempts, Duration lockDuration) {
        this(maxAttempts, lockDuration, Clock.systemUTC());
    }

    Lockout(int maxAttempts, Duration lockDuration, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.lockDuration = lockDuration;
        this.clock = clock;
        this.attempts = Caffeine.newBuilder()
                .maximumSize(25_000)
                .expireAfterWrite(lockDuration)
                .build();
    }

    /**
     * Counts an attempt, or throws {@link RateLimitException} while the key is locked. Returns the attempts left
     * before the lock: 0 means this one was the last, and the key locks unless {@link #clear} runs.
     */
    public int consume(String key) {
        Instant now = clock.instant();
        Attempts after = attempts.asMap().compute(key, (k, before) -> next(before, now));
        if (after.count() > maxAttempts) {
            throw new RateLimitException(Math.ceilDiv(Duration.between(now, after.resetAt()).toMillis(), 1000L));
        }
        return maxAttempts - after.count();
    }

    public void clear(String key) {
        attempts.invalidate(key);
    }

    public void reset() {
        attempts.invalidateAll();
    }

    private Attempts next(Attempts before, Instant now) {
        if (before == null || !now.isBefore(before.resetAt())) {
            return new Attempts(1, now.plus(lockDuration));
        }
        int count = before.count() + 1;
        Instant resetAt = count == maxAttempts ? now.plus(lockDuration) : before.resetAt();
        return new Attempts(count, resetAt);
    }

    private record Attempts(int count, Instant resetAt) {
    }
}
