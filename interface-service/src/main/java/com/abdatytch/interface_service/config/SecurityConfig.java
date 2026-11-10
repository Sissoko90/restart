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
import com.abdatytch.commons.security.CheminsPublics;

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
                        // LES SONDES SONT LUES CHEZ LEUR SEUL DETENTEUR.
                        //
                        // Ce fichier portait la liste en dur : trois chemins
                        // EXACTS. Or `/actuator/health` exact ne reconnait pas
                        // `/actuator/health/liveness` — la sonde de groupe
                        // tombait donc sous la regle suivante,
                        // `hasRole("ADMIN")`, et rendait 401.
                        //
                        // MESURE SUR LA PLATEFORME EN MARCHE : Consul
                        // interrogeait /actuator/health/liveness, recevait 401,
                        // marquait le controle critique, et RETIRAIT
                        // interface-service du catalogue au bout de 30 s. Le
                        // service tournait, repondait en direct, et n'existait
                        // plus pour la passerelle — qui rendait 503 sur la
                        // connexion, interface-service etant l'autorite JWT.
                        //
                        // Le defaut etait masque parce que la clef
                        // `health-check-path` de ce service etait ecrite sous
                        // `gateway:` au lieu de `spring:`, donc inerte : Consul
                        // utilisait le defaut `/actuator/health`, qui passe. La
                        // correction de l'emplacement a rendu la regle visible.
                        //
                        // CheminsPublics porte deja les quatre motifs, dont
                        // `/actuator/health/**`. Sept modules sur neuf
                        // l'utilisent ; ce fichier et celui de l'autre module
                        // tenaient les deux seules listes divergentes, et c'est
                        // precisement ce que cette classe existe pour
                        // supprimer.
                        .pathMatchers(CheminsPublics.sondes().construire().motifsPermitAll()).permitAll()
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
                        
                        // SIX REGLES ONT ETE RETIREES ICI.
                        //
                        // Elles ouvraient /dev/auth/register, login, refresh,
                        // verify-email, forgot-password et reset-password. Or
                        // AUCUN controleur n'est monte sur /dev/auth : ce
                        // service n'a que DevKeyManagementController
                        // (@RequestMapping("/dev")) et DeveloperErasureController
                        // ("/dev/erasure"). Les six endpoints reels vivent sous
                        // /auth, declares juste en dessous.
                        //
                        // Six regles permitAll sur des chemins inexistants ne
                        // sont pas inoffensives : elles font croire, a la
                        // relecture, que cette surface existe et qu'elle est
                        // volontairement ouverte. C'est ainsi qu'une route YAML
                        // vers /dev/auth/** a vecu dans la configuration de la
                        // passerelle, pointant vers le vide.
                        
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
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder())
                                .jwtAuthenticationConverter(convertisseurDeRoles()))
                )

                .build();
    }

    /**
     * Traduit le claim {@code roles} en autorités {@code ROLE_*}.
     *
     * <h3>Le défaut corrigé : deux règles de rôle qui ne pouvaient JAMAIS être
     * satisfaites</h3>
     *
     * Sans ce convertisseur, Spring Security emploie son
     * {@code JwtGrantedAuthoritiesConverter} par défaut, qui lit les claims
     * {@code scope} et {@code scp} et préfixe les valeurs de {@code SCOPE_}. Or
     * les jetons de cette plateforme ne portent ni l'un ni l'autre : ils portent
     * un claim {@code roles} ({@code DevJwtServiceImpl}). Aucune autorité
     * {@code ROLE_*} n'était donc jamais produite, et les deux seules règles de
     * rôle de ce service refusaient <b>tout le monde</b> :
     *
     * <ul>
     *   <li>{@code /dev/admin/keys/**} en {@code hasRole("SUPER_ADMIN")} — la
     *       rotation et la révocation des clés de signature de la plateforme,
     *       injoignables ;</li>
     *   <li>{@code /actuator/**} en {@code hasRole("ADMIN")} — toute la
     *       supervision, injoignable.</li>
     * </ul>
     *
     * <p>Le plus instructif est l'enchaînement. Ces trois endpoints de gestion
     * de clés portaient le commentaire « Réservé aux administrateurs » sans
     * qu'aucune règle ne l'applique : ils tombaient sous
     * {@code anyExchange().authenticated()}, et n'importe quel porteur de jeton
     * pouvait faire tourner les clés. La règle {@code hasRole("SUPER_ADMIN")} a
     * été ajoutée pour fermer ce trou — et l'a fermé pour <b>tout le monde</b>,
     * le responsable technique compris. Une protection qui refuse chacun n'est
     * pas une protection, c'est une panne, et elle se présente avec le même
     * visage vert qu'une protection qui marche.
     *
     * <p>Mesuré par {@code ChaineReelleTest}, qui appelle ces endpoints avec un
     * jeton {@code SUPER_ADMIN} authentique : 403 avant, 200 après.
     *
     * <h3>Les deux formes du claim</h3>
     *
     * {@code roles} se présente tantôt en liste, tantôt en chaîne simple. Les
     * deux sont admises — c'est le contrat des autres modules
     * ({@code user-service/SecurityConfig}, les
     * {@code JwtAuthenticationWebFilter}), et n'en couvrir qu'une laisserait
     * l'autre se casser en silence.
     *
     * <p>Le préfixe {@code ROLE_} n'est ajouté que s'il manque :
     * {@code hasRole("ADMIN")} cherche l'autorité {@code ROLE_ADMIN}, et un
     * double préfixe produirait {@code ROLE_ROLE_ADMIN} — un refus de plus,
     * silencieux lui aussi.
     */
    private org.springframework.core.convert.converter.Converter<
            org.springframework.security.oauth2.jwt.Jwt,
            reactor.core.publisher.Mono<
                    org.springframework.security.authentication.AbstractAuthenticationToken>>
            convertisseurDeRoles() {

        return jeton -> {
            var autorites = new java.util.ArrayList<
                    org.springframework.security.core.GrantedAuthority>();

            Object declares = jeton.getClaim("roles");
            if (declares instanceof String unSeul) {
                autorites.add(autorite(unSeul));
            } else if (declares instanceof java.util.List<?> plusieurs) {
                for (Object role : plusieurs) {
                    if (role != null) {
                        autorites.add(autorite(role.toString()));
                    }
                }
            }

            return reactor.core.publisher.Mono.just(
                    new org.springframework.security.oauth2.server.resource.authentication
                            .JwtAuthenticationToken(jeton, autorites, jeton.getSubject()));
        };
    }

    /** Le préfixe {@code ROLE_}, ajouté une seule fois. */
    private static org.springframework.security.core.GrantedAuthority autorite(String role) {
        String nom = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return new org.springframework.security.core.authority.SimpleGrantedAuthority(nom);
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
