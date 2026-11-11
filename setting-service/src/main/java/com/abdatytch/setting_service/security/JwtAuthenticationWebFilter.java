package com.abdatytch.setting_service.security;

import com.abdatytch.commons.security.JwksKeyProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;

/**

 * Validation du JWT émis par interface-service.
 *
 * <p>Conforme à docs/JWT-CONTRACT.md : clé résolue par le {@code kid} de l'en-tête JWS,
 * émetteur contrôlé, audience du service exigée, rôle dérivé des claims validés
 * (jamais d'un header HTTP).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationWebFilter implements WebFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwksKeyProvider jwksKeyProvider;

    @Value("${jwt.issuer:interface-service}")
    private String expectedIssuer;

    @Value("${jwt.audience:setting-service}")
    private String expectedAudience;

    /**
     * L'analyseur JWT, construit UNE fois.
     *
     * <p>{@code Jwts.parser()...build()} etait appele a chaque requete portant
     * un jeton. Or {@code build()} de jjwt fait une recherche
     * {@link java.util.ServiceLoader}, qui <b>lit des JAR sur le disque</b> :
     * un appel bloquant sur une EventLoop, sur le chemin de chaque requete
     * authentifiee.
     *
     * <p>Cacher l'analyseur est sur : {@code keyLocator()} resout la cle au
     * moment de l'analyse, par le {@code kid} de l'en-tete JWS, donc la rotation
     * de cles continue de fonctionner. Voir
     * {@code media-service/JwtAuthenticationWebFilter} pour la trace qui l'a
     * revele.
     */
    private volatile io.jsonwebtoken.JwtParser parser;

    /**
     * Construit l'analyseur apres l'injection des {@code @Value}.
     *
     * <p>Et non dans le constructeur, ou l'emetteur et l'audience attendus
     * seraient encore nuls.
     */
    @jakarta.annotation.PostConstruct
    void construireLAnalyseur() {
        parser = Jwts.parser()
                .keyLocator(entete -> jwksKeyProvider.keyLocator().locate(entete))
                .requireIssuer(expectedIssuer)
                .requireAudience(expectedAudience)
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return chain.filter(exchange);
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        return Mono.fromCallable(() -> parse(token))
                .flatMap(claims -> {
                    String role = extractRole(claims);
                    var authentication = new UsernamePasswordAuthenticationToken(
                            claims.getSubject(),
                            null,
                            List.of(new SimpleGrantedAuthority(normalizeRole(role))));

                    log.debug("JWT valide pour {} (rôle {})", claims.getSubject(), role);

                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                })
                .onErrorResume(error -> {
                    // Token invalide : on poursuit sans authentification.
                    // Spring Security refusera l'accès aux routes protégées.
                    log.warn("JWT rejeté : {}", error.getMessage());
                    return chain.filter(exchange);
                });
    }

    private Claims parse(String token) {
        // L'analyseur construit au demarrage : le reconstruire ici ferait une
        // lecture disque sur une EventLoop. Voir le champ.
        return parser
                .parseSignedClaims(token)
                .getPayload();
    }

    /**

     * Ordre normatif défini par docs/JWT-CONTRACT.md : primaryRole, puis roles, puis role.
     */
    private String extractRole(Claims claims) {
        Object primaryRole = claims.get("primaryRole");
        if (primaryRole instanceof String role && !role.isBlank()) {
            return role;
        }

        Object roles = claims.get("roles");
        if (roles instanceof String role && !role.isBlank()) {
            return role;
        }
        if (roles instanceof Collection<?> collection && !collection.isEmpty()) {
            Object first = collection.iterator().next();
            if (first != null) {
                return first.toString();
            }
        }

        Object role = claims.get("role");
        return role != null ? role.toString() : "USER";
    }

    private String normalizeRole(String role) {
        return role.startsWith("ROLE_") ? role : "ROLE_" + role;
    }
}
