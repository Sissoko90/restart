package com.abdatytch.media_service.config;

import com.abdatytch.commons.security.GatewayClientIp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Configuration du rate limiting distribué avec Redis
 * Remplace le ConcurrentHashMap en mémoire par Redis pour scalabilité multi-instance
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class RedisRateLimitConfig {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Value("${rate-limit.max-requests-per-minute:100}")
    private int maxRequestsPerMinute;

    @Bean
    public WebFilter redisRateLimitFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            
            if (isPublicEndpoint(path)) {
                return chain.filter(exchange);
            }

            String clientIp = getClientIp(exchange);
            String rateLimitKey = "rate-limit:" + clientIp;

            return redisTemplate.opsForValue()
                .increment(rateLimitKey)
                .flatMap(currentCount -> {
                    if (currentCount == 1) {
                        return redisTemplate.expire(rateLimitKey, Duration.ofMinutes(1))
                            .then(Mono.just(currentCount));
                    }
                    return Mono.just(currentCount);
                })
                .flatMap(currentCount -> {
                    if (currentCount > maxRequestsPerMinute) {
                        log.warn("Rate limit exceeded for IP: {} ({} requests/min)", clientIp, currentCount);
                        
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(maxRequestsPerMinute));
                        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
                        exchange.getResponse().getHeaders().add("X-RateLimit-Reset", "60");
                        
                        return exchange.getResponse().setComplete();
                    }

                    exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(maxRequestsPerMinute));
                    exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", 
                        String.valueOf(maxRequestsPerMinute - currentCount));
                    
                    return chain.filter(exchange);
                })
                .onErrorResume(error -> {
                    log.error("Redis rate limit error for IP: {}", clientIp, error);
                    return chain.filter(exchange);
                });
        };
    }

    private boolean isPublicEndpoint(String path) {
        return path.startsWith("/actuator/health") || 
               path.startsWith("/actuator/info") ||
               path.startsWith("/actuator/prometheus") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs");
    }

    /**
     * Adresse du client, posee par le Gateway.
     *
     * <p>Lisait {@code X-Forwarded-For} : l'appelant choisissait donc l'adresse
     * sur laquelle reposait la decision de blocage ou de plafonnement. Voir
     * {@code GatewayClientIp} — le meme schema a ete trouve huit fois.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return GatewayClientIp.of(exchange);
    }
}
