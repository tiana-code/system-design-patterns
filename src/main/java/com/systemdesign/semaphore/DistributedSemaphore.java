package com.systemdesign.semaphore;

import com.systemdesign.backoff.ExponentialBackoff;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class DistributedSemaphore {

    private static final String ACQUIRE_SCRIPT = """
            local key = KEYS[1]
            local lease_id = ARGV[1]
            local max_permits = tonumber(ARGV[2])
            local ttl_ms = tonumber(ARGV[3])
            local fence_key = KEYS[2]
            
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now)
            
            local count = redis.call('ZCARD', key)
            if count < max_permits then
                local expiry = now + ttl_ms
                redis.call('ZADD', key, expiry, lease_id)
                redis.call('PEXPIRE', key, ttl_ms * 2)
                local token = redis.call('INCR', fence_key)
                redis.call('PEXPIRE', fence_key, ttl_ms * 2)
                return {token, expiry}
            end
            return {0, 0}
            """;

    private static final String RENEW_SCRIPT = """
            local key = KEYS[1]
            local lease_id = ARGV[1]
            local ttl_ms = tonumber(ARGV[2])
            
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            
            local score = redis.call('ZSCORE', key, lease_id)
            if score == false then
                return 0
            end
            if tonumber(score) < now then
                redis.call('ZREM', key, lease_id)
                return 0
            end
            redis.call('ZADD', key, now + ttl_ms, lease_id)
            redis.call('PEXPIRE', key, ttl_ms * 2)
            return 1
            """;

    private static final String RELEASE_SCRIPT = """
            local key = KEYS[1]
            local lease_id = ARGV[1]
            
            local score = redis.call('ZSCORE', key, lease_id)
            if score == false then
                return 0
            end
            redis.call('ZREM', key, lease_id)
            return 1
            """;

    private static final String AVAILABLE_SCRIPT = """
            local key = KEYS[1]
            local max_permits = tonumber(ARGV[1])
            
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now)
            local count = redis.call('ZCARD', key)
            return max_permits - count
            """;

    private final SemaphoreConfig config;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> acquireScript;
    private final DefaultRedisScript<Long> renewScript;
    private final DefaultRedisScript<Long> releaseScript;
    private final DefaultRedisScript<Long> availableScript;
    private final String redisKey;
    private final String fenceKey;
    private final ExponentialBackoff acquireBackoff;

    public DistributedSemaphore(SemaphoreConfig config, StringRedisTemplate redisTemplate) {
        this.config = config;
        this.redisTemplate = redisTemplate;
        this.redisKey = "semaphore:" + config.name();
        this.fenceKey = "semaphore:fence:" + config.name();

        this.acquireScript = new DefaultRedisScript<>();
        this.acquireScript.setScriptText(ACQUIRE_SCRIPT);
        this.acquireScript.setResultType(List.class);

        this.renewScript = buildLongScript(RENEW_SCRIPT);
        this.releaseScript = buildLongScript(RELEASE_SCRIPT);
        this.availableScript = buildLongScript(AVAILABLE_SCRIPT);

        this.acquireBackoff = ExponentialBackoff.builder()
                .initialDelay(Duration.ofMillis(50))
                .maxDelay(Duration.ofMillis(500))
                .multiplier(2.0)
                .jitterFactor(0.2)
                .build();
    }

    public record Lease(String leaseId, long fencingToken, long expiresAtMs, String semaphoreName) {
    }

    @SuppressWarnings("unchecked")
    public Optional<Lease> tryAcquire() {
        String leaseId = UUID.randomUUID().toString();
        long ttlMs = config.leaseTtl().toMillis();

        List<Long> result = (List<Long>) redisTemplate.execute(
                acquireScript,
                List.of(redisKey, fenceKey),
                leaseId,
                String.valueOf(config.permits()),
                String.valueOf(ttlMs)
        );

        if (result.size() == 2 && result.get(0) > 0) {
            return Optional.of(new Lease(leaseId, result.get(0), result.get(1), config.name()));
        }
        return Optional.empty();
    }

    public Lease acquire() throws InterruptedException {
        long deadline = System.currentTimeMillis() + config.acquireTimeout().toMillis();
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            Optional<Lease> lease = tryAcquire();
            if (lease.isPresent()) {
                return lease.get();
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            long backoffMs = acquireBackoff.nextDelay(attempt++).toMillis();
            TimeUnit.MILLISECONDS.sleep(Math.min(backoffMs, remaining));
        }

        throw new SemaphoreAcquireTimeoutException(
                "Could not acquire semaphore '%s' within %s".formatted(config.name(), config.acquireTimeout())
        );
    }

    public boolean renew(Lease lease) {
        validateLeaseOwnership(lease);
        long ttlMs = config.leaseTtl().toMillis();

        Long result = redisTemplate.execute(
                renewScript,
                List.of(redisKey),
                lease.leaseId(),
                String.valueOf(ttlMs)
        );

        return result == 1L;
    }

    public boolean release(Lease lease) {
        validateLeaseOwnership(lease);
        Long result = redisTemplate.execute(
                releaseScript,
                List.of(redisKey),
                lease.leaseId()
        );
        return result == 1L;
    }

    public int availablePermits() {
        Long available = redisTemplate.execute(
                availableScript,
                List.of(redisKey),
                String.valueOf(config.permits())
        );
        return Math.max(0, available.intValue());
    }

    private void validateLeaseOwnership(Lease lease) {
        if (!config.name().equals(lease.semaphoreName())) {
            throw new IllegalArgumentException(
                    "Lease belongs to semaphore '%s', not '%s'".formatted(lease.semaphoreName(), config.name()));
        }
    }

    private DefaultRedisScript<Long> buildLongScript(String script) {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    public static class SemaphoreAcquireTimeoutException extends RuntimeException {
        public SemaphoreAcquireTimeoutException(String message) {
            super(message);
        }
    }
}
