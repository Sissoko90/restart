package com.abdatytch.auth_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuration pour WebClient
 * Nécessaire pour les appels HTTP réactifs (ex: GatewayGeoLocationClient)
 */
@Configuration
public class WebClientConfig {
    
    /**
     * Fournit un WebClient.Builder pour l'injection de dépendances
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
