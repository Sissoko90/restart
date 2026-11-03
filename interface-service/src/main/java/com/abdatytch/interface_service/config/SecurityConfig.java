package com.abdatytch.interface_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration de sécurité complète pour l'interface-service
 * Inclut: JWT, CORS, CSRF, Headers de sécurité
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    /**
     * L'URL du JWKS, servie par ce service lui-même.
     *
     * <h3>Le défaut corrigé : il pointait ailleurs, et sur rien</h3>
     *
     * Il valait {@code http://localhost:8082/.well-known/jwks.json}, soit
     * <b>deux</b> erreurs dans la même chaîne :
     * <ul>
     *   <li>le port <b>8082</b> est celui d'auth-service, alors que l'autorité
     *       JWT de la plateforme est <b>ce</b> service, sur 8088 — il est le
     *       seul détenteur des clés privées ;</li>
     *   <li>le chemin {@code /.well-known/jwks.json} n'est servi par personne
     *       sous cette forme : {@code DevKeyManagementController} porte
     *       {@code @RequestMapping("/dev")}, donc le chemin réel est
     *       {@code /dev/.well-known/jwks.json}.</li>
     * </ul>
     *
     * <p>Le défaut ne se voyait pas parce que {@code config-repo} le remplace
     * par la bonne valeur. Mais une valeur de repli fausse est un piège qui
     * attend : elle s'applique le jour où config-service est injoignable, et ce
     * jour-là la validation des jetons chercherait les clés sur un service qui
     * n'en sert aucune — à l'instant précis où l'on cherche ailleurs.
     */
    @Value("${jwt.jwks.url:http://localhost:8088/dev/.well-known/jwks.json}")
    private String jwksUrl;

    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Configuration CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                
                // Désactiver CSRF pour API REST stateless
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                
                // Configuration des headers de sécurité
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions
                                .mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'"))
                        .cache(ServerHttpSecurity.HeaderSpec.CacheSpec::disable)
                )
                
                // Configuration des autorisations
                .authorizeExchange(exchanges -> exchanges
                        // Endpoints publics
                        // /actuator/** N'EST PLUS permitAll.
                        //
                        // Seul le filtre d'acces Gateway de ce service limitait
                        // l'exemption a /actuator/health, ce qui bloquait le
                        // reste : la defense en profondeur a tenu alors que
                        // cette couche-ci etait ouverte. Mesure sur le service
                        // en marche : /actuator/env en direct rendait 403.
                        //
                        // Compter sur l'autre couche n'est pas une politique.
                        .pathMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        
                        // JWKS endpoint pour les développeurs (public)
                        // Le JWKS est PUBLIC par nature : les cles publiques doivent
                        // etre lisibles par tout service qui valide un jeton.
                        .pathMatchers("/dev/.well-known/jwks.json").permitAll()

                        // Gestion du materiel cryptographique : reserve au
                        // responsable technique.
                        //
                        // Ces trois endpoints portaient le commentaire « Reserve aux
                        // administrateurs » sans qu'AUCUNE regle ne l'applique : ils
                        // tombaient sous anyExchange().authenticated(). N'importe quel
                        // porteur de jeton pouvait donc faire tourner les cles de
                        // signature de la plateforme, ou supprimer des cles encore en
                        // periode de grace — ce qui invalide les jetons en circulation.
                        .pathMatchers("/dev/admin/keys/**").hasRole("SUPER_ADMIN")
                        .pathMatchers("/.well-known/**").permitAll()
                        
                        // Endpoints internes (protégés par X-Internal-Secret header)
                        .pathMatchers("/internal/**").permitAll()
                        
                        // Endpoints d'authentification développeur (public)
                        .pathMatchers("/dev/auth/register").permitAll()
                        .pathMatchers("/dev/auth/login").permitAll()
                        .pathMatchers("/dev/auth/refresh").permitAll()
                        .pathMatchers("/dev/auth/verify-email").permitAll()
                        .pathMatchers("/dev/auth/forgot-password").permitAll()
                        .pathMatchers("/dev/auth/reset-password").permitAll()
                        
                        .pathMatchers("/auth/register").permitAll()
                        .pathMatchers("/auth/login").permitAll()
                        .pathMatchers("/auth/refresh").permitAll()
                        .pathMatchers("/auth/verify-email").permitAll()
                        .pathMatchers("/auth/forgot-password").permitAll()
                        .pathMatchers("/auth/reset-password").permitAll()
                        
                        // Endpoints admin accessibles sans jeton — chacun porte sa
                        // propre vérification :
                        //   /admin/login       : mot de passe + code TOTP
                        //   /admin/totp/enroll : mot de passe, et refuse si le second
                        //                        facteur est déjà actif
                        //   /admin/confirm     : jeton à usage unique reçu par courriel
                        //
                        // L'ancien GET /admin/totp/qr-code était ici, et renvoyait le
                        // secret TOTP en clair à qui connaissait l'adresse de
                        // l'administrateur — laquelle était écrite en dur dans le
                        // dépôt. Le second facteur ne protégeait donc rien.
                        .pathMatchers("/admin/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/admin/totp/enroll").permitAll()
                        .pathMatchers("/admin/confirm").permitAll()

                        // Endpoints maintenance (protégés par MaintenanceJwtFilter avec token JWT + rôle MAINTENANCE)
                        .pathMatchers("/maintenance/**").permitAll()

                        .pathMatchers("/swagger-ui/**", "/v3/api-docs/**", "/webjars/**").permitAll()
                        
                        // OPTIONS pour CORS preflight
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        
                        // Tous les autres endpoints nécessitent une authentification
                        .anyExchange().authenticated()
                )
                
                // Configuration OAuth2 Resource Server avec JWT
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder()))
                )
                
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwksUrl).build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Origins autorisées
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        configuration.setAllowedOrigins(origins);
        
        // Méthodes HTTP autorisées
        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));
        
        // Headers autorisés
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Accept",
                "Origin",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers",
                "X-CSRF-Token",
                "X-Request-ID"
        ));
        
        // Headers exposés au client
        configuration.setExposedHeaders(Arrays.asList(
                "Access-Control-Allow-Origin",
                "Access-Control-Allow-Credentials",
                "X-Request-ID"
        ));
        
        // Autoriser les credentials (cookies, authorization headers)
        configuration.setAllowCredentials(true);
        
        // Durée de cache du preflight (1 heure)
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }
}
