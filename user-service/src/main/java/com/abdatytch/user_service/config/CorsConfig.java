package com.abdatytch.user_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration CORS réactive pour WebFlux
 * Migré de Spring WebMVC vers WebFlux
 * PRÉSERVE TOUTE LA LOGIQUE de configuration CORS
 */
@Configuration
public class CorsConfig {
    
    @Value("${cors.allowed-origins}")
    private String allowedOrigins;
    
    @Value("${cors.allowed-methods}")
    private String allowedMethods;
    
    @Value("${cors.allowed-headers}")
    private String allowedHeaders;
    
    @Value("${cors.exposed-headers}")
    private String exposedHeaders;
    
    @Value("${cors.allow-credentials}")
    private Boolean allowCredentials;
    
    @Value("${cors.max-age}")
    private Long maxAge;
    
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseCommaSeparated(allowedOrigins));
        configuration.setAllowedMethods(parseCommaSeparated(allowedMethods));
        configuration.setAllowedHeaders(parseCommaSeparated(allowedHeaders));
        configuration.setExposedHeaders(parseCommaSeparated(exposedHeaders));
        configuration.setAllowCredentials(allowCredentials);
        configuration.setMaxAge(maxAge);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
    
    private List<String> parseCommaSeparated(String value) {
        return Arrays.asList(value.split(","));
    }
}
