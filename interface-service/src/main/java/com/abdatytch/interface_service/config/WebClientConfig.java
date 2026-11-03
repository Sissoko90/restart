package com.abdatytch.interface_service.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fournit un {@link WebClient.Builder} injectable.
 *
 * <p>Spring Boot 4 ne publie pas systématiquement ce bean : les services de la
 * plateforme le déclarent donc explicitement (même configuration que auth-service).
 * Sans lui, tout composant appelant un autre service — ici
 * {@code QuotasServiceClient} — échoue au démarrage.
 */
@Configuration
public class WebClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
