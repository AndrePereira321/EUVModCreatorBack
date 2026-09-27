package com.euvmodcreator.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockoutTest {

    private static final Duration LOCK = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-27T12:00:00Z"));

    private final Lockout lockout = new Lockout(5, LOCK, clock);

    @Test
    void allowsTheMaxAttemptsThenLocks() {
        consume("andre", 5);

        expectLocked("andre", 300);
    }

    // Attempts at 12:00 to 12:04: the fifth starts the lock, so it holds until 12:09.
    @Test
    void lockRunsFromTheLastAllowedAttempt() {
        for (int i = 0; i < 5; i++) {
            lockout.consume("andre");
            clock.advance(Duration.ofMinutes(1));
        }

        expectLocked("andre", 240);
        clock.advance(Duration.ofMinutes(4));
        consume("andre", 1);
    }

    @Test
    void attemptsDuringTheLockDoNotExtendIt() {
        consume("andre", 5);

        clock.advance(Duration.ofMinutes(2));
        expectLocked("andre", 180);
        clock.advance(Duration.ofMinutes(2));
        expectLocked("andre", 60);
        clock.advance(Duration.ofMinutes(1));
        consume("andre", 1);
    }

    // Four attempts, then a full lock duration of silence: they no longer count towards the next five.
    @Test
    void countStartsOverOnceItsWindowHasPassed() {
        consume("andre", 4);
        clock.advance(LOCK);

        consume("andre", 5);
        expectLocked("andre", 300);
    }

    @Test
    void clearForgetsTheAttempts() {
        consume("andre", 4);
        lockout.clear("andre");

        consume("andre", 5);
    }

    @Test
    void keysAreCountedSeparately() {
        consume("andre", 5);

        consume("bruno", 5);
    }

    @Test
    void resetForgetsEveryKey() {
        consume("andre", 5);
        consume("bruno", 5);
        lockout.reset();

        consume("andre", 5);
        consume("bruno", 5);
    }

    // Rounded up, so Retry-After never says 0 while the lock still holds.
    @Test
    void retryAfterRoundsUp() {
        consume("andre", 5);
        clock.advance(LOCK.minusMillis(1));

        expectLocked("andre", 1);
    }

    private void consume(String key, int times) {
        for (int i = 0; i < times; i++) {
            lockout.consume(key);
        }
    }

    private void expectLocked(String key, long retryAfterSeconds) {
        assertThatThrownBy(() -> lockout.consume(key))
                .isInstanceOfSatisfying(RateLimitException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(retryAfterSeconds));
    }

    private static class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

    }

}
