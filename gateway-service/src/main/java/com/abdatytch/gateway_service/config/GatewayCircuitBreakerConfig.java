package com.abdatytch.gateway_service.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**

 * Configuration Circuit Breaker pour tous les services backend
 * Protège le Gateway contre les cascading failures
 *
 * Pattern Circuit Breaker:
 * - CLOSED: Requêtes passent normalement
 * - OPEN: Requêtes bloquées, fallback immédiat
 * - HALF_OPEN: Test de récupération
 *
 * Configuration par service:
 * - auth-service: Critique, timeout court
 * - user-service: Normal
 * - autres services: Tolérant
 */
@Configuration
public class GatewayCircuitBreakerConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayCircuitBreakerConfig.class);

    /**

     * Circuit Breaker pour auth-service (critique)
     */
    @Bean
    public CircuitBreaker authServiceCircuitBreaker(CircuitBreakerRegistry registry) {
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config =
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slowCallRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .slidingWindowType(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreaker circuitBreaker = registry.circuitBreaker("auth-service", config);

        // Event listeners pour monitoring
        circuitBreaker.getEventPublisher()
            .onStateTransition(event ->
                log.warn(" Circuit Breaker [auth-service] state changed: {} -> {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()))
            .onFailureRateExceeded(event ->
                log.error(" Circuit Breaker [auth-service] failure rate exceeded: {}%",
                    event.getFailureRate()))
            .onSlowCallRateExceeded(event ->
                log.warn(" Circuit Breaker [auth-service] slow call rate exceeded: {}%",
                    event.getSlowCallRate()));

        return circuitBreaker;
    }

    /**

     * Circuit Breaker pour user-service (normal)
     */
    @Bean
    public CircuitBreaker userServiceCircuitBreaker(CircuitBreakerRegistry registry) {
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config =
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(60)
                .slowCallRateThreshold(60)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .waitDurationInOpenState(Duration.ofSeconds(45))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreaker circuitBreaker = registry.circuitBreaker("user-service", config);

        circuitBreaker.getEventPublisher()
            .onStateTransition(event ->
                log.warn(" Circuit Breaker [user-service] state changed: {} -> {}",
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState()));

        return circuitBreaker;
    }

    /**

     * Circuit Breaker par défaut pour autres services
     */
    @Bean
    public CircuitBreaker defaultCircuitBreaker(CircuitBreakerRegistry registry) {
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config =
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(70)
                .slowCallRateThreshold(70)
                .slowCallDurationThreshold(Duration.ofSeconds(10))
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        return registry.circuitBreaker("default", config);
    }

    /**

     * Registry pour créer des circuit breakers dynamiquement
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }
}
