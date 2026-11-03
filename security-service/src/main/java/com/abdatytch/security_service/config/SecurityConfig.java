package com.abdatytch.security_service.config;

import com.abdatytch.commons.security.CheminsPublics;
import com.abdatytch.security_service.security.JwtAuthenticationWebFilter;
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
 * Sécurité WebFlux de security-service.
 *
 * <h2>Ce service n'en avait AUCUNE</h2>
 *
 * Ni cette classe, ni filtre d'accès, ni filtre JWT — alors qu'il est routé et
 * que ses endpoints décident de la politique de sécurité de <b>toute la
 * plateforme</b> : pays bloqués, règles d'IP, fenêtres de maintenance, seuils.
 * Le Gateway applique ces décisions via Redis.
 *
 * <p>{@code spring-boot-starter-security} étant au classpath sans aucune chaîne
 * déclarée, l'auto-configuration de Spring Boot s'appliquait : un utilisateur
 * {@code user} au mot de passe <b>généré et journalisé au démarrage</b>, et
 * aucun contrôle de rôle. Un jeton porteur de la plateforme n'y était pas
 * accepté, donc les sept endpoints étaient en pratique injoignables par la
 * passerelle — le même défaut que media-service, dans le service le plus
 * privilégié.
 *
 * <h2>Le patron est celui de quotas-service et setting-service</h2>
 *
 * Délibérément à l'identique : le service n'est joignable qu'à travers le
 * Gateway (signature HMAC vérifiée par {@code GatewayAccessWebFilter}, qui
 * s'exécute avant cette chaîne), et le JWT identifie ensuite l'appelant et
 * porte le rôle. Inventer une variante ici donnerait une sixième liste de
 * règles à tenir en parallèle, et ce dépôt a déjà payé ce qu'il arrive alors.
 *
 * <p>{@code AppelantAdministrateur} redevient fonctionnel : il lit l'identité
 * dans le {@code SecurityContext}, que le filtre JWT peuple enfin. C'est cette
 * identité qui est écrite en base comme auteur de chaque décision, et qui sert
 * de preuve.
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

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
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
                        // Le prefixe de CE service est /security-admin, pas
                        // /admin : voir SecurityConstants.ADMIN_BASE_PATH. Y
                        // mettre /admin ne protegerait rien, et la regle aurait
                        // l'air d'etre la.
                        .pathMatchers("/security-admin/**").hasRole("ADMIN")
                        .anyExchange().authenticated()
                )
                .addFilterAt(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
