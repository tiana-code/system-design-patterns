package com.systemdesign.backoff;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {

    private static RetryExecutor executorWithAttempts(int maxAttempts) {
        return RetryExecutor.builder(
                ExponentialBackoff.builder()
                        .initialDelay(Duration.ofMillis(1))
                        .maxDelay(Duration.ofMillis(1))
                        .multiplier(1.0)
                        .jitterFactor(0.0)
                        .build()
        ).maxAttempts(maxAttempts).build();
    }

    @Test
    void succeedsOnFirstAttempt() throws Exception {
        RetryExecutor executor = executorWithAttempts(3);
        String result = executor.execute(() -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void retriesAndSucceedsOnSecondAttempt() throws Exception {
        RetryExecutor executor = executorWithAttempts(3);
        AtomicInteger calls = new AtomicInteger();
        String result = executor.execute(() -> {
            if (calls.incrementAndGet() < 2) throw new RuntimeException("fail");
            return "recovered";
        });
        assertEquals("recovered", result);
        assertEquals(2, calls.get());
    }

    @Test
    void exhaustsAllAttemptsAndThrowsLastException() {
        RetryExecutor executor = executorWithAttempts(3);
        AtomicInteger calls = new AtomicInteger();
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("attempt " + calls.get());
                })
        );
        assertEquals(3, calls.get());
        assertEquals("attempt 3", thrown.getMessage());
    }

    @Test
    void retryOnPredicateThrowsImmediatelyForNonRetryableException() {
        RetryExecutor executor = RetryExecutor.builder(
                        ExponentialBackoff.builder()
                                .initialDelay(Duration.ofMillis(1))
                                .maxDelay(Duration.ofMillis(1))
                                .multiplier(1.0)
                                .jitterFactor(0.0)
                                .build()
                )
                .maxAttempts(5)
                .retryOn(e -> e instanceof RuntimeException)
                .build();

        AtomicInteger calls = new AtomicInteger();
        assertThrows(Exception.class, () ->
                executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new Exception("not retryable");
                })
        );
        assertEquals(1, calls.get());
    }

    @Test
    void builderRejectsMaxAttemptsLessThanOne() {
        assertThrows(IllegalArgumentException.class, () ->
                executorWithAttempts(0)
        );
    }

    @Test
    void runnableVariantExecutesSuccessfully() throws Exception {
        RetryExecutor executor = executorWithAttempts(3);
        AtomicInteger calls = new AtomicInteger();
        executor.execute((RetryExecutor.RetryableRunnable) () -> {
            if (calls.incrementAndGet() < 2) throw new RuntimeException("fail");
        });
        assertEquals(2, calls.get());
    }
}
