package com.abdatytch.gateway_service.security;

import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.gateway_service.filter.ApiVersionWebFilter;
import com.abdatytch.gateway_service.service.GeoLocationService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import com.abdatytch.commons.security.CheminsPublics;

/**
 * Chaîne de sécurité du Gateway.
 *
 * <h2>Pourquoi les filtres sont construits ici et non annotés {@code @Component}</h2>
 *
 * WebFlux collecte automatiquement <b>tous</b> les beans de type {@code WebFilter}
 * et les place dans la chaîne HTTP. Spring Security en est lui-même un. Un filtre
 * à la fois annoté {@code @Component} <b>et</b> ajouté par {@code addFilterAt}
 * s'exécutait donc <b>deux fois par requête</b> : une fois dans la chaîne externe,
 * une fois dans celle de Spring Security. C'était le cas des sept filtres du
 * Gateway, avec pour conséquences concrètes :
 * <ul>
 *   <li>un compteur de rate limiting incrémenté deux fois — le plafond réel
 *       valait la moitié de l'annoncé, 2 tentatives de connexion au lieu de 5 ;</li>
 *   <li>deux allers-retours Redis par requête pour chaque contrôle ;</li>
 *   <li>chaque événement de sécurité journalisé en double, ce qui fausse toute
 *       lecture des journaux et toute alerte fondée sur un décompte.</li>
 * </ul>
 *
 * Les filtres n'étant plus des beans {@code WebFilter}, leur seul point d'entrée
 * est cette classe, et leur ordre y est explicite.
 *
 * <p>Toute future implémentation de {@code WebFilter} destinée à cette chaîne doit
 * suivre la même règle. {@code FilterWiringTest} le vérifie.
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:4200}")
    private String allowedOrigins;

    // ------------------------------------------------------------------
    // Filtres : instanciés, jamais publiés comme beans WebFilter
    // ------------------------------------------------------------------

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            JwksKeyProvider jwksKeyProvider,
            TokenRevocationChecker revocationChecker,
            ClientIpResolver clientIpResolver,
            GeoLocationService geoLocationService,
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> gatewayRedis,
            @Qualifier("sharedSecurityRedisTemplate") ReactiveRedisTemplate<String, String> sharedSecurityRedis,
            @Value("${jwt.issuer:interface-service}") String jwtIssuer,
            @Value("${jwt.audience:}") String jwtAudience,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {

        JwtWebFilter jwtWebFilter =
                new JwtWebFilter(jwksKeyProvider, revocationChecker, jwtIssuer, jwtAudience);

        // Un seul enregistreur, partage par tous les filtres qui detectent.
        // Compter les violations separement par filtre les rendrait invisibles
        // les unes aux autres : dix traversees et dix tentatives XSS depuis la
        // meme adresse doivent s'additionner.
        SecurityViolationRecorder violationRecorder =
                new SecurityViolationRecorder(sharedSecurityRedis, meterRegistry);

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)  // API sans cookie de session : le jeton porte l'authentification
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/v1/auth/**").permitAll()
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

                        // Accusés de réception du fournisseur SMS, à destination
                        // d'auth-service. Public au Gateway par nécessité :
                        // AfrikSMS ne peut présenter aucun jeton, il ne connaît
                        // que l'URL déclarée. L'authentification est portée par
                        // le contrôleur en aval — un secret partagé dans la
                        // chaîne de requête, comparé à temps constant, et un 404
                        // sur refus.
                        .pathMatchers("/api/v1/sms/accuses/**").permitAll()

                        // JWKS : public par nature — les services doivent pouvoir
                        // récupérer les clés publiques sans être déjà authentifiés.
                        .pathMatchers("/.well-known/jwks.json").permitAll()

                        // La documentation : `permitAll` ICI, mais gardee par
                        // DocumentationBasicAuthWebFilter, qui s'execute AVANT
                        // cette chaine (HIGHEST_PRECEDENCE + 8).
                        //
                        // `permitAll` ne veut donc pas dire « ouvert » : il veut
                        // dire « cette chaine n'est pas le juge ». Un navigateur
                        // qui charge Swagger ne porte aucun jeton JWT, donc
                        // exiger ici une authentification rendrait la
                        // documentation illisible ; c'est l'authentification
                        // HTTP dediee du filtre qui decide, et a celle-la le
                        // navigateur sait repondre.
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/api-docs/**").permitAll()
                        .pathMatchers("/webjars/**").permitAll()
                        // Les documents AGREGES des sept services. Sans cette
                        // ligne ils tomberaient sous anyExchange().authenticated()
                        // et l'interface Swagger resterait vide : la page se
                        // chargerait, et chaque document serait refuse en 401.
                        .pathMatchers("/api/v1/docs/**").permitAll()
                        // `/swagger-resources/**` a ete RETIRE : chemin springfox,
                        // la plateforme emploie springdoc 2.7. Rien ne le sert,
                        // donc l'ouvrir n'ajoutait qu'une exemption ayant l'air
                        // d'une decision.

                        // Connexion administrateur : protégée en aval par X-Admin-Secret
                        .pathMatchers("/api/v1/admin/login").permitAll()
                        .pathMatchers("/api/v1/admin/totp/qr-code").permitAll()

                        // auth-service. La connexion d'un administrateur de la
                        // PLATEFORME est publique par necessite : il n'a aucun
                        // jeton a presenter avant de s'etre connecte. Declarees
                        // AVANT la regle generale, qui l'emporterait sinon.
                        // Surface SDK d'auth-service : ce qui precede
                        // l'obtention d'un jeton. Declare AVANT la regle
                        // generale, qui l'emporterait sinon.
                        //
                        // Miroir des permitAll de auth-service/SecurityConfig ;
                        // SurfacePubliqueSdkTest verifie que les deux listes ne
                        // divergent pas.
                        .pathMatchers(HttpMethod.POST,
                                "/api/v1/auth-sdk/login",
                                "/api/v1/auth-sdk/refresh",
                                "/api/v1/auth-sdk/forgot-password",
                                "/api/v1/auth-sdk/reset-password",
                                "/api/v1/auth-sdk/resend-code",
                                "/api/v1/auth-sdk/phone/auth-request",
                                "/api/v1/auth-sdk/phone/auth-verify",
                                "/api/v1/auth-sdk/phone/register-request",
                                "/api/v1/auth-sdk/phone/register-verify",
                                "/api/v1/auth-sdk/email/auth-request",
                                "/api/v1/auth-sdk/email/auth-verify",
                                "/api/v1/auth-sdk/email/register",
                                "/api/v1/auth-sdk/email/register-verify",
                                "/api/v1/auth-sdk/biometric/authenticate/initiate",
                                "/api/v1/auth-sdk/biometric/authenticate/verify",
                                "/api/v1/auth-sdk/oauth2/callback",
                                // Echange du code de remise contre les jetons.
                                // Les jetons ne voyagent plus dans l'URL de
                                // redirection ; le frontal les recupere ici, en
                                // POST. Public par necessite : l'appelant n'a
                                // pas encore de jeton, c'est ce qu'il vient
                                // chercher. Ce qui tient la porte est le code
                                // lui-meme — 32 octets aleatoires, usage unique,
                                // deux minutes.
                                "/api/v1/auth-sdk/oauth2/exchange",
                                "/api/v1/auth-sdk/admin/login",
                                "/api/v1/auth-sdk/admin/totp/enroll",
                                "/api/v1/auth-sdk/admin/totp/reset").permitAll()
                        .pathMatchers(HttpMethod.GET,
                                "/api/v1/auth-sdk/oauth2/authorize/**",
                                "/api/v1/auth-sdk/oauth2/callback/**",
                                // Voir JwtWebFilter : la liste des fournisseurs
                                // precede l'obtention d'un jeton.
                                "/api/v1/auth-sdk/oauth2/providers").permitAll()
                        .pathMatchers("/api/v1/auth-sdk/**").authenticated()
                        .pathMatchers("/api/v1/auth-admin/**").authenticated()
                        .pathMatchers("/api/v1/auth-users/**").authenticated()

                        // security-service : la politique de securite de toute
                        // la plateforme, reservee aux administrateurs.
                        //
                        // hasRole et non authenticated : ces endpoints refusent
                        // un pays entier, bloquent une adresse IP, coupent un
                        // service pour maintenance. Un client authentifie n'a
                        // aucune raison d'y toucher, et « authenticated » les
                        // lui ouvrirait — c'est la nuance qui separe une
                        // surface d'administration d'une surface de client.
                        .pathMatchers("/api/v1/security-admin/**").hasRole("ADMIN")

                        .pathMatchers("/api/v1/admin/geo/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/admin/**").authenticated()

                        .anyExchange().authenticated()
                )
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none'; form-action 'self'"))
                        .frameOptions(frame -> frame.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // HSTS : un an, sous-domaines inclus. Sans cet en-tête, la
                        // toute première requête d'un client reste interceptable.
                        .hsts(hsts -> hsts
                                .maxAge(Duration.ofDays(365))
                                .includeSubdomains(true))
                )
                // L'ordre suit le coût croissant du contrôle : ce qui se tranche
                // sans I/O passe avant ce qui interroge Redis, qui passe avant la
                // vérification cryptographique.
                // L'enregistreur de violations est partage par tous les filtres
                // qui detectent. Sans lui, IpBlacklistWebFilter lisait une cle
                // qu'aucun code n'ecrivait : la liste noire ne se remplissait
                // jamais, et dix traversees de repertoire depuis la meme adresse
                // n'avaient aucune consequence au-dela du refus de chaque
                // requete prise isolement.
                .addFilterBefore(new PathTraversalWebFilter(clientIpResolver)
                        .avecEnregistreur(violationRecorder), SecurityWebFiltersOrder.FIRST)
                .addFilterAfter(new IpBlacklistWebFilter(sharedSecurityRedis, clientIpResolver), SecurityWebFiltersOrder.FIRST)
                .addFilterAfter(new GeoLocationWebFilter(geoLocationService, clientIpResolver), SecurityWebFiltersOrder.FIRST)
                // Maintenance : APRES la liste noire et la geolocalisation,
                // AVANT l'authentification.
                //
                // Apres les deux premiers, parce qu'une adresse bloquee n'a pas
                // a apprendre qu'un service est en travaux. Avant
                // l'authentification, parce qu'annoncer une coupure ne demande
                // pas de verifier un jeton : l'ordre inverse ferait repondre 401
                // a qui n'en a pas, ce qui est un message faux pendant une
                // maintenance.
                //
                // Ce filtre est la piece qui MANQUAIT : setting-service ecrivait
                // system.maintenance-mode et rien ne le lisait, donc une
                // maintenance activee ne coupait rien.
                .addFilterAfter(new MaintenanceWebFilter(sharedSecurityRedis), SecurityWebFiltersOrder.FIRST)
                .addFilterAfter(new RedisRateLimitWebFilter(gatewayRedis, clientIpResolver)
                        .avecEnregistreur(violationRecorder), SecurityWebFiltersOrder.FIRST)
                .addFilterAfter(new XssProtectionWebFilter(clientIpResolver)
                        .avecEnregistreur(violationRecorder), SecurityWebFiltersOrder.FIRST)
                .addFilterAfter(new BodyXssScannerWebFilter(clientIpResolver)
                        .avecEnregistreur(violationRecorder), SecurityWebFiltersOrder.FIRST)
                .addFilterAfter(new ApiVersionWebFilter(), SecurityWebFiltersOrder.FIRST)
                .addFilterAt(jwtWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        // `*` avec `allowCredentials` est refusé par la spécification CORS et par
        // Spring. On échoue au démarrage plutôt qu'à la première requête.
        if (origins.contains("*")) {
            throw new IllegalStateException(
                    "cors.allowed-origins ne peut pas valoir '*' : les identifiants "
                            + "sont autorisés, ce qui exige une liste d'origines explicite.");
        }

        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // Liste explicite : `X-Auth-User-Id` en est absent à dessein — c'est un
        // en-tête que seul le Gateway produit, l'autoriser en entrée inviterait
        // le navigateur à l'envoyer.
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "X-Requested-With",
                "X-CSRF-Token"
        ));
        configuration.setExposedHeaders(List.of(
                "Content-Type",
                "X-Rate-Limit-Limit",
                "X-Rate-Limit-Remaining",
                "Retry-After"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
