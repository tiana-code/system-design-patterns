package com.systemdesign.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class HealthQuarantine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthQuarantine.class);

    public enum Lifecycle {CREATED, STARTED, CLOSED}

    private record InstanceState(
            String healthEndpoint,
            Deque<HealthStatus> history,
            int failures,
            int consecutiveSuccessesAfterQuarantine
    ) {
        static InstanceState create(String healthEndpoint) {
            return new InstanceState(healthEndpoint, new ArrayDeque<>(), 0, 0);
        }
    }

    private final int windowSize;
    private final double failureThreshold;
    private final int recoveryChecks;
    private final Duration checkInterval;
    private final Duration requestTimeout;

    private final Map<String, InstanceState> instances = new ConcurrentHashMap<>();
    private final Set<String> quarantinedInstances = ConcurrentHashMap.newKeySet();
    private final HttpClient httpClient;
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.CREATED);
    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService probeExecutor;

    public HealthQuarantine(
            int windowSize,
            double failureThreshold,
            int recoveryChecks,
            Duration checkInterval,
            Duration requestTimeout
    ) {
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be >= 1, got: " + windowSize);
        }
        if (failureThreshold <= 0 || failureThreshold > 1) {
            throw new IllegalArgumentException("failureThreshold must be in (0, 1], got: " + failureThreshold);
        }
        if (recoveryChecks < 1) {
            throw new IllegalArgumentException("recoveryChecks must be >= 1, got: " + recoveryChecks);
        }
        if (checkInterval == null || checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("checkInterval must be positive");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }

        this.windowSize = windowSize;
        this.failureThreshold = failureThreshold;
        this.recoveryChecks = recoveryChecks;
        this.checkInterval = checkInterval;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();
    }

    public void start() {
        if (!lifecycle.compareAndSet(Lifecycle.CREATED, Lifecycle.STARTED)) {
            Lifecycle current = lifecycle.get();
            if (current == Lifecycle.CLOSED) {
                throw new IllegalStateException("Cannot start a closed HealthQuarantine");
            }
            return;
        }
        this.probeExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "health-quarantine");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(
                this::checkAll,
                0,
                checkInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public void register(String instanceId, String healthEndpoint) {
        requireNotClosed();
        instances.put(instanceId, InstanceState.create(healthEndpoint));
    }

    public void deregister(String instanceId) {
        requireNotClosed();
        instances.remove(instanceId);
        quarantinedInstances.remove(instanceId);
    }

    public boolean isQuarantined(String instanceId) {
        return quarantinedInstances.contains(instanceId);
    }

    public Set<String> getQuarantinedInstances() {
        return Set.copyOf(quarantinedInstances);
    }

    public Set<String> getHealthyInstances() {
        return instances.keySet().stream()
                .filter(id -> !quarantinedInstances.contains(id))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Lifecycle getLifecycle() {
        return lifecycle.get();
    }

    private void checkAll() {
        instances.forEach((instanceId, state) ->
                probeExecutor.submit(() -> {
                    HealthStatus status = probe(instanceId, state.healthEndpoint());
                    updateState(instanceId, status);
                })
        );
    }

    private HealthStatus probe(String instanceId, String healthEndpoint) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthEndpoint))
                    .timeout(requestTimeout)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            long elapsed = System.currentTimeMillis() - start;
            boolean healthy = response.statusCode() >= 200 && response.statusCode() < 300;
            return healthy
                    ? HealthStatus.healthy(instanceId, response.statusCode(), elapsed)
                    : HealthStatus.unhealthy(instanceId, response.statusCode(), elapsed);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("Health probe interrupted for instance '{}'", instanceId);
            return HealthStatus.unreachable(instanceId, "interrupted");
        } catch (Exception exception) {
            log.warn("Health probe failed for instance '{}': {}", instanceId, exception.getMessage());
            return HealthStatus.unreachable(instanceId, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private void updateState(String instanceId, HealthStatus status) {
        instances.compute(instanceId, (id, current) -> {
            if (current == null) return null;

            Deque<HealthStatus> history = current.history();
            int failures = current.failures();

            if (history.size() >= windowSize) {
                HealthStatus evicted = history.pollFirst();
                if (evicted != null && !evicted.healthy()) {
                    failures--;
                }
            }
            history.addLast(status);
            if (!status.healthy()) {
                failures++;
            }

            boolean currentlyQuarantined = quarantinedInstances.contains(id);

            InstanceState instanceState = new InstanceState(current.healthEndpoint(), history, failures, 0);

            if (!currentlyQuarantined) {
                double failureRate = history.isEmpty() ? 0.0 : (double) failures / history.size();
                if (failureRate >= failureThreshold) {
                    quarantinedInstances.add(id);
                    return instanceState;
                }
                return instanceState;
            } else {
                int successes = status.healthy()
                        ? current.consecutiveSuccessesAfterQuarantine() + 1
                        : 0;
                if (successes >= recoveryChecks) {
                    quarantinedInstances.remove(id);
                    return instanceState;
                }
                return new InstanceState(current.healthEndpoint(), history, failures, successes);
            }
        });
    }

    private void requireNotClosed() {
        if (lifecycle.get() == Lifecycle.CLOSED) {
            throw new IllegalStateException("HealthQuarantine is closed");
        }
    }

    @Override
    public void close() {
        if (!lifecycle.compareAndSet(Lifecycle.CREATED, Lifecycle.CLOSED)) {
            if (!lifecycle.compareAndSet(Lifecycle.STARTED, Lifecycle.CLOSED)) {
                return;
            }
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (probeExecutor != null) {
            probeExecutor.close();
        }
    }
}
