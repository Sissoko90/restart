package com.abdatytch.setting_service.config;

import com.abdatytch.commons.security.AutorisationInterne;
import com.abdatytch.commons.security.CheminsPublics;
import org.springframework.beans.factory.annotation.Value;
import com.abdatytch.setting_service.security.JwtAuthenticationWebFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Sécurité WebFlux de setting-service.
 *
 * <p>Le service n'est joignable qu'à travers le Gateway (signature HMAC vérifiée par
 * {@code GatewayAccessWebFilter}, qui s'exécute avant cette chaîne). Le JWT identifie
 * ensuite l'appelant et porte le rôle.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationWebFilter jwtAuthenticationWebFilter;

    /** Les memes chemins publics que le filtre d'acces. Voir CheminsPublics. */
    private static final CheminsPublics CHEMINS_PUBLICS = CheminsPublics.sondes()
            .pageDErreur()
            .documentationViaPasserelle()
            .construire();

    /**
     * La regle d'autorisation des appels inter-services.
     *
     * <p>Le mecanisme vit dans platform-commons, le {@code @Bean} ici : ce
     * module ne scanne pas {@code com.abdatytch.commons}, et un
     * {@code @Component} y serait inerte.
     *
     * <p>Posee dans la chaine de securite et non dans le controleur : un
     * endpoint ajoute plus tard sous {@code /internal/} ne peut alors pas
     * oublier le controle. auth-service et interface-service le recrivent dans
     * chaque controleur, et c'est precisement la faiblesse de cette approche.
     */
    @Bean
    public AutorisationInterne autorisationInterne(
            @Value("${security.internal-secret:}") String secretInterne) {
        return new AutorisationInterne(secretInterne);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http, AutorisationInterne autorisationInterne) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // API sans état : aucune session, l'identité vient du JWT à chaque requête
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.mode(
                                org.springframework.security.web.server.header
                                        .XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none'; form-action 'self'"))
                )
                .authorizeExchange(exchanges -> exchanges
                        // Les chemins publics viennent de la MEME source que le
                        // filtre d'acces (CheminsPublics, platform-commons) : un
                        // chemin n'est joignable que s'il franchit les DEUX
                        // couches, et deux listes tenues separement se
                        // contredisent en silence.
                        //
                        // La documentation OpenAPI y figure, et c'est voulu :
                        // signature de passerelle EXIGEE, jeton non. Permettre ici
                        // n'ouvre rien tant que le filtre d'acces exige la
                        // signature — l'attaquer sur le port du service en direct
                        // echoue. C'est cette asymetrie qui fait de la passerelle
                        // la seule porte, en entree comme en sortie.
                        //
                        // `/api-docs/**`, alias springfox, n'a pas ete repris : la
                        // plateforme emploie springdoc, rien ne le sert.
                        .pathMatchers(CHEMINS_PUBLICS.motifsPermitAll()).permitAll()
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        .pathMatchers("/admin/**").hasRole("ADMIN")
                        // Appels de service a service : le secret partage fait
                        // foi, pas un jeton d'utilisateur — un service qui lit
                        // un consentement n'agit pour personne en particulier.
                        .pathMatchers("/internal/**").access(autorisationInterne)
                        .anyExchange().authenticated()
                )
                .addFilterAt(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
