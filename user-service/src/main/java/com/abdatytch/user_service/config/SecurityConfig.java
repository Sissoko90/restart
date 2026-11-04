package com.abdatytch.user_service.config;

import com.abdatytch.commons.security.CheminsPublics;
import com.abdatytch.user_service.security.AutorisationInterne;
import com.abdatytch.user_service.security.JwtTokenValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import reactor.core.publisher.Mono;

/**
 * Configuration de sécurité réactive pour WebFlux
 * Migré de Spring Security WebMVC vers WebFlux
 * PRÉSERVE TOUTE LA LOGIQUE : actuator public, internal/SERVICE, admin/ADMIN, JWT OAuth2
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {
    
    private final CorsConfigurationSource corsConfigurationSource;
    private final JwtTokenValidator jwtTokenValidator;
    private final AutorisationInterne autorisationInterne;
    
    public SecurityConfig(CorsConfigurationSource corsConfigurationSource,
                         JwtTokenValidator jwtTokenValidator,
                         AutorisationInterne autorisationInterne) {
        this.corsConfigurationSource = corsConfigurationSource;
        this.jwtTokenValidator = jwtTokenValidator;
        this.autorisationInterne = autorisationInterne;
    }
    
    /**
     * SecurityWebFilterChain réactive pour WebFlux
     * PRÉSERVE TOUTE LA LOGIQUE de l'ancien SecurityFilterChain
     */
    /** Les memes chemins publics que le filtre d'acces. Voir CheminsPublics. */
    private static final CheminsPublics CHEMINS_PUBLICS = CheminsPublics.sondes()
            .pageDErreur()
            .documentationViaPasserelle()
            .construire();

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeExchange(exchanges -> exchanges
                // /actuator/** N'EST PLUS permitAll.
                //
                // La regle ouvrait env, httptrace et loggers a tout appelant.
                // Avec le filtre d'acces Gateway qui exemptait /actuator/ en
                // bloc, les deux couches etaient ouvertes en meme temps : ces
                // endpoints etaient joignables en direct, sans jeton et sans
                // signature. httptrace livre les en-tetes des derniers
                // echanges, loggers est modifiable par POST.
                //
                // Les trois sondes restent publiques, nommement : elles sont
                // interrogees par Consul et Prometheus, qui ne portent pas de
                // jeton.
                // Les chemins publics viennent de la MEME source que le filtre
                // d'acces (CheminsPublics, platform-commons) : un chemin n'est
                // joignable que s'il franchit les DEUX couches, et deux listes
                // tenues separement se contredisent en silence.
                //
                // La documentation OpenAPI y figure, et c'est voulu : signature de
                // passerelle EXIGEE, jeton non. Permettre ici n'ouvre rien tant que
                // le filtre d'acces exige la signature — l'attaquer sur le port du
                // service en direct echoue. C'est cette asymetrie qui fait de la
                // passerelle la seule porte, en entree comme en sortie.
                .pathMatchers(CHEMINS_PUBLICS.motifsPermitAll()).permitAll()
                .pathMatchers("/actuator/**").hasRole("ADMIN")
                // Les fichiers ne sont PAS publics : le chemin porte l'identifiant
                // du propriétaire, et il vient du client. En permitAll, remplacer
                // ce segment par celui d'un autre suffisait à lire ses fichiers,
                // sans authentification. FileReactiveController vérifie en plus
                // que l'appelant est bien le propriétaire.
                .pathMatchers(HttpMethod.GET, "/files/**").authenticated()
                // /internal/** : permitAll ICI, et secret verifie dans le
                // controleur — comme auth-service et interface-service.
                //
                // La regle precedente exigeait le role SERVICE, et ce role
                // n'etait accorde que par ServiceTokenFilter, qui gardait les
                // chemins commencant par /api/v1/internal/. Or ce service ne
                // sert pas ce prefixe : la passerelle le retire avant
                // transmission, et un appel de service a service ne passe de
                // toute facon pas par elle. Le filtre ne se declenchait donc
                // JAMAIS, personne n'obtenait le role, et toute la surface
                // inter-services etait refusee.
                //
                // Deux mecanismes pour une meme exigence, dont aucun ne
                // fonctionnait : le filtre visait le mauvais chemin, et cette
                // regle attendait ce que le filtre n'accordait pas. Le filtre a
                // ete supprime ; InternalUserReactiveController verifie
                // X-Internal-Secret a temps constant et rend 404 sur refus.
                //
                // La decision reste DANS la chaine, et c'est le point : un
                // `permitAll` avec verification dans le controleur protege
                // l'endpoint existant et lui seul. Le prochain endpoint ajoute
                // sous /internal/** sans reprendre la verification serait
                // ouvert, sans que rien ne le signale.
                //
                // AutorisationInterne compare X-Internal-Secret a temps
                // constant, et refuse tout si aucun secret n'est configure —
                // fermé a la requete, pas au demarrage.
                .pathMatchers("/internal/**").access(autorisationInterne)
                .pathMatchers("/admin/**").hasRole("ADMIN")
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(reactiveJwtAuthenticationConverter()))
            );
        
        return http.build();
    }
    
    /**
     * Converter réactif pour JWT Authentication
     * Gère les rôles multiples (array) ou un seul rôle (string)
     */
    private org.springframework.core.convert.converter.Converter<org.springframework.security.oauth2.jwt.Jwt, 
            reactor.core.publisher.Mono<org.springframework.security.authentication.AbstractAuthenticationToken>> 
            reactiveJwtAuthenticationConverter() {
        
        return jwt -> {
            // Extraire les rôles du JWT (peut être string ou List)
            var authorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
            
            Object rolesClaim = jwt.getClaim("roles");
            if (rolesClaim != null) {
                if (rolesClaim instanceof String) {
                    // Un seul rôle
                    String role = (String) rolesClaim;
                    // Ajouter ROLE_ prefix si absent
                    if (!role.startsWith("ROLE_")) {
                        role = "ROLE_" + role;
                    }
                    authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(role));
                } else if (rolesClaim instanceof java.util.List) {
                    // Plusieurs rôles
                    @SuppressWarnings("unchecked")
                    java.util.List<String> roles = (java.util.List<String>) rolesClaim;
                    for (String role : roles) {
                        // Ajouter ROLE_ prefix si absent
                        if (!role.startsWith("ROLE_")) {
                            role = "ROLE_" + role;
                        }
                        authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority(role));
                    }
                }
            }
            
            // Créer l'authentication token
            var authToken = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                jwt, authorities, jwt.getSubject()
            );
            
            return Mono.just(authToken);
        };
    }
    
    /**
     * JwtDecoder réactif utilisant le JwtTokenValidator existant
     */
    /**
     * Décodeur JWT réactif.
     *
     * <p>L'ancienne version enveloppait le validateur <b>bloquant</b> dans un
     * {@code Mono.fromCallable} sans {@code subscribeOn} : le corps s'exécutait donc
     * sur le thread souscripteur, c'est-à-dire l'EventLoop Netty, à <b>chaque requête
     * authentifiée</b>. On chaîne désormais directement la validation réactive.
     */
    @Bean
    public org.springframework.security.oauth2.jwt.ReactiveJwtDecoder reactiveJwtDecoder() {
        return token -> jwtTokenValidator.validateTokenReactive(token)
                .map(claims -> {
                    java.util.Map<String, Object> claimsMap = new java.util.HashMap<>(claims);

                    return new org.springframework.security.oauth2.jwt.Jwt(
                            token,
                            claims.getIssuedAt() != null ? claims.getIssuedAt().toInstant() : null,
                            claims.getExpiration() != null ? claims.getExpiration().toInstant() : null,
                            java.util.Map.of("alg", "RS256", "typ", "JWT"),
                            claimsMap);
                })
                // BadJwtException, et NON JwtException : c'est la difference
                // entre 401 et 500.
                //
                // LE DEFAUT CORRIGE. Spring Security ne rend un 401 que pour un
                // BadJwtException ; tout autre JwtException devient un
                // AuthenticationServiceException, donc un 500. Verifie dans le
                // bytecode de JwtReactiveAuthenticationManager.onError
                // (spring-security-oauth2-resource-server 7.1.1), pas suppose.
                //
                // Ce service rendait donc 500 a TOUT jeton refuse — expire
                // compris, c'est-a-dire le cas le plus courant qui existe,
                // puisque chaque jeton finit par expirer. Un client qui
                // declenche son rafraichissement sur 401 ne le declenchait
                // jamais. Et GlobalExceptionHandler declare bien 401 pour
                // InvalidTokenException : il n'etait jamais atteint, l'erreur
                // survenant dans le filtre Security, en amont du controleur.
                //
                // La distinction porte sur le TYPE et non sur un message : un
                // statut lie au texte d'un message change des qu'on reformule le
                // message.
                .onErrorMap(com.abdatytch.user_service.exception.InvalidTokenException.class,
                        erreur -> new org.springframework.security.oauth2.jwt.BadJwtException(
                                "Jeton refuse", erreur))
                // Tout le reste reste une erreur SERVEUR, et c'est voulu :
                // ValidationIndisponibleException (JWKS injoignable) et une
                // panne de la liste de revocation ne disent rien du jeton de
                // l'appelant. Les habiller en 401 ferait deconnecter tout le
                // monde au moment ou la plateforme est deja en difficulte.
                .onErrorMap(erreur -> !(erreur instanceof org.springframework.security.oauth2.jwt.JwtException),
                        erreur -> new org.springframework.security.oauth2.jwt.JwtException(
                                "Validation du jeton impossible", erreur));
    }
}
