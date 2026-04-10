package com.systemdesign.backoff;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BackoffStrategyTest {

    @Test
    void exponentialBackoffAttempt0ReturnsInitialDelay() {
        ExponentialBackoff backoff = ExponentialBackoff.builder()
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(30))
                .multiplier(2.0)
                .jitterFactor(0.0)
                .build();
        assertEquals(Duration.ofMillis(100), backoff.nextDelay(0));
    }

    @Test
    void exponentialBackoffGrowsWithMultiplier() {
        ExponentialBackoff backoff = ExponentialBackoff.builder()
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(30))
                .multiplier(2.0)
                .jitterFactor(0.0)
                .build();
        assertEquals(Duration.ofMillis(200), backoff.nextDelay(1));
        assertEquals(Duration.ofMillis(400), backoff.nextDelay(2));
        assertEquals(Duration.ofMillis(800), backoff.nextDelay(3));
    }

    @Test
    void exponentialBackoffIsCappedByMaxDelay() {
        ExponentialBackoff backoff = ExponentialBackoff.builder()
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofMillis(500))
                .multiplier(2.0)
                .jitterFactor(0.0)
                .build();
        for (int i = 0; i < 20; i++) {
            assertTrue(backoff.nextDelay(i).toMillis() <= 500,
                    "Attempt " + i + " exceeded maxDelay");
        }
    }

    @Test
    void exponentialBackoffJitterStaysWithinBounds() {
        ExponentialBackoff backoff = ExponentialBackoff.builder()
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(30))
                .multiplier(2.0)
                .jitterFactor(0.1)
                .build();
        for (int attempt = 0; attempt < 5; attempt++) {
            long base = (long) (100 * Math.pow(2, attempt));
            long allowedMin = (long) (base * 0.9) - 1;
            long allowedMax = (long) (base * 1.1) + 1;
            for (int sample = 0; sample < 50; sample++) {
                long ms = backoff.nextDelay(attempt).toMillis();
                assertTrue(ms >= allowedMin && ms <= allowedMax,
                        "Jitter out of bounds at attempt=" + attempt + ": " + ms);
            }
        }
    }

    @Test
    void exponentialBackoffNeverReturnsNegativeDelay() {
        ExponentialBackoff backoff = ExponentialBackoff.builder()
                .initialDelay(Duration.ofMillis(1))
                .maxDelay(Duration.ofMillis(5))
                .multiplier(1.0)
                .jitterFactor(0.9)
                .build();
        for (int i = 0; i < 100; i++) {
            assertTrue(backoff.nextDelay(0).toMillis() >= 0);
        }
    }

    @Test
    void decorrelatedJitterStatelessNextDelayIsWithinBounds() {
        DecorrelatedJitter jitter = DecorrelatedJitter.builder()
                .base(Duration.ofMillis(100))
                .cap(Duration.ofSeconds(10))
                .build();
        for (int i = 0; i < 200; i++) {
            long ms = jitter.nextDelay(i).toMillis();
            assertTrue(ms >= 100, "Delay below base: " + ms);
            assertTrue(ms <= 10_000, "Delay above cap: " + ms);
        }
    }

    @Test
    void decorrelatedJitterSessionGrowsFromBase() {
        DecorrelatedJitter jitter = DecorrelatedJitter.builder()
                .base(Duration.ofMillis(100))
                .cap(Duration.ofSeconds(30))
                .build();
        DecorrelatedJitter.RetrySession session = jitter.newSession();
        for (int i = 0; i < 10; i++) {
            long ms = session.nextDelay().toMillis();
            assertTrue(ms >= 100, "Session delay below base at step " + i + ": " + ms);
            assertTrue(ms <= 30_000, "Session delay above cap at step " + i + ": " + ms);
        }
    }

    @Test
    void decorrelatedJitterSessionsAreIndependent() {
        DecorrelatedJitter jitter = DecorrelatedJitter.builder()
                .base(Duration.ofMillis(200))
                .cap(Duration.ofSeconds(5))
                .build();
        DecorrelatedJitter.RetrySession s1 = jitter.newSession();
        DecorrelatedJitter.RetrySession s2 = jitter.newSession();

        for (int i = 0; i < 5; i++) {
            s1.nextDelay();
        }

        long s2First = s2.nextDelay().toMillis();
        assertTrue(s2First >= 200, "Second session not independent, delay: " + s2First);
    }

    @Test
    void exponentialBackoffRejectsZeroInitialDelay() {
        assertThrows(IllegalArgumentException.class, () ->
                ExponentialBackoff.builder()
                        .initialDelay(Duration.ZERO)
                        .maxDelay(Duration.ofSeconds(30))
                        .multiplier(2.0)
                        .jitterFactor(0.0)
                        .build());
    }

    @Test
    void exponentialBackoffRejectsMaxDelayBelowInitialDelay() {
        assertThrows(IllegalArgumentException.class, () ->
                ExponentialBackoff.builder()
                        .initialDelay(Duration.ofSeconds(10))
                        .maxDelay(Duration.ofSeconds(5))
                        .multiplier(2.0)
                        .jitterFactor(0.0)
                        .build());
    }

    @Test
    void exponentialBackoffRejectsMultiplierBelowOne() {
        assertThrows(IllegalArgumentException.class, () ->
                ExponentialBackoff.builder()
                        .initialDelay(Duration.ofMillis(100))
                        .maxDelay(Duration.ofSeconds(30))
                        .multiplier(0.5)
                        .jitterFactor(0.0)
                        .build());
    }

    @Test
    void exponentialBackoffRejectsJitterFactorAboveOne() {
        assertThrows(IllegalArgumentException.class, () ->
                ExponentialBackoff.builder()
                        .initialDelay(Duration.ofMillis(100))
                        .maxDelay(Duration.ofSeconds(30))
                        .multiplier(2.0)
                        .jitterFactor(1.5)
                        .build());
    }

    @Test
    void exponentialBackoffRejectsNegativeJitterFactor() {
        assertThrows(IllegalArgumentException.class, () ->
                ExponentialBackoff.builder()
                        .initialDelay(Duration.ofMillis(100))
                        .maxDelay(Duration.ofSeconds(30))
                        .multiplier(2.0)
                        .jitterFactor(-0.1)
                        .build());
    }

    @Test
    void decorrelatedJitterRejectsZeroBase() {
        assertThrows(IllegalArgumentException.class, () ->
                DecorrelatedJitter.builder()
                        .base(Duration.ZERO)
                        .cap(Duration.ofSeconds(10))
                        .build());
    }

    @Test
    void decorrelatedJitterRejectsCapBelowBase() {
        assertThrows(IllegalArgumentException.class, () ->
                DecorrelatedJitter.builder()
                        .base(Duration.ofSeconds(10))
                        .cap(Duration.ofSeconds(5))
                        .build());
    }

    @Test
    void decorrelatedJitterNextDelayGrowsWithAttempt() {
        DecorrelatedJitter jitter = DecorrelatedJitter.builder()
                .base(Duration.ofMillis(100))
                .cap(Duration.ofSeconds(30))
                .build();

        long sumLow = 0;
        long sumHigh = 0;
        int samples = 50;
        for (int i = 0; i < samples; i++) {
            sumLow += jitter.nextDelay(0).toMillis();
            sumHigh += jitter.nextDelay(5).toMillis();
        }
        assertTrue(sumHigh > sumLow, "Delay at attempt 5 should tend to be larger than at attempt 0");

        for (int attempt = 0; attempt < 20; attempt++) {
            long ms = jitter.nextDelay(attempt).toMillis();
            assertTrue(ms >= 100 && ms <= 30_000,
                    "nextDelay(" + attempt + ") out of range: " + ms);
        }
    }
}
