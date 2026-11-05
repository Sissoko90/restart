package com.abdatytch.interface_service.service.impl;

import com.abdatytch.commons.security.RevocationGlobale;
import com.abdatytch.interface_service.service.IDevTokenBlacklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Service de blacklist des tokens JWT pour les développeurs
 * Utilise Redis pour stocker les tokens révoqués
 */
@Service
public class DevTokenBlacklistServiceImpl implements IDevTokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(DevTokenBlacklistServiceImpl.class);
    
    private static final String TOKEN_BLACKLIST_PREFIX = "dev:token:blacklist:";
    private static final String DEVELOPER_REVOKED_PREFIX = "dev:revoked:";
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public DevTokenBlacklistServiceImpl(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> revokeToken(String tokenId, long expirationMs) {
        String key = TOKEN_BLACKLIST_PREFIX + tokenId;
        return redisTemplate.opsForValue()
                .set(key, "revoked", Duration.ofMillis(expirationMs))
                .doOnSuccess(v -> log.info("Token révoqué: {}", tokenId))
                .then();
    }

    @Override
    public Mono<Boolean> isTokenRevoked(String tokenId) {
        String key = TOKEN_BLACKLIST_PREFIX + tokenId;
        return redisTemplate.hasKey(key);
    }

    @Override
    public Mono<Void> revokeAllDeveloperTokens(String developerId) {
        String key = DEVELOPER_REVOKED_PREFIX + developerId;
        return redisTemplate.opsForValue()
                .set(key, String.valueOf(System.currentTimeMillis()), Duration.ofDays(7))
                .doOnSuccess(v -> log.info("Tous les tokens révoqués pour le développeur: {}", developerId))
                .then();
    }

    /**
     * Lit l'horodatage de révocation et le compare à l'émission du jeton.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * La méthode précédente, {@code hasDeveloperRevokedTokens}, faisait un
     * simple {@code hasKey} : elle <b>ignorait la valeur</b> que
     * {@code revokeAllDeveloperTokens} écrit juste au-dessus. Une révocation
     * globale aurait donc verrouillé le développeur pendant les <b>sept
     * jours</b> de vie de la clé, reconnexion comprise.
     *
     * <p>La règle vit dans {@code RevocationGlobale}, dans platform-commons :
     * {@code gateway-service/TokenRevocationChecker} lit la <b>même clé</b> et
     * portait la même erreur. Deux lecteurs d'une clé qui n'appliquent pas la
     * même règle, c'est ce que ce module partagé existe pour empêcher.
     */
    @Override
    public Mono<Boolean> revocationGlobalePosterieureA(String developerId, Instant emisLe) {
        String key = DEVELOPER_REVOKED_PREFIX + developerId;

        return redisTemplate.opsForValue().get(key)
                .map(horodatage -> RevocationGlobale.frappe(horodatage, emisLe))
                .doOnNext(frappe -> {
                    if (frappe) {
                        log.warn("Jeton de {} frappe par une revocation globale", developerId);
                    }
                })
                // Aucune cle : aucune revocation. `get` rend un Mono VIDE, et
                // sans ce repli la validation entiere rendrait vide — le
                // flatMap de l'appelant ne s'executerait jamais et la requete
                // serait perdue, ni acceptee ni refusee.
                .defaultIfEmpty(false);
    }
}
