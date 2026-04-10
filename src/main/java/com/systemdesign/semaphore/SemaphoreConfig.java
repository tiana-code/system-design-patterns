package com.systemdesign.semaphore;

import java.time.Duration;

public record SemaphoreConfig(
        String name,
        int permits,
        Duration leaseTtl,
        Duration acquireTimeout
) {

    public SemaphoreConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be >= 1");
        }
        if (leaseTtl.isNegative() || leaseTtl.isZero()) {
            throw new IllegalArgumentException("leaseTtl must be positive");
        }
        if (acquireTimeout.isNegative() || acquireTimeout.isZero()) {
            throw new IllegalArgumentException("acquireTimeout must be positive");
        }
        if (acquireTimeout.compareTo(leaseTtl) > 0) {
            throw new IllegalArgumentException(
                    "acquireTimeout (%s) should not exceed leaseTtl (%s) - lease may expire before acquisition completes"
                            .formatted(acquireTimeout, leaseTtl));
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private int permits = 1;
        private Duration leaseTtl = Duration.ofSeconds(30);
        private Duration acquireTimeout = Duration.ofSeconds(10);

        public Builder(String name) {
            this.name = name;
        }

        public Builder permits(int permits) {
            this.permits = permits;
            return this;
        }

        public Builder leaseTtl(Duration leaseTtl) {
            this.leaseTtl = leaseTtl;
            return this;
        }

        public Builder acquireTimeout(Duration acquireTimeout) {
            this.acquireTimeout = acquireTimeout;
            return this;
        }

        public SemaphoreConfig build() {
            return new SemaphoreConfig(name, permits, leaseTtl, acquireTimeout);
        }
    }
}
