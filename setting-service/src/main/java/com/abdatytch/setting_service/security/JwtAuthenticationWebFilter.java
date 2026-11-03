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

                    log.debug(" JWT valide pour {} (rôle {})", claims.getSubject(), role);

                    return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                })
                .onErrorResume(error -> {
                    // Token invalide : on poursuit sans authentification.
                    // Spring Security refusera l'accès aux routes protégées.
                    log.warn(" JWT rejeté : {}", error.getMessage());
                    return chain.filter(exchange);
                });
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .keyLocator(jwksKeyProvider.keyLocator())
                .requireIssuer(expectedIssuer)
                .requireAudience(expectedAudience)
                .build()
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
