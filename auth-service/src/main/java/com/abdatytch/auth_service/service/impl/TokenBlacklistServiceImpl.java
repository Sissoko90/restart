package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.service.ITokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Implémentation réactive du service de blacklist des tokens JWT
 * Utilise ReactiveRedisTemplate pour un stockage distribué non-bloquant
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBlacklistServiceImpl implements ITokenBlacklistService {
    
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final com.abdatytch.auth_service.metrics.AuthMetrics authMetrics;
    
    /** Portee de la revocation, pour la metrique. Valeurs BORNEES. */
    static final String PORTEE_JETON = "jeton";
    static final String PORTEE_TOUS_LES_JETONS = "tous_les_jetons_du_compte";

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    private static final String USER_REVOKED_PREFIX = "jwt:user:revoked:";
    
    @Override
    public Mono<Void> revokeToken(String tokenId, long expirationMs) {
        // La metrique de revocation etait DEFINIE dans AuthMetrics et appelee
        // par personne. Micrometer ne cree la serie qu'au premier appel : elle
        // n'apparaissait donc pas sur /actuator/prometheus, et rien ne
        // distinguait cette absence d'une absence de revocation.
        //
        // Posee ici, chez le detenteur : les deux portees de revocation
        // passent par cette classe et nulle part ailleurs.
        authMetrics.recordTokenRevocation(PORTEE_JETON);
        String key = BLACKLIST_PREFIX + tokenId;
        
        return reactiveRedisTemplate.opsForValue()
            .set(key, "revoked", Duration.ofMillis(expirationMs))
            .doOnSuccess(result -> log.info("Token révoqué avec succès: {} (TTL: {}ms)", tokenId, expirationMs))
            .doOnError(e -> log.error("Erreur lors de la révocation du token: {}", tokenId, e))
            .onErrorMap(e -> new RuntimeException("Échec de la révocation du token", e))
            .then();
    }
    
    @Override
    public Mono<Boolean> isTokenRevoked(String tokenId) {
        String key = BLACKLIST_PREFIX + tokenId;
        
        return reactiveRedisTemplate.hasKey(key)
            .doOnError(e -> log.error("Erreur lors de la vérification de révocation du token: {}", tokenId, e))
            .onErrorReturn(false);
    }
    
    @Override
    public Mono<Void> revokeAllUserTokens(String userId) {
        // Revocation de TOUS les jetons d'un compte : operation large, comptee
        // separement. Elle survient a un changement de mot de passe, a un
        // effacement, ou sur decision d'administration.
        authMetrics.recordTokenRevocation(PORTEE_TOUS_LES_JETONS);
        String key = USER_REVOKED_PREFIX + userId;
        
        return reactiveRedisTemplate.opsForValue()
            .set(key, String.valueOf(System.currentTimeMillis()), Duration.ofHours(24))
            .doOnSuccess(result -> log.warn("Tous les tokens de l'utilisateur {} ont été révoqués", userId))
            .doOnError(e -> log.error("Erreur lors de la révocation des tokens de l'utilisateur: {}", userId, e))
            .onErrorMap(e -> new RuntimeException("Échec de la révocation des tokens utilisateur", e))
            .then();
    }
    
    @Override
    public Mono<Boolean> hasRevokedTokens(String userId) {
        String key = USER_REVOKED_PREFIX + userId;
        
        return reactiveRedisTemplate.hasKey(key)
            .doOnError(e -> log.error("Erreur lors de la vérification des tokens révoqués de l'utilisateur: {}", userId, e))
            .onErrorReturn(false);
    }
}
