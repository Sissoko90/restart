package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Implémentation du service de blacklist des tokens
 * Utilise Redis pour stocker les tokens révoqués
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    
    private static final String BLACKLIST_PREFIX = "token:blacklist:";

    @Override
    public Mono<Boolean> blacklistToken(String token, Duration ttl) {
        String key = BLACKLIST_PREFIX + hashToken(token);
        
        return redisTemplate.opsForValue()
            .set(key, "revoked", ttl)
            .doOnSuccess(success -> {
                if (Boolean.TRUE.equals(success)) {
                    log.info("Token blacklisted for {} seconds", ttl.getSeconds());
                }
            })
            .onErrorResume(e -> {
                log.error("Failed to blacklist token", e);
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Boolean> isBlacklisted(String token) {
        String key = BLACKLIST_PREFIX + hashToken(token);
        
        return redisTemplate.hasKey(key)
            // Le piege du Mono vide, septieme instance dans ce depot.
            //
            // `hasKey` peut se terminer VIDE — ce n'est pas une erreur, donc
            // `onErrorResume` ne s'applique pas. Le vide traverserait le
            // `flatMap` de JwtAuthenticationWebFilter sans le declencher : la
            // requete ne serait NI transmise NI repondue, et le client
            // attendrait son delai d'expiration.
            //
            // Le defaut est pose sur le Mono<Boolean>, et non sur le Mono<Void>
            // de l'appelant : l'y poser declencherait le repli a chaque requete
            // acceptee, `chain.filter()` se terminant vide en cas de succes.
            //
            // Valeur `true`, donc REFUS, conforme a la politique de la
            // plateforme pour la revocation : c'est l'identite du porteur qui
            // est en cause, et accepter un jeton peut-etre revoque revient a
            // accepter quelqu'un dont on ne sait plus s'il a le droit.
            .defaultIfEmpty(true)
            .onErrorResume(e -> {
                log.error("Failed to check token blacklist", e);
                // En cas d'erreur Redis, on refuse par sécurité
                return Mono.just(true);
            });
    }

    @Override
    public Mono<Boolean> removeFromBlacklist(String token) {
        String key = BLACKLIST_PREFIX + hashToken(token);
        
        return redisTemplate.delete(key)
            .map(count -> count > 0)
            .onErrorResume(e -> {
                log.error("Failed to remove token from blacklist", e);
                return Mono.just(false);
            });
    }
    
    /**
     * Hash le token pour éviter de stocker le token complet en Redis
     */
    private String hashToken(String token) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Failed to hash token", e);
            return String.valueOf(token.hashCode());
        }
    }
}
