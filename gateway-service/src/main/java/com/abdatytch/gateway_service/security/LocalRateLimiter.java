package com.abdatytch.gateway_service.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**

 * Limiteur de débit en mémoire, utilisé comme filet de sécurité quand Redis est
 * injoignable.
 *
 * <p><b>Pourquoi.</b> Le limiteur distribué laissait passer toutes les requêtes
 * en cas de panne Redis. C'est le pire moment pour retirer la protection : une
 * plateforme sous attaque voit d'abord tomber ses dépendances. Ce limiteur ne
 * remplace pas le compteur distribué — il ne compte que pour l'instance
 * courante, donc N instances autorisent N fois la limite — mais il transforme
 * une absence totale de plafond en un plafond dégradé.
 *
 * <p><b>Borne mémoire.</b> Le cache est plafonné : sans cela, un attaquant
 * disposant de nombreuses adresses transformerait le limiteur lui-même en
 * fuite mémoire. Au-delà du plafond, le cache est vidé plutôt que de croître —
 * une remise à zéro périodique des compteurs est un moindre mal comparé à un
 * dépassement de mémoire.
 */
public class LocalRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LocalRateLimiter.class);

    private static final int MAX_TRACKED_KEYS = 100_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Duration window;

    public LocalRateLimiter(Duration window) {
        this.window = window;
    }

    /** @return {@code true} si la requête est autorisée. */
    public boolean tryConsume(String key, int limit) {
        if (buckets.size() >= MAX_TRACKED_KEYS) {
            log.warn("Cache du limiteur local saturé ({} clés) — remise à zéro", buckets.size());
            buckets.clear();
        }
        return buckets.computeIfAbsent(key + ":" + limit, ignored -> newBucket(limit))
                .tryConsume(1);
    }

    private Bucket newBucket(int limit) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit)
                .refillIntervally(limit, window)
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    /** Exposé pour les tests : nombre de clés actuellement suivies. */
    int trackedKeys() {
        return buckets.size();
    }
}
