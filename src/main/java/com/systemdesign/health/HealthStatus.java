package com.systemdesign.health;

import java.time.Instant;
import java.util.Optional;

public record HealthStatus(
        String instanceId,
        boolean healthy,
        int statusCode,
        long responseTimeMs,
        String failureCause,
        Instant checkedAt
) {

    public static HealthStatus healthy(String instanceId, int statusCode, long responseTimeMs) {
        return new HealthStatus(instanceId, true, statusCode, responseTimeMs, null, Instant.now());
    }

    public static HealthStatus unhealthy(String instanceId, int statusCode, long responseTimeMs) {
        return new HealthStatus(instanceId, false, statusCode, responseTimeMs,
                "HTTP %d".formatted(statusCode), Instant.now());
    }

    public static HealthStatus unreachable(String instanceId, String cause) {
        return new HealthStatus(instanceId, false, -1, -1, cause, Instant.now());
    }

    public Optional<String> getFailureCause() {
        return Optional.ofNullable(failureCause);
    }
}
