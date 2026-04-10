package com.systemdesign.autoconfigure;

import com.systemdesign.backoff.ExponentialBackoff;
import com.systemdesign.circuitbreaker.CircuitBreakerConfig;
import com.systemdesign.circuitbreaker.EwmaCircuitBreaker;
import com.systemdesign.gps.GpsValidator;
import com.systemdesign.gps.PositionPredictor;
import com.systemdesign.health.HealthQuarantine;
import com.systemdesign.semaphore.DistributedSemaphore;
import com.systemdesign.semaphore.SemaphoreConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@EnableConfigurationProperties(SystemDesignProperties.class)
public class SystemDesignAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(EwmaCircuitBreaker.class)
    static class CircuitBreakerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public EwmaCircuitBreaker ewmaCircuitBreaker(SystemDesignProperties properties) {
            SystemDesignProperties.CircuitBreaker circuitBreaker = properties.getCircuitBreaker();
            CircuitBreakerConfig config = CircuitBreakerConfig.builder()
                    .alpha(circuitBreaker.getAlpha())
                    .failureRateThreshold(circuitBreaker.getFailureRateThreshold())
                    .recoveryTimeout(circuitBreaker.getRecoveryTimeout())
                    .halfOpenPermittedCalls(circuitBreaker.getHalfOpenPermittedCalls())
                    .minimumCalls(circuitBreaker.getMinimumCalls())
                    .build();
            return new EwmaCircuitBreaker(config);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ExponentialBackoff.class)
    static class BackoffConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public ExponentialBackoff exponentialBackoff(SystemDesignProperties properties) {
            SystemDesignProperties.Backoff backoff = properties.getBackoff();
            return ExponentialBackoff.builder()
                    .initialDelay(backoff.getInitialDelay())
                    .maxDelay(backoff.getMaxDelay())
                    .multiplier(backoff.getMultiplier())
                    .jitterFactor(backoff.getJitterFactor())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthQuarantine.class)
    static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public HealthQuarantine healthQuarantine(SystemDesignProperties properties) {
            SystemDesignProperties.HealthQuarantine healthQuarantine = properties.getHealthQuarantine();
            return new HealthQuarantine(
                    healthQuarantine.getWindowSize(),
                    healthQuarantine.getFailureThreshold(),
                    healthQuarantine.getRecoveryChecks(),
                    healthQuarantine.getCheckInterval(),
                    healthQuarantine.getRequestTimeout()
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(GpsValidator.class)
    static class GpsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public GpsValidator gpsValidator(SystemDesignProperties properties) {
            SystemDesignProperties.Gps gps = properties.getGps();
            return new GpsValidator(gps.getMaxSpeedKnots(), gps.getMaxHdop());
        }

        @Bean
        @ConditionalOnMissingBean
        public PositionPredictor positionPredictor(SystemDesignProperties properties) {
            return new PositionPredictor(properties.getGps().getPredictorHistoryPoints());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({DistributedSemaphore.class, StringRedisTemplate.class})
    static class SemaphoreConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public DistributedSemaphore distributedSemaphore(
                SystemDesignProperties properties,
                StringRedisTemplate redisTemplate
        ) {
            SystemDesignProperties.Semaphore semaphore = properties.getSemaphore();
            SemaphoreConfig config = SemaphoreConfig.builder(semaphore.getName())
                    .permits(semaphore.getDefaultPermits())
                    .leaseTtl(semaphore.getDefaultLeaseTtl())
                    .acquireTimeout(semaphore.getDefaultAcquireTimeout())
                    .build();
            return new DistributedSemaphore(config, redisTemplate);
        }
    }
}
