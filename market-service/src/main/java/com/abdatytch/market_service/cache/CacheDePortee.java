package com.abdatytch.market_service.cache;

import com.abdatytch.market_service.security.PorteeDuCatalogue;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Le cache de la portee du catalogue.
 *
 * <h2>Pourquoi un cache ici et nulle part ailleurs</h2>
 *
 * Calculer une portee demande DEUX appels sortants — la boutique en base, le
 * pays chez user-service — et il en faut une a chaque requete de catalogue,
 * c'est-a-dire sur l'ecran le plus consulte du service. Sans cache, afficher
 * une page de vingt produits coute deux allers-retours de plus que necessaire.
 *
 * <h2>Ce qui est mis en cache, et ce qui ne l'est PAS</h2>
 *
 * La portee, jamais les produits. Un cache de produits devrait etre invalide a
 * chaque moderation, a chaque changement de prix et a chaque rupture de stock ;
 * il montrerait des prix perimes, ce qui se paie en litiges. La portee, elle,
 * ne bouge qu'a l'approbation d'une boutique ou a un demenagement.
 *
 * <h2>Le TTL est COURT, et l'approbation evince</h2>
 *
 * Deux mecanismes, et le second est celui qui compte : approuver une boutique
 * evince la clef, donc le vendeur voit ses droits elargis immediatement. Le TTL
 * n'est que le filet — il borne la duree d'une incoherence si l'eviction
 * echoue, au lieu de la laisser indefinie.
 *
 * <h2>Une panne de Redis ne REFUSE rien</h2>
 *
 * Elle fait recalculer. Un cache dont la panne casse le service est pire que
 * pas de cache : il ajoute une dependance au chemin critique sans l'annoncer.
 */
@Component
public class CacheDePortee {

    private static final Logger log = LoggerFactory.getLogger(CacheDePortee.class);

    /** Le prefixe, nomme une fois : deux services sur le meme Redis se croisent. */
    static final String PREFIXE = "market:portee:";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper jackson;
    private final Duration duree;

    public CacheDePortee(ReactiveStringRedisTemplate redis, ObjectMapper jackson,
                         @Value("${market.cache.portee-secondes:120}") long secondes) {
        this.redis = redis;
        this.jackson = jackson;
        this.duree = Duration.ofSeconds(secondes);
    }

    /** La portee en cache, ou vide. */
    public Mono<PorteeDuCatalogue> lire(String authUserId, boolean administrateur) {
        return redis.opsForValue().get(PREFIXE + authUserId)
                .flatMap(json -> {
                    try {
                        Enregistrement e = jackson.readValue(json, Enregistrement.class);
                        return Mono.just(new PorteeDuCatalogue(authUserId, e.pays(),
                                e.boutiqueId() == null ? null : UUID.fromString(e.boutiqueId()),
                                e.professionnel(), administrateur));
                    } catch (Exception illisible) {
                        // Une valeur illisible vient d'un changement de forme
                        // entre deux versions. On la jette au lieu de refuser :
                        // le recalcul rend la bonne valeur, et la prochaine
                        // ecriture remplace l'ancienne.
                        log.debug("Entree de cache illisible, recalcul : {}",
                                illisible.getMessage());
                        return Mono.empty();
                    }
                })
                .onErrorResume(panne -> {
                    log.warn("Redis indisponible en lecture ({}) : la portee est "
                            + "recalculee", panne.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /** Ecrit la portee. L'echec est journalise, jamais propage. */
    public Mono<Void> ecrire(PorteeDuCatalogue portee) {
        String json;
        try {
            json = jackson.writeValueAsString(new Enregistrement(portee.pays(),
                    portee.boutiqueId() == null ? null : portee.boutiqueId().toString(),
                    portee.professionnel()));
        } catch (Exception impossible) {
            log.warn("Portee non serialisable : {}", impossible.getMessage());
            return Mono.empty();
        }
        return redis.opsForValue().set(PREFIXE + portee.authUserId(), json, duree)
                .onErrorResume(panne -> {
                    log.warn("Redis indisponible en ecriture ({}) : la portee sera "
                            + "recalculee a la prochaine requete",
                            panne.getClass().getSimpleName());
                    return Mono.just(false);
                })
                .then();
    }

    /**
     * Evince la portee d'un compte.
     *
     * <p>Appelee a l'approbation, au rejet et a la suspension d'une boutique.
     * C'est le mecanisme qui compte : sans elle, un vendeur approuve
     * attendrait l'expiration pour voir ses droits.
     */
    public Mono<Void> evincer(String authUserId) {
        if (authUserId == null || authUserId.isBlank()) {
            return Mono.empty();
        }
        return redis.delete(PREFIXE + authUserId)
                .onErrorResume(panne -> {
                    // Journalise en WARN et non en DEBUG : une eviction ratee
                    // laisse une personne avec des droits perimes jusqu'au TTL,
                    // ce qui merite d'etre visible.
                    log.warn("Eviction de portee impossible ({}) : le TTL borne "
                            + "l'incoherence", panne.getClass().getSimpleName());
                    return Mono.just(0L);
                })
                .then();
    }

    /**
     * Ce qui est serialise.
     *
     * <p>Le role d'administrateur n'y figure PAS : il vient du jeton, change a
     * la reconnexion, et le mettre en cache ferait survivre un pouvoir retire.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Enregistrement(String pays, String boutiqueId, boolean professionnel) {
    }
}
