package com.systemdesign.circuitbreaker;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Predicate;

public record CircuitBreakerConfig(
        double alpha,
        double failureRateThreshold,
        Duration recoveryTimeout,
        int halfOpenPermittedCalls,
        int minimumCalls,
        Predicate<Exception> failurePredicate,
        Consumer<StateTransition> transitionListener,
        Clock clock
) {

    public record StateTransition(
            CircuitBreakerState from,
            CircuitBreakerState to,
            double errorRate
    ) {
    }

    public CircuitBreakerConfig {
        if (alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("alpha must be in (0, 1], got: " + alpha);
        }
        if (failureRateThreshold <= 0 || failureRateThreshold > 1) {
            throw new IllegalArgumentException("failureRateThreshold must be in (0, 1], got: " + failureRateThreshold);
        }
        if (recoveryTimeout.isNegative() || recoveryTimeout.isZero()) {
            throw new IllegalArgumentException("recoveryTimeout must be positive");
        }
        if (halfOpenPermittedCalls < 1) {
            throw new IllegalArgumentException("halfOpenPermittedCalls must be >= 1");
        }
        if (minimumCalls < 1) {
            throw new IllegalArgumentException("minimumCalls must be >= 1, got: " + minimumCalls);
        }
        if (failurePredicate == null) {
            throw new IllegalArgumentException("failurePredicate must not be null");
        }
        if (transitionListener == null) {
            throw new IllegalArgumentException("transitionListener must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
    }

    public static CircuitBreakerConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private double alpha = 0.2;
        private double failureRateThreshold = 0.5;
        private Duration recoveryTimeout = Duration.ofSeconds(30);
        private int halfOpenPermittedCalls = 3;
        private int minimumCalls = 10;
        private Predicate<Exception> failurePredicate = exception -> true;
        private Consumer<StateTransition> transitionListener = transition -> {
        };
        private Clock clock = Clock.systemUTC();

        public Builder alpha(double alpha) {
            this.alpha = alpha;
            return this;
        }

        public Builder failureRateThreshold(double threshold) {
            this.failureRateThreshold = threshold;
            return this;
        }

        public Builder recoveryTimeout(Duration timeout) {
            this.recoveryTimeout = timeout;
            return this;
        }

        public Builder halfOpenPermittedCalls(int calls) {
            this.halfOpenPermittedCalls = calls;
            return this;
        }

        public Builder minimumCalls(int minimumCalls) {
            this.minimumCalls = minimumCalls;
            return this;
        }

        public Builder failurePredicate(Predicate<Exception> predicate) {
            this.failurePredicate = predicate;
            return this;
        }

        public Builder transitionListener(Consumer<StateTransition> listener) {
            this.transitionListener = listener;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(
                    alpha, failureRateThreshold, recoveryTimeout,
                    halfOpenPermittedCalls, minimumCalls,
                    failurePredicate, transitionListener, clock
            );
        }
    }
}
