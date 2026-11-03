package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.client.config.FeignClientInterceptor;
import feign.Logger;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration globale Feign pour auth-service
 */
@Configuration
@RequiredArgsConstructor
public class FeignClientConfig {
    
    private final FeignClientInterceptor feignClientInterceptor;
    
    /**
     * Niveau de log Feign (NONE, BASIC, HEADERS, FULL)
     */
    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }
}
