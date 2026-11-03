package com.abdatytch.user_service.config;

import com.abdatytch.user_service.constants.RateLimitConstants;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration du Rate Limiting pour protéger les endpoints publics
 * Utilise Resilience4j pour limiter le nombre de requêtes par utilisateur
 * Toutes les constantes sont centralisées dans RateLimitConstants
 */
@Slf4j
@Configuration
public class RateLimitingConfig {

    @Bean
    public RateLimiter publicEndpointsRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
            .limitForPeriod(RateLimitConstants.PUBLIC_LIMIT_PER_PERIOD)
            .limitRefreshPeriod(Duration.ofMinutes(RateLimitConstants.PUBLIC_PERIOD_MINUTES))
            .timeoutDuration(Duration.ofMillis(RateLimitConstants.PUBLIC_TIMEOUT_MS))
            .build();
        
        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        RateLimiter rateLimiter = registry.rateLimiter(RateLimitConstants.LIMITER_PUBLIC_ENDPOINTS);
        
        log.info(String.format(RateLimitConstants.LOG_INFO_PUBLIC_CONFIGURED, 
            RateLimitConstants.PUBLIC_LIMIT_PER_PERIOD, 
            RateLimitConstants.PUBLIC_PERIOD_MINUTES));
        return rateLimiter;
    }

    @Bean
    public RateLimiter userEndpointsRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
            .limitForPeriod(RateLimitConstants.USER_LIMIT_PER_PERIOD)
            .limitRefreshPeriod(Duration.ofMinutes(RateLimitConstants.USER_PERIOD_MINUTES))
            .timeoutDuration(Duration.ofMillis(RateLimitConstants.USER_TIMEOUT_MS))
            .build();
        
        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        RateLimiter rateLimiter = registry.rateLimiter(RateLimitConstants.LIMITER_USER_ENDPOINTS);
        
        log.info(String.format(RateLimitConstants.LOG_INFO_USER_CONFIGURED,
            RateLimitConstants.USER_LIMIT_PER_PERIOD,
            RateLimitConstants.USER_PERIOD_MINUTES));
        return rateLimiter;
    }

    @Bean
    public RateLimiter adminEndpointsRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
            .limitForPeriod(RateLimitConstants.ADMIN_LIMIT_PER_PERIOD)
            .limitRefreshPeriod(Duration.ofMinutes(RateLimitConstants.ADMIN_PERIOD_MINUTES))
            .timeoutDuration(Duration.ofMillis(RateLimitConstants.ADMIN_TIMEOUT_MS))
            .build();
        
        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        RateLimiter rateLimiter = registry.rateLimiter(RateLimitConstants.LIMITER_ADMIN_ENDPOINTS);
        
        log.info(String.format(RateLimitConstants.LOG_INFO_ADMIN_CONFIGURED,
            RateLimitConstants.ADMIN_LIMIT_PER_PERIOD,
            RateLimitConstants.ADMIN_PERIOD_MINUTES));
        return rateLimiter;
    }

    @Bean
    public RateLimiter fileUploadRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
            .limitForPeriod(RateLimitConstants.FILE_UPLOAD_LIMIT_PER_PERIOD)
            .limitRefreshPeriod(Duration.ofHours(RateLimitConstants.FILE_UPLOAD_PERIOD_HOURS))
            .timeoutDuration(Duration.ofMillis(RateLimitConstants.FILE_UPLOAD_TIMEOUT_MS))
            .build();
        
        RateLimiterRegistry registry = RateLimiterRegistry.of(config);
        RateLimiter rateLimiter = registry.rateLimiter(RateLimitConstants.LIMITER_FILE_UPLOAD);
        
        log.info(String.format(RateLimitConstants.LOG_INFO_FILE_CONFIGURED,
            RateLimitConstants.FILE_UPLOAD_LIMIT_PER_PERIOD,
            RateLimitConstants.FILE_UPLOAD_PERIOD_HOURS));
        return rateLimiter;
    }
}
