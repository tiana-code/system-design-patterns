package com.systemdesign.backoff;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class DecorrelatedJitter implements BackoffStrategy {

    private final Duration base;
    private final Duration cap;

    private DecorrelatedJitter(Duration base, Duration cap) {
        this.base = base;
        this.cap = cap;
    }

    @Override
    public Duration nextDelay(int attempt) {
        long baseMs = base.toMillis();
        long capMs = cap.toMillis();
        long simulatedPrevious = (long) (baseMs * Math.pow(3, Math.min(attempt, 10)));
        simulatedPrevious = Math.min(simulatedPrevious, capMs);
        return computeNext(Math.max(baseMs, simulatedPrevious), baseMs, capMs);
    }

    public RetrySession newSession() {
        return new RetrySession(base.toMillis(), cap.toMillis());
    }

    private static Duration computeNext(long previousMs, long baseMs, long capMs) {
        long upper = Math.min(capMs, previousMs * 3);
        long delayMs = ThreadLocalRandom.current().nextLong(baseMs, Math.max(baseMs + 1, upper));
        return Duration.ofMillis(delayMs);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DecorrelatedJitter defaults() {
        return builder().build();
    }

    public static final class RetrySession {
        private long previousDelayMs;
        private final long baseMs;
        private final long capMs;

        private RetrySession(long baseMs, long capMs) {
            this.baseMs = baseMs;
            this.capMs = capMs;
            this.previousDelayMs = baseMs;
        }

        public Duration nextDelay() {
            Duration delay = computeNext(previousDelayMs, baseMs, capMs);
            previousDelayMs = delay.toMillis();
            return delay;
        }
    }

    public static final class Builder {
        private Duration base = Duration.ofMillis(100);
        private Duration cap = Duration.ofSeconds(30);

        public Builder base(Duration base) {
            this.base = base;
            return this;
        }

        public Builder cap(Duration cap) {
            this.cap = cap;
            return this;
        }

        public DecorrelatedJitter build() {
            if (base == null || base.isNegative() || base.isZero()) {
                throw new IllegalArgumentException("base must be positive");
            }
            if (cap == null || cap.compareTo(base) < 0) {
                throw new IllegalArgumentException("cap must be >= base");
            }
            return new DecorrelatedJitter(base, cap);
        }
    }
}
