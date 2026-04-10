package com.systemdesign.circuitbreaker;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EwmaCircuitBreakerTest {

    private static CircuitBreakerConfig fastConfig() {
        return CircuitBreakerConfig.builder()
                .alpha(1.0)
                .failureRateThreshold(0.5)
                .recoveryTimeout(Duration.ofMillis(50))
                .halfOpenPermittedCalls(2)
                .minimumCalls(1)
                .build();
    }

    @Test
    void startsInClosedState() {
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(CircuitBreakerConfig.defaults());
        assertEquals(CircuitBreakerState.CLOSED, cb.getState());
        assertEquals(0.0, cb.getEwmaErrorRate());
    }

    @Test
    void successfulCallsKeepCircuitClosed() throws Exception {
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(CircuitBreakerConfig.defaults());
        for (int i = 0; i < 10; i++) {
            cb.execute(() -> "ok");
        }
        assertEquals(CircuitBreakerState.CLOSED, cb.getState());
    }

    @Test
    void singleFailureWithAlpha1OpensImmediately() {
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(fastConfig());
        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        assertEquals(CircuitBreakerState.OPEN, cb.getState());
        assertEquals(1.0, cb.getEwmaErrorRate());
    }

    @Test
    void openCircuitRejectsCallsWithOpenException() {
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(fastConfig());
        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));

        assertThrows(EwmaCircuitBreaker.CallNotPermittedException.class,
                () -> cb.execute(() -> "should not run"));
    }

    @Test
    void transitionsToHalfOpenAfterRecoveryTimeout() throws Exception {
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(fastConfig());
        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        assertEquals(CircuitBreakerState.OPEN, cb.getState());

        Thread.sleep(100);

        cb.execute(() -> "probe");
        assertEquals(CircuitBreakerState.HALF_OPEN, cb.getState());
    }

    @Test
    void halfOpenClosesAfterEnoughSuccesses() throws Exception {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .alpha(1.0)
                .failureRateThreshold(0.5)
                .recoveryTimeout(Duration.ofMillis(50))
                .halfOpenPermittedCalls(2)
                .minimumCalls(1)
                .build();
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(config);

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        Thread.sleep(100);

        cb.execute(() -> "probe1");
        cb.execute(() -> "probe2");

        assertEquals(CircuitBreakerState.CLOSED, cb.getState());
        assertEquals(0.0, cb.getEwmaErrorRate());
    }

    @Test
    void halfOpenFailureReopensCircuit() throws Exception {
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(fastConfig());
        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        Thread.sleep(100);

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("probe fail");
        }));

        assertEquals(CircuitBreakerState.OPEN, cb.getState());
    }

    @Test
    void ewmaCalculationIsCorrect() throws Exception {
        double alpha = 0.3;
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .alpha(alpha)
                .failureRateThreshold(0.99)
                .recoveryTimeout(Duration.ofSeconds(60))
                .halfOpenPermittedCalls(1)
                .minimumCalls(1)
                .build();
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(config);

        cb.execute(() -> "ok");
        double expectedAfterSuccess = alpha * 0.0 + (1 - alpha) * 0.0;
        assertEquals(expectedAfterSuccess, cb.getEwmaErrorRate(), 1e-9);

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        double expectedAfterFailure = alpha + (1 - alpha) * expectedAfterSuccess;
        assertEquals(expectedAfterFailure, cb.getEwmaErrorRate(), 1e-9);
    }

    @Test
    void concurrentHalfOpenProbesAreGatedByPermitCount() throws Exception {
        int permittedCalls = 2;
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .alpha(1.0)
                .failureRateThreshold(0.5)
                .recoveryTimeout(Duration.ofMillis(50))
                .halfOpenPermittedCalls(permittedCalls)
                .minimumCalls(1)
                .build();
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(config);

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        Thread.sleep(100);

        int threads = 10;
        CountDownLatch probeStarted = new CountDownLatch(permittedCalls);
        CountDownLatch releaseProbeLatch = new CountDownLatch(1);
        AtomicInteger probeExecuting = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    try {
                        cb.execute(() -> {
                            probeExecuting.incrementAndGet();
                            probeStarted.countDown();
                            try {
                                releaseProbeLatch.await();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            return "ok";
                        });
                    } catch (EwmaCircuitBreaker.CallNotPermittedException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await();
        go.countDown();

        boolean allPermitsFilled = probeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(allPermitsFilled, "Expected exactly " + permittedCalls + " probes to start");
        assertEquals(permittedCalls, probeExecuting.get(),
                "Exactly halfOpenPermittedCalls probes should be executing concurrently");

        releaseProbeLatch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();
    }

    @Test
    void onlyOneThreadTransitionsFromOpenToHalfOpen() throws Exception {
        int permittedCalls = 1;
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .alpha(1.0)
                .failureRateThreshold(0.5)
                .recoveryTimeout(Duration.ofMillis(50))
                .halfOpenPermittedCalls(permittedCalls)
                .minimumCalls(1)
                .build();
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(config);

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        Thread.sleep(100);

        int threads = 20;
        CountDownLatch probeStarted = new CountDownLatch(permittedCalls);
        CountDownLatch releaseProbeLatch = new CountDownLatch(1);
        AtomicInteger probeExecuting = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    try {
                        cb.execute(() -> {
                            probeExecuting.incrementAndGet();
                            probeStarted.countDown();
                            try {
                                releaseProbeLatch.await();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            return "ok";
                        });
                    } catch (EwmaCircuitBreaker.CallNotPermittedException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await();
        go.countDown();

        boolean probeStartedInTime = probeStarted.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(probeStartedInTime, "Expected the 1 probe to start");
        assertEquals(1, probeExecuting.get(),
                "Exactly 1 probe should be executing in HALF_OPEN (halfOpenPermittedCalls=1)");

        releaseProbeLatch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();
    }

    @Test
    void breakerDoesNotOpenBeforeMinimumCallsReached() throws Exception {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .alpha(1.0)
                .failureRateThreshold(0.5)
                .recoveryTimeout(Duration.ofMillis(50))
                .halfOpenPermittedCalls(1)
                .minimumCalls(3)
                .build();
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(config);

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        assertEquals(CircuitBreakerState.CLOSED, cb.getState());

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        assertEquals(CircuitBreakerState.CLOSED, cb.getState());

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        assertEquals(CircuitBreakerState.OPEN, cb.getState());
    }

    @Test
    void nonMatchingExceptionDoesNotCountAsFailure() throws Exception {
        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .alpha(1.0)
                .failureRateThreshold(0.5)
                .recoveryTimeout(Duration.ofMillis(50))
                .halfOpenPermittedCalls(1)
                .minimumCalls(1)
                .failurePredicate(ex -> ex instanceof IllegalStateException)
                .build();
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(config);

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("not a failure");
        }));
        assertEquals(CircuitBreakerState.CLOSED, cb.getState());
        assertEquals(0.0, cb.getEwmaErrorRate());

        assertThrows(IllegalStateException.class, () -> cb.execute(() -> {
            throw new IllegalStateException("counts as failure");
        }));
        assertEquals(CircuitBreakerState.OPEN, cb.getState());
        assertEquals(1.0, cb.getEwmaErrorRate());
    }

    @Test
    void openExceptionCarriesCorrectMetadata() {
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(fastConfig());
        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));

        EwmaCircuitBreaker.CallNotPermittedException ex = assertThrows(
                EwmaCircuitBreaker.CallNotPermittedException.class,
                () -> cb.execute(() -> "should not run"));

        assertEquals(CircuitBreakerState.OPEN, ex.getState());
        assertEquals(1.0, ex.getErrorRate(), 1e-9);
        assertTrue(ex.getRetryAfterMs() > 0, "retryAfterMs should be positive while timeout has not elapsed");
    }

    @Test
    void fixedClockAllowsDeterministicRecoveryTimeout() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock fixedClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                .alpha(1.0)
                .failureRateThreshold(0.5)
                .recoveryTimeout(Duration.ofSeconds(10))
                .halfOpenPermittedCalls(1)
                .minimumCalls(1)
                .clock(fixedClock)
                .build();
        EwmaCircuitBreaker cb = new EwmaCircuitBreaker(config);

        assertThrows(RuntimeException.class, () -> cb.execute(() -> {
            throw new RuntimeException("fail");
        }));
        assertEquals(CircuitBreakerState.OPEN, cb.getState());

        assertThrows(EwmaCircuitBreaker.CallNotPermittedException.class,
                () -> cb.execute(() -> "too early"));

        now.set(Instant.parse("2026-01-01T00:00:11Z"));

        cb.execute(() -> "probe after timeout");
        assertEquals(CircuitBreakerState.CLOSED, cb.getState());
    }
}
