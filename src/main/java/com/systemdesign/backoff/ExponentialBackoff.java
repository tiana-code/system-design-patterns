package com.systemdesign.backoff;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class ExponentialBackoff implements BackoffStrategy {

    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;
    private final double jitterFactor;

    private ExponentialBackoff(Duration initialDelay, Duration maxDelay, double multiplier, double jitterFactor) {
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
        this.jitterFactor = jitterFactor;
    }

    @Override
    public Duration nextDelay(int attempt) {
        double delayMs = initialDelay.toMillis() * Math.pow(multiplier, attempt);
        delayMs = Math.min(delayMs, maxDelay.toMillis());

        if (jitterFactor > 0) {
            double jitter = delayMs * jitterFactor * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
            delayMs = Math.max(0, delayMs + jitter);
        }

        return Duration.ofMillis(Math.round(delayMs));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ExponentialBackoff defaults() {
        return builder().build();
    }

    public static final class Builder {
        private Duration initialDelay = Duration.ofMillis(100);
        private Duration maxDelay = Duration.ofSeconds(30);
        private double multiplier = 2.0;
        private double jitterFactor = 0.1;

        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder multiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        public Builder jitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
            return this;
        }

        public ExponentialBackoff build() {
            if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()) {
                throw new IllegalArgumentException("initialDelay must be positive");
            }
            if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
                throw new IllegalArgumentException("maxDelay must be >= initialDelay");
            }
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("multiplier must be >= 1.0, got: " + multiplier);
            }
            if (jitterFactor < 0.0 || jitterFactor > 1.0) {
                throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0], got: " + jitterFactor);
            }
            return new ExponentialBackoff(initialDelay, maxDelay, multiplier, jitterFactor);
        }
    }
}
