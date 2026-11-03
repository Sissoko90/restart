package com.abdatytch.auth_service.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {
    
    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.ofDefaults();
    }
    
    @Bean
    public RateLimiter authRateLimiter(RateLimiterRegistry registry) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(5)  // 5 requêtes
                .limitRefreshPeriod(Duration.ofMinutes(1))  // par minute
                .timeoutDuration(Duration.ofSeconds(5))
                .build();
        
        return registry.rateLimiter("authRateLimiter", config);
    }
    
    @Bean
    public RateLimiter otpRateLimiter(RateLimiterRegistry registry) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(3)  // 3 requêtes
                .limitRefreshPeriod(Duration.ofMinutes(5))  // par 5 minutes
                .timeoutDuration(Duration.ofSeconds(5))
                .build();
        
        return registry.rateLimiter("otpRateLimiter", config);
    }
    
    @Bean
    public RateLimiter passwordResetRateLimiter(RateLimiterRegistry registry) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(3)  // 3 requêtes
                .limitRefreshPeriod(Duration.ofHours(1))  // par heure
                .timeoutDuration(Duration.ofSeconds(5))
                .build();
        
        return registry.rateLimiter("passwordResetRateLimiter", config);
    }
}
