package com.abdatytch.market_service.config;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Le constructeur de WebClient.
 *
 * <p>{@code PROTOTYPE} : chaque client sortant recoit le sien, donc un en-tete
 * pose par l'un ne contamine pas les appels de l'autre. Avec un singleton, le
 * secret interne ajoute par un client partirait vers toutes les dependances.
 */
@Configuration
public class WebClientConfig {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
