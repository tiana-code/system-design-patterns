package com.systemdesign.health;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static com.systemdesign.health.HealthQuarantine.Lifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

class HealthQuarantineTest {

    private static HealthQuarantine valid() {
        return new HealthQuarantine(5, 0.5, 2, Duration.ofSeconds(10), Duration.ofSeconds(2));
    }

    @Test
    void rejectsWindowSizeZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(0, 0.5, 2, Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsWindowSizeNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(-1, 0.5, 2, Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsFailureThresholdZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 0.0, 2, Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsFailureThresholdNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, -0.1, 2, Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsFailureThresholdAboveOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 1.1, 2, Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    @Test
    void acceptsFailureThresholdOfOne() {
        assertDoesNotThrow(
                () -> new HealthQuarantine(5, 1.0, 2, Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsRecoveryChecksZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 0.5, 0, Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsRecoveryChecksNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 0.5, -1, Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsNullCheckInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 0.5, 2, null, Duration.ofSeconds(2)));
    }

    @Test
    void rejectsZeroCheckInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 0.5, 2, Duration.ZERO, Duration.ofSeconds(2)));
    }

    @Test
    void rejectsNegativeCheckInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 0.5, 2, Duration.ofSeconds(-1), Duration.ofSeconds(2)));
    }

    @Test
    void rejectsNullRequestTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 0.5, 2, Duration.ofSeconds(10), null));
    }

    @Test
    void rejectsZeroRequestTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 0.5, 2, Duration.ofSeconds(10), Duration.ZERO));
    }

    @Test
    void rejectsNegativeRequestTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new HealthQuarantine(5, 0.5, 2, Duration.ofSeconds(10), Duration.ofSeconds(-1)));
    }

    @Test
    void validConstructionSucceeds() {
        assertDoesNotThrow(HealthQuarantineTest::valid);
    }

    @Test
    void registerAndDeregisterWork() {
        HealthQuarantine hq = valid();
        hq.register("instance-1", "http://localhost:8080/health");
        assertTrue(hq.getHealthyInstances().contains("instance-1"));

        hq.deregister("instance-1");
        assertFalse(hq.getHealthyInstances().contains("instance-1"));
    }

    @Test
    void newInstanceIsNotQuarantined() {
        HealthQuarantine hq = valid();
        hq.register("instance-1", "http://localhost:8080/health");
        assertFalse(hq.isQuarantined("instance-1"));
        assertFalse(hq.getQuarantinedInstances().contains("instance-1"));
    }

    @Test
    void getHealthyInstancesReturnsNonQuarantinedRegistered() {
        HealthQuarantine hq = valid();
        hq.register("a", "http://localhost:8080/health");
        hq.register("b", "http://localhost:8081/health");

        Set<String> healthy = hq.getHealthyInstances();
        assertTrue(healthy.contains("a"));
        assertTrue(healthy.contains("b"));
    }

    @Test
    void deregisteredInstanceRemovedFromHealthy() {
        HealthQuarantine hq = valid();
        hq.register("a", "http://localhost:8080/health");
        hq.register("b", "http://localhost:8081/health");
        hq.deregister("a");

        Set<String> healthy = hq.getHealthyInstances();
        assertFalse(healthy.contains("a"));
        assertTrue(healthy.contains("b"));
    }

    @Test
    void closeIsIdempotentWithoutStart() {
        HealthQuarantine hq = valid();
        assertDoesNotThrow(hq::close);
        assertDoesNotThrow(hq::close);
    }

    @Test
    void newInstanceHasCreatedLifecycle() {
        assertEquals(CREATED, valid().getLifecycle());
    }

    @Test
    void startTransitionsToStarted() {
        HealthQuarantine hq = valid();
        hq.start();
        assertEquals(STARTED, hq.getLifecycle());
    }

    @Test
    void closeTransitionsToClosed() {
        HealthQuarantine hq = valid();
        hq.close();
        assertEquals(CLOSED, hq.getLifecycle());
    }

    @Test
    void startAfterCloseThrows() {
        HealthQuarantine hq = valid();
        hq.close();
        assertThrows(IllegalStateException.class, hq::start);
    }

    @Test
    void registerAfterCloseThrows() {
        HealthQuarantine hq = valid();
        hq.close();
        assertThrows(IllegalStateException.class,
                () -> hq.register("instance-1", "http://localhost:8080/health"));
    }

    @Test
    void deregisterAfterCloseThrows() {
        HealthQuarantine hq = valid();
        hq.register("instance-1", "http://localhost:8080/health");
        hq.close();
        assertThrows(IllegalStateException.class, () -> hq.deregister("instance-1"));
    }

    @Test
    void closeIsIdempotent() {
        HealthQuarantine hq = valid();
        hq.start();
        assertDoesNotThrow(hq::close);
        assertDoesNotThrow(hq::close);
        assertEquals(CLOSED, hq.getLifecycle());
    }

    @Test
    void doubleStartIsIdempotent() {
        HealthQuarantine hq = valid();
        hq.start();
        assertDoesNotThrow(hq::start);
        assertEquals(STARTED, hq.getLifecycle());
    }

    @Test
    void healthyStatusHasNoFailureCause() {
        HealthStatus status = HealthStatus.healthy("instance-1", 200, 50);
        assertTrue(status.getFailureCause().isEmpty());
    }

    @Test
    void unhealthyStatusHasHttpCause() {
        HealthStatus status = HealthStatus.unhealthy("instance-1", 503, 100);
        assertTrue(status.getFailureCause().isPresent());
        assertEquals("HTTP 503", status.getFailureCause().get());
    }

    @Test
    void unreachableStatusHasProvidedCause() {
        HealthStatus status = HealthStatus.unreachable("instance-1", "Connection refused");
        assertTrue(status.getFailureCause().isPresent());
        assertEquals("Connection refused", status.getFailureCause().get());
    }
}
