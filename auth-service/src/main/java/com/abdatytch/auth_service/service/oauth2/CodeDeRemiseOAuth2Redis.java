package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.OAuth2AuthResponse;
import com.abdatytch.auth_service.exception.AuthException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Le code a usage unique qui remplace les jetons dans l'URL de redirection.
 *
 * <h2>Ce qui etait en place</h2>
 *
 * Au retour du fournisseur, {@code urlDeRedirectionApresRappel} construisait :
 *
 * <pre>
 * {frontendUrl}/auth/callback?success=true&amp;accessToken=...&amp;refreshToken=...
 * </pre>
 *
 * <p>Le jeton d'acces <b>et</b> le jeton de rafraichissement voyageaient donc
 * dans la chaine de requete d'une redirection de navigateur. Et une chaine de
 * requete n'est pas un canal prive :
 *
 * <ul>
 *   <li>elle entre dans l'<b>historique</b> du navigateur, ou elle reste ;</li>
 *   <li>elle est ecrite dans les <b>journaux d'acces</b> de tout proxy, de tout
 *       CDN et du serveur du frontal — des endroits ou personne ne cherche un
 *       secret, et dont la duree de conservation se compte en mois ;</li>
 *   <li>elle part dans l'en-tete <b>{@code Referer}</b> des requetes emises par
 *       la page qui vient de se charger, donc vers chaque tiers qu'elle
 *       appelle ;</li>
 *   <li>elle se retrouve dans ce qu'on copie-colle pour signaler un probleme.</li>
 * </ul>
 *
 * <p>Le jeton de rafraichissement est le plus grave des deux : il suffit a
 * obtenir des jetons d'acces neufs pendant des jours, sans mot de passe ni
 * second facteur.
 *
 * <h2>Ce qui le remplace</h2>
 *
 * Un code aleatoire a usage unique et a vie courte. La redirection ne porte plus
 * que ce code ; le frontal l'echange immediatement, en <b>POST</b>, contre les
 * jetons rendus dans le corps de la reponse. Un corps de requete ne va ni dans
 * l'historique, ni dans les journaux d'acces, ni dans le {@code Referer}.
 *
 * <p>C'est la forme du flot d'autorisation OAuth2 appliquee a notre propre
 * frontal — et pour la meme raison que celle qui l'a fait adopter : un secret ne
 * traverse pas une barre d'adresse.
 *
 * <h2>Pourquoi le profil incomplet est traite pareil</h2>
 *
 * Cette branche transportait {@code tempToken} dans l'URL. Il est plus limite —
 * il ne porte que {@code ROLE_OAUTH2_TEMP} — mais il permet de completer un
 * profil, donc de prendre la main sur un compte en cours de creation. Un seul
 * chemin pour les deux cas evite d'avoir a se souvenir lequel est protege.
 *
 * <h2>Usage unique, et echeance courte</h2>
 *
 * Le code est <b>supprime</b> a la lecture, pas seulement lu : un code rejouable
 * laisserait un attaquant qui a vu l'URL — dans un journal, dans un
 * {@code Referer} — obtenir les jetons a son tour. C'est le defaut que cette
 * classe corrige ; le reproduire sur le code lui-meme n'aurait aucun sens.
 *
 * <p>L'echeance est de l'ordre de la minute parce que l'echange suit la
 * redirection immediatement. Elle n'est pas une commodite : sans elle, chaque
 * parcours ou l'onglet est ferme avant l'echange laisserait des jetons
 * utilisables dans Redis pour toujours.
 */
@Slf4j
@Component
public class CodeDeRemiseOAuth2Redis {

    /** 32 octets : la meme taille que le state, et pour la meme raison. */
    private static final int OCTETS = 32;

    private static final SecureRandom ALEA = new SecureRandom();

    private final ReactiveRedisTemplate<String, String> redis;
    private final ObjectMapper json;
    private final Duration validite;

    /**
     * @param redis qualifie explicitement : deux
     *        {@code ReactiveRedisTemplate<String, String>} existent dans ce
     *        contexte — celui de {@code RedisConfig} et celui de
     *        l'auto-configuration. Sans qualificatif, le contexte ne demarre
     *        pas. Meme choix que {@code EtatOAuth2Redis}.
     */
    public CodeDeRemiseOAuth2Redis(@Qualifier("reactiveRedisTemplate")
                                   ReactiveRedisTemplate<String, String> redis,
                                   ObjectMapper json,
                                   @Value("${oauth2.remise.validity-seconds:120}")
                                   long validiteEnSecondes) {
        this.redis = redis;
        this.json = json;
        this.validite = Duration.ofSeconds(validiteEnSecondes);
    }

    /**
     * Retient la reponse et rend le code qui la designe.
     *
     * @param reponse la reponse complete, jetons compris
     * @return le code a placer dans la redirection
     * @throws AuthException si Redis est injoignable. Refuser ici plutot que
     *         rendre un code que l'echange ne trouvera pas : la personne verrait
     *         la connexion aboutir, puis le frontal echouerait sans cause
     *         lisible.
     */
    public Mono<String> creer(OAuth2AuthResponse reponse) {
        byte[] octets = new byte[OCTETS];
        ALEA.nextBytes(octets);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);

        return serialiser(reponse)
                .flatMap(charge -> redis.opsForValue().set(cle(code), charge, validite))
                .flatMap(ecrit -> Boolean.TRUE.equals(ecrit)
                        ? Mono.just(code)
                        : Mono.error(new AuthException(ErrorCode.SERVICE_UNAVAILABLE)))
                // set() ne rend rien si Redis est injoignable : la chaine se
                // terminerait vide, et la redirection ne serait ni produite ni
                // refusee. Meme piege que dans EtatOAuth2Redis.
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.SERVICE_UNAVAILABLE)));
    }

    /**
     * Lit la reponse et consomme le code.
     *
     * @throws AuthException si le code est absent, inconnu, expire ou deja
     *         utilise. Les quatre cas rendent la <b>meme</b> erreur : distinguer
     *         « inconnu » de « deja utilise » dirait a un attaquant qui a trouve
     *         un code dans un journal s'il est arrive trop tard, donc si l'URL
     *         qu'il a vue valait la peine d'etre cherchee ailleurs.
     */
    public Mono<OAuth2AuthResponse> consommer(String code) {
        if (code == null || code.isBlank()) {
            return Mono.error(new AuthException(ErrorCode.INVALID_REQUEST));
        }
        return redis.opsForValue().getAndDelete(cle(code))
                .switchIfEmpty(Mono.defer(() -> {
                    // Le code n'est PAS journalise : il vaut les jetons qu'il
                    // designe le temps de sa validite.
                    log.warn("Echange OAuth2 avec un code inconnu, expire ou deja utilise");
                    return Mono.error(new AuthException(ErrorCode.INVALID_REQUEST));
                }))
                .flatMap(this::deserialiser);
    }

    private Mono<String> serialiser(OAuth2AuthResponse reponse) {
        try {
            return Mono.just(json.writeValueAsString(reponse));
        } catch (JsonProcessingException illisible) {
            // Le message de l'exception peut reprendre le contenu serialise,
            // donc les jetons : seule la classe est journalisee.
            log.error("Serialisation de la reponse OAuth2 impossible ({})",
                    illisible.getClass().getSimpleName());
            return Mono.error(new AuthException(ErrorCode.SERVICE_UNAVAILABLE));
        }
    }

    private Mono<OAuth2AuthResponse> deserialiser(String charge) {
        try {
            return Mono.just(json.readValue(charge, OAuth2AuthResponse.class));
        } catch (JsonProcessingException illisible) {
            log.error("Lecture de la reponse OAuth2 remise impossible ({})",
                    illisible.getClass().getSimpleName());
            return Mono.error(new AuthException(ErrorCode.SERVICE_UNAVAILABLE));
        }
    }

    private static String cle(String code) {
        return AppConstants.OAUTH2_REMISE_PREFIX + code;
    }
}
