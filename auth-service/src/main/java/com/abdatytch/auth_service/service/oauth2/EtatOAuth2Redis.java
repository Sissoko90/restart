package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.enums.OAuthProvider;
import com.abdatytch.auth_service.exception.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * La valeur anti-falsification du parcours OAuth2, enfin appliquee.
 *
 * <h2>Ce qui existait, et ce qui manquait</h2>
 *
 * {@code AppConstants.OAUTH2_STATE_PREFIX} etait defini et
 * {@code OAuth2CallbackRequest.state} portait le commentaire « Pour la
 * validation CSRF ». Ni l'un ni l'autre n'etait utilise : l'URL d'autorisation
 * ne contenait aucun parametre {@code state}, et le rappel ne verifiait rien.
 * La protection etait ecrite, presente, et jamais atteinte — le meme motif que
 * les autres defauts corriges dans ce depot.
 *
 * <h2>Ce que son absence permettait</h2>
 *
 * Sans {@code state}, un tiers peut declencher le parcours avec <b>son</b> code
 * d'autorisation et faire aboutir le rappel dans le navigateur de la victime.
 * Selon le parcours, la victime se retrouve connectee au compte de l'attaquant —
 * et y depose ce qu'elle croit deposer chez elle — ou bien le compte social de
 * l'attaquant se trouve lie au compte de la victime, ce qui lui en donne ensuite
 * l'acces permanent. C'est le <i>login CSRF</i>, et c'est precisement la raison
 * d'etre du parametre.
 *
 * <h2>Pourquoi Redis et non une session</h2>
 *
 * Le service est sans session et tourne en plusieurs instances. Le rappel
 * n'arrive pas forcement sur l'instance qui a produit l'URL : une valeur gardee
 * en memoire serait inconnue de l'instance qui recoit le retour, et la
 * verification echouerait une fois sur deux a deux instances. Le defaut serait
 * intermittent, donc attribue au reseau.
 *
 * <h2>Usage unique</h2>
 *
 * La valeur est <b>supprimee</b> au moment de la verification, pas seulement
 * lue. Une valeur rejouable laisserait un code d'autorisation intercepte
 * utilisable plusieurs fois.
 */
@Slf4j
@Component
public class EtatOAuth2Redis {

    /** 32 octets : assez pour qu'une valeur ne soit pas devinable. */
    private static final int OCTETS = 32;

    private static final SecureRandom ALEA = new SecureRandom();

    private final ReactiveRedisTemplate<String, String> redis;
    private final Duration validite;

    /**
     * @param redis qualifie explicitement : deux {@code ReactiveRedisTemplate<String, String>}
     *        existent dans ce contexte — celui de {@code RedisConfig} et celui
     *        de l'auto-configuration. Sans qualificatif, le contexte ne demarre
     *        pas. Meme choix que {@code RegistreDesEnvoisSmsRedis}.
     */
    public EtatOAuth2Redis(@Qualifier("reactiveRedisTemplate")
                           ReactiveRedisTemplate<String, String> redis,
                           @Value("${oauth2.state.validity-minutes:10}") long validiteEnMinutes) {
        this.redis = redis;
        this.validite = Duration.ofMinutes(validiteEnMinutes);
    }

    /**
     * Produit une valeur et la retient le temps du parcours.
     *
     * <p>Le fournisseur est memorise avec elle : au retour, on verifie non
     * seulement que la valeur a bien ete emise par nous, mais qu'elle l'a ete
     * <b>pour ce fournisseur</b>. Sans cela, une valeur obtenue pour un
     * fournisseur serait recevable au rappel d'un autre.
     *
     * <p>L'echeance n'est pas une commodite : sans elle, chaque parcours
     * abandonne — et il y en a beaucoup — laisserait une cle dans Redis pour
     * toujours.
     */
    public Mono<String> creer(OAuthProvider fournisseur) {
        byte[] octets = new byte[OCTETS];
        ALEA.nextBytes(octets);
        String etat = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);

        return redis.opsForValue()
                .set(cle(etat), fournisseur.getProviderName(), validite)
                .flatMap(ecrit -> Boolean.TRUE.equals(ecrit)
                        ? Mono.just(etat)
                        // Un echec d'ecriture rendrait une valeur que la
                        // verification refusera ensuite : la personne irait
                        // jusqu'a Apple pour se voir refuser au retour. Mieux
                        // vaut refuser maintenant, avec la vraie cause.
                        : Mono.error(new AuthException(ErrorCode.SERVICE_UNAVAILABLE)))
                // set() ne rend pas de valeur si Redis est injoignable : la
                // chaine se terminerait vide, et l'URL d'autorisation ne serait
                // ni produite ni refusee.
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.SERVICE_UNAVAILABLE)));
    }

    /**
     * Verifie la valeur rendue par le fournisseur, et la consomme.
     *
     * @throws AuthException si la valeur est absente, inconnue, expiree, deja
     *         utilisee, ou emise pour un autre fournisseur
     */
    public Mono<Void> consommer(String etat, OAuthProvider fournisseur) {
        if (etat == null || etat.isBlank()) {
            // Un rappel sans state n'est pas un rappel que nous avons initie.
            log.warn("Rappel OAuth2 {} sans parametre state : refuse",
                    fournisseur.getProviderName());
            return Mono.error(new AuthException(ErrorCode.INVALID_REQUEST));
        }
        return redis.opsForValue().getAndDelete(cle(etat))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Rappel OAuth2 {} avec un state inconnu ou deja utilise",
                            fournisseur.getProviderName());
                    return Mono.error(new AuthException(ErrorCode.INVALID_REQUEST));
                }))
                .flatMap(fournisseurAttendu -> {
                    if (!fournisseur.getProviderName().equals(fournisseurAttendu)) {
                        log.warn("Rappel OAuth2 {} avec un state emis pour {}",
                                fournisseur.getProviderName(), fournisseurAttendu);
                        return Mono.error(new AuthException(ErrorCode.INVALID_REQUEST));
                    }
                    return Mono.empty();
                })
                .then();
    }

    private static String cle(String etat) {
        return AppConstants.OAUTH2_STATE_PREFIX + etat;
    }
}
