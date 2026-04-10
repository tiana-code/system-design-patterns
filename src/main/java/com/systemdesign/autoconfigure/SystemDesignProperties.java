package com.systemdesign.autoconfigure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "system-design")
public class SystemDesignProperties {

    @Valid
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Valid
    private final Semaphore semaphore = new Semaphore();

    @Valid
    private final HealthQuarantine healthQuarantine = new HealthQuarantine();

    @Valid
    private final Backoff backoff = new Backoff();

    @Valid
    private final Gps gps = new Gps();

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public Semaphore getSemaphore() {
        return semaphore;
    }

    public HealthQuarantine getHealthQuarantine() {
        return healthQuarantine;
    }

    public Backoff getBackoff() {
        return backoff;
    }

    public Gps getGps() {
        return gps;
    }

    public static class CircuitBreaker {

        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "1")
        private double alpha = 0.2;

        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "1")
        private double failureRateThreshold = 0.5;

        @NotNull
        private Duration recoveryTimeout = Duration.ofSeconds(30);

        @Positive
        private int halfOpenPermittedCalls = 3;

        @Positive
        private int minimumCalls = 10;

        public double getAlpha() {
            return alpha;
        }

        public void setAlpha(double alpha) {
            this.alpha = alpha;
        }

        public double getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(double failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getRecoveryTimeout() {
            return recoveryTimeout;
        }

        public void setRecoveryTimeout(Duration recoveryTimeout) {
            this.recoveryTimeout = recoveryTimeout;
        }

        public int getHalfOpenPermittedCalls() {
            return halfOpenPermittedCalls;
        }

        public void setHalfOpenPermittedCalls(int halfOpenPermittedCalls) {
            this.halfOpenPermittedCalls = halfOpenPermittedCalls;
        }

        public int getMinimumCalls() {
            return minimumCalls;
        }

        public void setMinimumCalls(int minimumCalls) {
            this.minimumCalls = minimumCalls;
        }
    }

    public static class Semaphore {

        @Positive
        private int defaultPermits = 10;

        @NotNull
        private Duration defaultLeaseTtl = Duration.ofSeconds(30);

        @NotNull
        private Duration defaultAcquireTimeout = Duration.ofSeconds(10);

        private String name = "default";

        public int getDefaultPermits() {
            return defaultPermits;
        }

        public void setDefaultPermits(int defaultPermits) {
            this.defaultPermits = defaultPermits;
        }

        public Duration getDefaultLeaseTtl() {
            return defaultLeaseTtl;
        }

        public void setDefaultLeaseTtl(Duration defaultLeaseTtl) {
            this.defaultLeaseTtl = defaultLeaseTtl;
        }

        public Duration getDefaultAcquireTimeout() {
            return defaultAcquireTimeout;
        }

        public void setDefaultAcquireTimeout(Duration defaultAcquireTimeout) {
            this.defaultAcquireTimeout = defaultAcquireTimeout;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class HealthQuarantine {

        @Positive
        private int windowSize = 10;

        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax(value = "1")
        private double failureThreshold = 0.6;

        @Positive
        private int recoveryChecks = 3;

        @NotNull
        private Duration checkInterval = Duration.ofSeconds(15);

        @NotNull
        private Duration requestTimeout = Duration.ofSeconds(5);

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        public double getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(double failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public int getRecoveryChecks() {
            return recoveryChecks;
        }

        public void setRecoveryChecks(int recoveryChecks) {
            this.recoveryChecks = recoveryChecks;
        }

        public Duration getCheckInterval() {
            return checkInterval;
        }

        public void setCheckInterval(Duration checkInterval) {
            this.checkInterval = checkInterval;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    public static class Backoff {

        @NotNull
        private Duration initialDelay = Duration.ofMillis(100);

        @NotNull
        private Duration maxDelay = Duration.ofSeconds(30);

        @DecimalMin(value = "1")
        private double multiplier = 2.0;

        @DecimalMin(value = "0")
        @DecimalMax(value = "1")
        private double jitterFactor = 0.1;

        @AssertTrue(message = "maxDelay must be >= initialDelay")
        private boolean isMaxDelayValid() {
            return maxDelay == null || initialDelay == null || maxDelay.compareTo(initialDelay) >= 0;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public double getJitterFactor() {
            return jitterFactor;
        }

        public void setJitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
        }
    }

    public static class Gps {

        @DecimalMin(value = "0", inclusive = false)
        private double maxSpeedKnots = 50.0;

        @DecimalMin(value = "0", inclusive = false)
        private double maxHdop = 10.0;

        @Positive
        private int predictorHistoryPoints = 10;

        public double getMaxSpeedKnots() {
            return maxSpeedKnots;
        }

        public void setMaxSpeedKnots(double maxSpeedKnots) {
            this.maxSpeedKnots = maxSpeedKnots;
        }

        public double getMaxHdop() {
            return maxHdop;
        }

        public void setMaxHdop(double maxHdop) {
            this.maxHdop = maxHdop;
        }

        public int getPredictorHistoryPoints() {
            return predictorHistoryPoints;
        }

        public void setPredictorHistoryPoints(int predictorHistoryPoints) {
            this.predictorHistoryPoints = predictorHistoryPoints;
        }
    }
}
