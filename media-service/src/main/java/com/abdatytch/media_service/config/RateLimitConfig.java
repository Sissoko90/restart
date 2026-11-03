package com.abdatytch.media_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.core.annotation.Order;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configuration du rate limiting global pour tous les endpoints
 */
@Configuration
@Slf4j
public class RateLimitConfig {

    @Value("${rate-limit.max-requests-per-minute:100}")
    private int maxRequestsPerMinute;
    private static final ConcurrentHashMap<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> resetTimes = new ConcurrentHashMap<>();

    @Bean
    @Order(1)
    public WebFilter globalRateLimitFilter() {
        return (exchange, chain) -> {
            String clientIp = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
            
            String path = exchange.getRequest().getPath().value();
            
            // Skip rate limiting for health checks
            if (path.startsWith("/actuator/health")) {
                return chain.filter(exchange);
            }
            
            long now = System.currentTimeMillis();
            long resetTime = resetTimes.computeIfAbsent(clientIp, k -> now + 60000);
            
            // Reset counter if minute has passed
            if (now > resetTime) {
                requestCounts.remove(clientIp);
                resetTimes.put(clientIp, now + 60000);
            }
            
            AtomicInteger count = requestCounts.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
            int currentCount = count.incrementAndGet();
            
            if (currentCount > maxRequestsPerMinute) {
                log.warn("Rate limit exceeded for IP: {} ({} requests/min)", clientIp, currentCount);
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(maxRequestsPerMinute));
                exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
                exchange.getResponse().getHeaders().add("Retry-After", "60");
                return exchange.getResponse().setComplete();
            }
            
            // Add rate limit headers
            exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(maxRequestsPerMinute));
            exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", 
                String.valueOf(maxRequestsPerMinute - currentCount));
            
            return chain.filter(exchange);
        };
    }
}
