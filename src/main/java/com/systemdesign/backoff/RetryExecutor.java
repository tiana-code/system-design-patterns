package com.systemdesign.backoff;

import java.time.Duration;
import java.util.function.Predicate;

public final class RetryExecutor {

    private final BackoffStrategy strategy;
    private final int maxAttempts;
    private final Predicate<Exception> retryPredicate;

    private RetryExecutor(BackoffStrategy strategy, int maxAttempts, Predicate<Exception> retryPredicate) {
        this.strategy = strategy;
        this.maxAttempts = maxAttempts;
        this.retryPredicate = retryPredicate;
    }

    public <T> T execute(RetryableSupplier<T> action) throws Exception {
        Exception lastException = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (Exception exception) {
                lastException = exception;
                if (!retryPredicate.test(exception)) {
                    throw exception;
                }
                if (attempt < maxAttempts - 1) {
                    Duration delay = strategy.nextDelay(attempt);
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        }
        throw lastException;
    }

    public void execute(RetryableRunnable action) throws Exception {
        execute(() -> {
            action.run();
            return null;
        });
    }

    public static Builder builder(BackoffStrategy strategy) {
        return new Builder(strategy);
    }

    @FunctionalInterface
    public interface RetryableSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface RetryableRunnable {
        void run() throws Exception;
    }

    public static final class Builder {
        private final BackoffStrategy strategy;
        private int maxAttempts = 3;
        private Predicate<Exception> retryPredicate = exception -> true;

        private Builder(BackoffStrategy strategy) {
            this.strategy = strategy;
        }

        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder retryOn(Predicate<Exception> predicate) {
            this.retryPredicate = predicate;
            return this;
        }

        public RetryExecutor build() {
            return new RetryExecutor(strategy, maxAttempts, retryPredicate);
        }
    }
}
