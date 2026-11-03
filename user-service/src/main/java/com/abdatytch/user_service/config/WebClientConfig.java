package com.abdatytch.user_service.config;

import com.abdatytch.commons.security.JwksKeyProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Les deux constructeurs de {@code WebClient}, et pourquoi il en faut deux.
 *
 * <h2>Celui qui équilibre ne convient pas à JWKS</h2>
 *
 * {@code @LoadBalanced} installe un filtre qui traite le <b>nom d'hôte</b> de
 * chaque requête comme un identifiant de service à résoudre dans Consul. C'est
 * exactement ce qu'il faut pour joindre auth-service par son nom, et c'est
 * rédhibitoire pour {@link JwksKeyProvider}, qui appelle une URL <b>absolue</b> :
 * l'équilibreur chercherait dans l'annuaire un service nommé d'après l'hôte de
 * cette URL, ne le trouverait pas, et la récupération des clés publiques
 * échouerait — donc toute validation de jeton avec elle.
 *
 * <h2>Pourquoi {@code @Primary} n'est pas une précaution</h2>
 *
 * {@link JwksKeyProvider} injecte {@code WebClient.Builder} <b>par type</b>, et
 * {@code PlatformSecurityAutoConfiguration} le récupère par
 * {@code ObjectProvider}. Deux beans du même type sans arbitre font lever
 * {@code NoUniqueBeanDefinitionException} : sans cette annotation, le service ne
 * démarre pas du tout.
 */
@Configuration
public class WebClientConfig {

    /** Constructeur ordinaire : URL absolues, aucun passage par l'annuaire. */
    @Bean
    @Primary
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    /**
     * Constructeur équilibré : l'hôte est un identifiant de service Consul.
     *
     * <p>Il sert les appels de service à service — aujourd'hui le seul,
     * {@code ClientIdentiteAuth} vers auth-service. On l'obtient par le
     * qualificateur {@code @LoadBalanced}, jamais par type.
     *
     * <p><b>Prototype</b>, et non singleton : un {@code WebClient.Builder} est
     * <b>mutable</b>. Partagé, le {@code baseUrl} et le connecteur posés par le
     * premier consommateur s'appliqueraient au suivant — un deuxième client de
     * service partirait vers auth-service sans que rien ne le signale. C'est la
     * portée que l'auto-configuration de Spring donne elle-même à son propre
     * constructeur, pour cette raison exacte.
     */
    @Bean
    @LoadBalanced
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public WebClient.Builder constructeurEquilibre() {
        return WebClient.builder();
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }
}
