package com.abdatytch.auth_service.service.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Registre des envois dans Redis, avec expiration automatique.
 *
 * <p>Redis convient ici précisément parce que la donnée est <b>périssable</b> :
 * un accusé de réception arrive en quelques secondes à quelques minutes, et une
 * correspondance plus vieille que cela n'intéresse plus personne. Une table en
 * base demanderait une purge, c'est-à-dire une tâche de plus à écrire, à
 * surveiller, et à oublier.
 */
@Service
@Slf4j
public class RegistreDesEnvoisSmsRedis implements IRegistreDesEnvoisSms {

    private static final String PREFIXE = "sms:envoi:";

    private final ReactiveRedisTemplate<String, String> redis;
    private final Duration dureeDeVie;

    /**
     * @param redis le gabarit du projet, et non celui de Spring Boot.
     *              <p>Deux beans de ce type coexistent :
     *              {@code reactiveRedisTemplate} declaré par {@code RedisConfig},
     *              et {@code reactiveStringRedisTemplate} auto-configuré par
     *              Boot. Le reste du service s'en sort en <b>nommant son
     *              paramètre</b> comme le bean voulu — ce qui dépend de la
     *              conservation des noms de paramètres à la compilation, et
     *              échoue silencieusement sans elle. Le qualificatif est
     *              explicite : il dit lequel, et ne dépend de rien.
     */
    public RegistreDesEnvoisSmsRedis(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redis,
            @Value("${sms.registre.duree-de-vie-heures:48}") long dureeDeVieEnHeures) {
        this.redis = redis;
        this.dureeDeVie = Duration.ofHours(dureeDeVieEnHeures);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Une écriture sans date d'expiration serait une fuite de mémoire lente :
     * une entrée par SMS envoyé, pour toujours. Le TTL est donc posé dans le
     * <b>même appel</b> que la valeur, et non par un {@code expire} séparé qui
     * peut échouer seul et laisser une entrée orpheline — le dépôt a déjà eu à
     * réparer exactement cela dans {@code IpBlacklistFilter}.
     */
    @Override
    public Mono<Void> enregistrer(String resourceId, String numeroDeTelephone) {
        if (estVide(resourceId) || estVide(numeroDeTelephone)) {
            return Mono.empty();
        }
        return redis.opsForValue()
                .set(PREFIXE + resourceId, numeroDeTelephone, dureeDeVie)
                .then();
    }

    @Override
    public Mono<String> numeroDe(String resourceId) {
        if (estVide(resourceId)) {
            return Mono.empty();
        }
        return redis.opsForValue().get(PREFIXE + resourceId);
    }

    private static boolean estVide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}
