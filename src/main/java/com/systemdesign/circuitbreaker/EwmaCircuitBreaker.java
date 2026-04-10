package com.systemdesign.circuitbreaker;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

public class EwmaCircuitBreaker {

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    private record StateSnapshot(
            CircuitBreakerState state,
            double ewmaErrorRate,
            Instant openedAt,
            int halfOpenSuccesses,
            int halfOpenFailures,
            int remainingProbePermits,
            long totalCalls
    ) {
        static StateSnapshot initial() {
            return new StateSnapshot(CircuitBreakerState.CLOSED, 0.0, null, 0, 0, 0, 0);
        }

        StateSnapshot withRate(double rate) {
            return new StateSnapshot(state, rate, openedAt, halfOpenSuccesses, halfOpenFailures, remainingProbePermits, totalCalls + 1);
        }

        StateSnapshot transitionTo(CircuitBreakerState next, Instant now) {
            return switch (next) {
                case OPEN -> new StateSnapshot(next, ewmaErrorRate, now, 0, 0, 0, totalCalls);
                case HALF_OPEN -> new StateSnapshot(next, ewmaErrorRate, openedAt, 0, 0, 0, totalCalls);
                case CLOSED -> new StateSnapshot(next, 0.0, null, 0, 0, 0, 0);
            };
        }

        StateSnapshot withProbePermits(int permits) {
            return new StateSnapshot(state, ewmaErrorRate, openedAt, halfOpenSuccesses, halfOpenFailures, permits, totalCalls);
        }

        StateSnapshot recordHalfOpenOutcome(boolean success) {
            return new StateSnapshot(
                    state, ewmaErrorRate, openedAt,
                    success ? halfOpenSuccesses + 1 : halfOpenSuccesses,
                    success ? halfOpenFailures : halfOpenFailures + 1,
                    remainingProbePermits, totalCalls
            );
        }

        StateSnapshot decrementPermit() {
            return new StateSnapshot(state, ewmaErrorRate, openedAt, halfOpenSuccesses, halfOpenFailures, remainingProbePermits - 1, totalCalls);
        }
    }

    private final CircuitBreakerConfig config;
    private final AtomicReference<StateSnapshot> snapshot;

    public EwmaCircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
        this.snapshot = new AtomicReference<>(StateSnapshot.initial());
    }

    public <T> T execute(CheckedSupplier<T> action) throws Exception {
        StateSnapshot current = snapshot.get();

        return switch (current.state()) {
            case OPEN -> {
                if (!shouldAttemptRecovery(current)) {
                    throw newCallNotPermittedException(current);
                }
                boolean transitioned = tryTransitionToHalfOpen();
                if (!transitioned) {
                    StateSnapshot reread = snapshot.get();
                    if (reread.state() == CircuitBreakerState.HALF_OPEN) {
                        yield executeProbeIfPermitAvailable(action);
                    }
                    throw newCallNotPermittedException(reread);
                }
                yield executeProbeIfPermitAvailable(action);
            }
            case HALF_OPEN -> executeProbeIfPermitAvailable(action);
            case CLOSED -> executeAndRecord(action);
        };
    }

    public void execute(CheckedRunnable action) throws Exception {
        execute(() -> {
            action.run();
            return null;
        });
    }

    public CircuitBreakerState getState() {
        return snapshot.get().state();
    }

    public double getEwmaErrorRate() {
        return snapshot.get().ewmaErrorRate();
    }

    private <T> T executeProbeIfPermitAvailable(CheckedSupplier<T> action) throws Exception {
        while (true) {
            StateSnapshot current = snapshot.get();
            if (current.remainingProbePermits() <= 0) {
                throw new CallNotPermittedException(
                        "Circuit breaker is HALF_OPEN: no probe permits remaining",
                        CircuitBreakerState.HALF_OPEN, current.ewmaErrorRate());
            }
            if (snapshot.compareAndSet(current, current.decrementPermit())) {
                return executeInHalfOpen(action);
            }
        }
    }

    private <T> T executeAndRecord(CheckedSupplier<T> action) throws Exception {
        try {
            T result = action.get();
            recordSuccess();
            return result;
        } catch (Exception exception) {
            if (config.failurePredicate().test(exception)) {
                recordFailure();
            } else {
                recordSuccess();
            }
            throw exception;
        }
    }

    private <T> T executeInHalfOpen(CheckedSupplier<T> action) throws Exception {
        try {
            T result = action.get();
            recordHalfOpenSuccess();
            return result;
        } catch (Exception exception) {
            if (config.failurePredicate().test(exception)) {
                recordHalfOpenFailure();
            } else {
                recordHalfOpenSuccess();
            }
            throw exception;
        }
    }

    private void recordSuccess() {
        snapshot.updateAndGet(currentSnapshot -> {
            double newRate = (1 - config.alpha()) * currentSnapshot.ewmaErrorRate();
            return currentSnapshot.withRate(newRate);
        });
    }

    private void recordFailure() {
        StateSnapshot before = snapshot.get();
        StateSnapshot after = snapshot.updateAndGet(currentSnapshot -> {
            double newRate = config.alpha() + (1 - config.alpha()) * currentSnapshot.ewmaErrorRate();
            StateSnapshot updated = currentSnapshot.withRate(newRate);
            if (newRate >= config.failureRateThreshold() && updated.totalCalls() >= config.minimumCalls()) {
                return updated.transitionTo(CircuitBreakerState.OPEN, config.clock().instant());
            }
            return updated;
        });
        if (before.state() != after.state()) {
            fireTransition(before.state(), after.state(), after.ewmaErrorRate());
        }
    }

    private void recordHalfOpenSuccess() {
        StateSnapshot before = snapshot.get();
        StateSnapshot after = snapshot.updateAndGet(currentSnapshot -> {
            StateSnapshot updated = currentSnapshot.recordHalfOpenOutcome(true);
            if (updated.halfOpenSuccesses() >= config.halfOpenPermittedCalls()) {
                return updated.transitionTo(CircuitBreakerState.CLOSED, config.clock().instant());
            }
            return updated;
        });
        if (before.state() != after.state()) {
            fireTransition(before.state(), after.state(), after.ewmaErrorRate());
        }
    }

    private void recordHalfOpenFailure() {
        StateSnapshot before = snapshot.get();
        StateSnapshot after = snapshot.updateAndGet(currentSnapshot ->
                currentSnapshot.recordHalfOpenOutcome(false)
                        .transitionTo(CircuitBreakerState.OPEN, config.clock().instant())
        );
        if (before.state() != after.state()) {
            fireTransition(before.state(), after.state(), after.ewmaErrorRate());
        }
    }

    private boolean shouldAttemptRecovery(StateSnapshot s) {
        return s.openedAt() != null &&
                config.clock().instant().isAfter(s.openedAt().plus(config.recoveryTimeout()));
    }

    private boolean tryTransitionToHalfOpen() {
        while (true) {
            StateSnapshot current = snapshot.get();
            if (current.state() != CircuitBreakerState.OPEN || !shouldAttemptRecovery(current)) {
                return false;
            }
            StateSnapshot next = current
                    .transitionTo(CircuitBreakerState.HALF_OPEN, config.clock().instant())
                    .withProbePermits(config.halfOpenPermittedCalls());
            if (snapshot.compareAndSet(current, next)) {
                fireTransition(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN, current.ewmaErrorRate());
                return true;
            }
            StateSnapshot reread = snapshot.get();
            if (reread.state() == CircuitBreakerState.HALF_OPEN) {
                return false;
            }
        }
    }

    private CallNotPermittedException newCallNotPermittedException(StateSnapshot s) {
        long retryAfterMs = 0;
        if (s.openedAt() != null) {
            Instant retryAt = s.openedAt().plus(config.recoveryTimeout());
            retryAfterMs = Math.max(0, retryAt.toEpochMilli() - config.clock().instant().toEpochMilli());
        }
        return new CallNotPermittedException(
                "Circuit breaker is %s. EWMA error rate: %.4f".formatted(s.state(), s.ewmaErrorRate()),
                s.state(), s.ewmaErrorRate(), retryAfterMs);
    }

    private void fireTransition(CircuitBreakerState from, CircuitBreakerState to, double errorRate) {
        try {
            config.transitionListener().accept(
                    new CircuitBreakerConfig.StateTransition(from, to, errorRate));
        } catch (Exception ignored) {
        }
    }

    public static class CallNotPermittedException extends RuntimeException {
        private final CircuitBreakerState state;
        private final double errorRate;
        private final long retryAfterMs;

        public CallNotPermittedException(String message, CircuitBreakerState state, double errorRate) {
            this(message, state, errorRate, 0);
        }

        public CallNotPermittedException(String message, CircuitBreakerState state, double errorRate, long retryAfterMs) {
            super(message);
            this.state = state;
            this.errorRate = errorRate;
            this.retryAfterMs = retryAfterMs;
        }

        public CircuitBreakerState getState() {
            return state;
        }

        public double getErrorRate() {
            return errorRate;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }
    }
}
