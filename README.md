# system-design-patterns - High-Load Architecture Patterns Library

A Spring Boot starter library of production-grade resilience and data quality patterns for distributed systems and microservices. Each pattern is self-contained, framework-agnostic at its core, and designed for high-throughput environments.

## Patterns

### Circuit Breaker (EWMA)
**What it solves:** Cascading failures when a downstream service degrades. Standard circuit breakers react only to hard errors; EWMA (Exponentially Weighted Moving Average) detects gradual degradation by tracking error rate over time.

**When to use:** Any external HTTP call, database, or third-party API that can go unavailable. Essential when downstream latency spikes cause upstream thread exhaustion.

**How it works:**
- Tracks error rate with EWMA: `rate = alpha * outcome + (1 - alpha) * previousRate`
- OPEN state blocks all calls and throws `CallNotPermittedException` (carries state, error rate, retry-after hint)
- HALF_OPEN probes recovery with a configurable number of trial calls, gated by permits inside a single `AtomicReference<StateSnapshot>` (no split-state race)
- State transitions are lock-free via CAS on one atomic snapshot
- Configurable `failurePredicate` - choose which exceptions count as failures (respected in both CLOSED and HALF_OPEN states)
- Transition notifications fired outside CAS lambdas for clean side-effect separation
- `minimumCalls` threshold - EWMA won't trip the breaker until enough observations collected
- `transitionListener` hook for metrics/logging on state changes
- Inject `Clock` for deterministic testing without `Thread.sleep`
- `CheckedSupplier<T>` / `CheckedRunnable` functional interfaces - supports checked exceptions

### Distributed Semaphore
**What it solves:** Concurrency limits across multiple service instances - e.g., capping parallel calls to a paid API, limiting concurrent batch jobs, or protecting a weak downstream.

**When to use:** Multi-instance deployments where a local semaphore is not sufficient. Redis ZSET-backed leases with TTL prevent deadlocks on instance crash.

**How it works:**
- Redis ZSET with `leaseId` as member and `expiry epoch ms` as score
- Atomic Lua scripts for acquire, renew, release - no race conditions
- Server-side time via `redis.call('TIME')` - immune to JVM clock skew
- Fencing tokens (monotonic counter) returned on acquire - prevents stale-lease side effects
- `Lease` record carries `leaseId`, `fencingToken`, `expiresAtMs`, `semaphoreName` - used as handle for `renew(Lease)` / `release(Lease)` with ownership validation
- Renew rejects already-expired leases (score < now) instead of silently extending them
- Release via Lua script verifying lease exists before removal
- Acquire polling uses library's own `ExponentialBackoff` with jitter
- Redis keys auto-expire via `PEXPIRE` (2x lease TTL) to prevent infinite key lifetime
- Expired leases pruned on every acquire (`ZREMRANGEBYSCORE`)
- Non-fair, best-effort distributed semaphore - no ordering guarantees under contention
- Cross-field config validation: `acquireTimeout` must not exceed `leaseTtl`

### Health Quarantine
**What it solves:** Unhealthy instances receiving traffic in a service mesh or client-side load balancer, even when the health endpoint briefly recovers.

**When to use:** Client-side load balancing or service registries where you need hysteresis - quarantine on sustained failure, release only on sustained recovery.

**How it works:**
- Sliding window of `HealthStatus` records per instance with O(1) failure rate tracking
- Quarantine triggered when failure rate exceeds configurable threshold
- Recovery requires N consecutive successes (hysteresis)
- Background HTTP probes via virtual threads for parallel checking
- Explicit lifecycle state machine: `CREATED` -> `STARTED` -> `CLOSED`
- `start()` begins probing, `close()` shuts down with `awaitTermination`
- Operations on a closed instance throw `IllegalStateException`
- `HealthStatus` records carry structured `failureCause` instead of sentinel values
- Probe failures logged at WARN level via SLF4J

### Backoff with Jitter
**What it solves:** Retry thundering herd - when all clients retry at the same intervals after a failure, they re-saturate the service simultaneously.

**When to use:** All retry loops for transient failures (network, DB, rate limits). Mandatory in distributed systems.

**Implementations:**
- `ExponentialBackoff` - `delay = min(initialDelay * multiplier^attempt, maxDelay) +/- jitter`
- `DecorrelatedJitter` (AWS-style) - delay upper bound grows with attempt via `base * 3^attempt`, capped at `cap`. Use `newSession()` for per-chain state tracking with correlated previous delay

**Builder validation:** both strategies validate all invariants at build time (`initialDelay > 0`, `maxDelay >= initialDelay`, `multiplier >= 1`, `jitterFactor in [0,1]`, `base > 0`, `cap >= base`)

**Retry execution:**
- `BackoffStrategy` is a pure delay calculator: `nextDelay(int attempt) -> Duration`
- `RetryExecutor` handles the retry loop with configurable `maxAttempts` and `retryOn(Predicate<Exception>)` for exception classification
- Properly handles `InterruptedException` - restores interrupt flag and rethrows

### GPS Validation Pipeline
**What it solves:** Corrupt, spoofed, or out-of-order GPS telemetry from vessel transponders and IoT sensors. Bad positions cause false route deviations, incorrect ETA calculations, and map rendering artifacts.

**When to use:** Any pipeline ingesting raw GPS data: vessel tracking, fleet management, logistics, IoT.

**Validation stages:**
1. **Signal quality** - rejects points with HDOP above threshold (geometric dilution of precision heuristic, not absolute accuracy)
2. **Timestamp order** - rejects out-of-order or duplicate timestamps
3. **Speed check** - Haversine distance / elapsed time; rejects physically impossible jumps (default: 50 knots max)
4. **Speed consistency** - cross-checks sensor-reported `speedKnots` vs derived speed; rejects when ratio exceeds threshold (default: 3x)
5. **Post-validation listeners** - immutable `List<Consumer<GpsPoint>>` notification chain (configured at construction)

**Note:** `filterAndValidate()` compares each point against the previous *accepted* point, building a cleaned trajectory. This is an intentional policy choice.

**Batch processing:** `filterAndValidate()` returns `BatchValidationResult` with both `accepted` points and `rejected` entries (point + rejection reason) for debugging and quality monitoring.

**GpsPoint validation:** all fields validated at construction - latitude `[-90, 90]`, longitude `[-180, 180]`, hdop/speedKnots non-negative, courseDegreesTrue `[0, 360)`, NaN/Infinity rejected.

**Position prediction:**
- `PositionPredictor` uses bearing-based projection when `courseDegreesTrue` and `speedKnots` are available from the GPS sensor
- Falls back to delta-based linear extrapolation when sensor speed is zero
- Bearing projection: converts speed + course into lat/lon velocity via `cos(bearing)/sin(bearing)` with proper meters-per-degree scaling at current latitude
- `horizonSeconds` validated to be positive
- `confidenceScore` is a heuristic decay function (half-life + trajectory variance), not a calibrated probability
- Suitable for short-horizon ETA estimates and anomaly detection; not designed for complex trajectory modeling (Kalman filtering, geodesic arcs)

## Architecture

```mermaid
graph TD
    subgraph "system-design-patterns"
        CB["EwmaCircuitBreaker\n(CLOSED / OPEN / HALF_OPEN)\nfailurePredicate + listener"]
        DS["DistributedSemaphore\n(Redis ZSET + Lua + fencing)"]
        HQ["HealthQuarantine\n(sliding window + virtual threads)\nCREATED / STARTED / CLOSED"]
        BK["BackoffStrategy\nExponential / DecorrelatedJitter"]
        RE["RetryExecutor\n(configurable retry loop)"]
        GPS["GpsValidator\nSignalQuality - Order - Speed - Consistency"]
        PP["PositionPredictor\n(bearing + speed projection)"]
    end

    App["Application code"] _-|"execute(checkedSupplier)"| CB
    App _-|"tryAcquire() / release(lease)"| DS
    App _-|"isQuarantined(instanceId)"| HQ
    App _-|"nextDelay(attempt)"| BK
    BK _-|"strategy"| RE
    App _-|"validate(point, previous)"| GPS
    App _-|"predict(history, horizon)"| PP

    CB _-|"EWMA error rate"| CB
    DS _-|"Redis ZSET + TIME"| Redis[(Redis)]
    HQ _-|"HTTP GET /health"| Instances["Service instances"]
```

## Practical Use Cases

- **Distributed systems:** Wrap all inter-service HTTP calls with `EwmaCircuitBreaker` + `RetryExecutor`
- **Microservices resilience:** Use `DistributedSemaphore` to cap parallel calls to shared resources (payment processor, SMS gateway)
- **IoT data pipelines:** Feed raw GPS frames through `GpsValidator.filterAndValidate()`, inspect `BatchValidationResult.rejected()` for quality monitoring
- **Client-side load balancing:** Register all instances with `HealthQuarantine`, call `start()`, filter `getHealthyInstances()` before routing, `close()` on shutdown

## Requirements

- Java 21 (uses sealed interfaces, records, virtual threads)
- Spring Boot 3.x
- Redis (for `DistributedSemaphore` only)

## Configuration

```yaml
system-design:
  circuit-breaker:
    alpha: 0.2                    # EWMA smoothing factor (0, 1]
    failure-rate-threshold: 0.5   # Open circuit above 50% error rate (0, 1]
    recovery-timeout: 30s
    half-open-permitted-calls: 3
    minimum-calls: 10             # EWMA won't trip until this many calls observed
  semaphore:
    name: default
    default-permits: 10
    default-lease-ttl: 30s
    default-acquire-timeout: 10s  # Must not exceed default-lease-ttl
  health-quarantine:
    window-size: 10
    failure-threshold: 0.6        # Quarantine above 60% failure rate (0, 1]
    recovery-checks: 3
    check-interval: 15s
    request-timeout: 5s
  backoff:
    initial-delay: 100ms
    max-delay: 30s                # Must be >= initial-delay
    multiplier: 2.0               # >= 1
    jitter-factor: 0.1            # [0, 1]
  gps:
    max-speed-knots: 50.0
    max-hdop: 10.0
    predictor-history-points: 10
```

All properties are validated at startup via Jakarta Bean Validation (`@Validated`, `@Positive`, `@DecimalMin`, `@DecimalMax`, `@NotNull`, `@AssertTrue` for cross-field invariants). Invalid configuration produces a clear binding error instead of a silent runtime failure.

Auto-configuration is split into modular nested `@Configuration` classes with `@ConditionalOnClass` guards, so only relevant beans are created based on classpath availability.

__-

## Release Status
**0.1.0** - API is stabilising but not yet frozen. Minor versions may include breaking changes until `1.0.0`.

__-

## License
MIT - see [LICENSE](./LICENSE)
