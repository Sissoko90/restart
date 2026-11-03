package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.service.IDisjoncteurSortant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.security.Key;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cles publiques d'Apple, et verification du jeton d'identite.
 *
 * <h2>Pourquoi cette classe existe, alors que JwksKeyProvider existe deja</h2>
 *
 * {@code JwksKeyProvider} (platform-commons) est lie a <b>une seule</b> URL par
 * {@code @Value}, chargee dans un {@code @PostConstruct}, et cette URL est celle
 * de la plateforme — interface-service, l'autorite JWT. Le pointer vers Apple
 * casserait la verification des jetons de la plateforme ; en instancier un
 * second avec une autre URL n'est pas possible avec cette forme.
 *
 * <p>Ce qui est <b>reutilise</b> : jjwt pour parser le JWKS
 * ({@code Jwks.setParser()}), donc aucune lecture de cle a la main ; et
 * {@link IDisjoncteurSortant} pour l'appel sortant, avec son delai maximal.
 *
 * <h2>Ce que la verification doit absolument faire</h2>
 *
 * Trois controles, et chacun ferme une attaque reelle :
 *
 * <ol>
 *   <li><b>la signature</b>, contre la cle publique d'Apple designee par le
 *       {@code kid} de l'en-tete ;</li>
 *   <li><b>{@code iss = https://appleid.apple.com}</b> — sinon un jeton signe
 *       par n'importe qui d'autre serait recevable ;</li>
 *   <li><b>{@code aud} = NOTRE Services ID</b>. C'est le controle le plus
 *       important et le plus souvent oublie : sans lui, un jeton d'identite
 *       Apple obtenu legitimement par <b>une autre application</b> serait
 *       accepte ici. C'est exactement la faille publiee sur Sign in with Apple
 *       en 2020, qui permettait de prendre un compte avec le jeton d'une
 *       application tierce.</li>
 * </ol>
 *
 * <p>L'echeance est verifiee par jjwt lui-meme.
 *
 * <h2>Le rafraichissement est BORNE, et c'est deliberе</h2>
 *
 * Apple fait tourner ses cles : un {@code kid} inconnu est donc normal et doit
 * declencher un rechargement. Mais rechargeer a chaque {@code kid} inconnu
 * offre un <b>amplificateur</b> : il suffit d'envoyer des jetons portant des
 * {@code kid} aleatoires pour nous faire marteler le JWKS d'Apple — et Apple
 * finirait par nous limiter, ce qui empecherait les connexions legitimes.
 *
 * <p>Un rechargement au plus toutes les {@value #DELAI_MINIMAL_ENTRE_RECHARGEMENTS_SECONDES}
 * secondes suffit a suivre une rotation de cles, et retire l'amplificateur.
 */
@Slf4j
public class ClesPubliquesApple {

    /**
     * Point d'entree public d'Apple, repris de {@link AppConstants}.
     *
     * <p>Cette URL n'est volontairement <b>pas</b> configurable. Elle est
     * l'ancre de confiance de toute l'integration : elle designe les cles que
     * nous acceptons pour signer une identite. Rendue modifiable depuis le
     * serveur de configuration, une simple ecriture — sans revue, sans build et
     * avec rechargement a chaud — suffirait a la pointer vers un jeu de cles
     * choisi par un tiers, qui pourrait alors forger l'identite de n'importe
     * quel compte Apple. Ce n'est pas un secret, donc la mettre en
     * configuration n'apporterait aucune confidentialite en echange.
     */
    static final String URL_JWKS = AppConstants.APPLE_JWKS_URL;

    /** Emetteur impose par Apple. */
    static final String EMETTEUR = "https://appleid.apple.com";

    /** Voir la javadoc de la classe : retire l'amplificateur. */
    static final long DELAI_MINIMAL_ENTRE_RECHARGEMENTS_SECONDES = 60;

    /** Instance de disjoncteur, propre a cette dependance. */
    static final String DISJONCTEUR = "apple-jwks";

    private final WebClient webClient;
    private final IDisjoncteurSortant disjoncteur;
    private final Duration delaiMaximal;
    private final String servicesId;

    private final Map<String, PublicKey> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> dernierRechargement =
            new AtomicReference<>(Instant.EPOCH);

    public ClesPubliquesApple(WebClient.Builder constructeurWebClient,
                              IDisjoncteurSortant disjoncteur,
                              String servicesId,
                              long delaiEnSecondes) {
        this.webClient = constructeurWebClient.build();
        this.disjoncteur = disjoncteur;
        this.servicesId = servicesId;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);
        disjoncteur.declarer(DISJONCTEUR);
    }

    /**
     * Verifie le jeton d'identite et rend ses revendications.
     *
     * <p>Rend une <b>erreur</b> et jamais un {@code Mono} vide : un jeton dont
     * on ne peut rien dire est un jeton refuse. Une chaine qui se termine vide
     * laisserait la connexion ni aboutie ni refusee.
     */
    public Mono<Claims> verifier(String jetonDIdentite) {
        if (jetonDIdentite == null || jetonDIdentite.isBlank()) {
            return Mono.error(new AuthException(ErrorCode.TOKEN_INVALID));
        }
        // Mono.defer est indispensable et non decoratif : identifiantDeCle
        // LEVE sur un jeton mal forme. Sans defer, la levee se produit a
        // l'assemblage, c'est-a-dire chez l'appelant et hors de la chaine — un
        // onErrorResume place apres ne la verrait jamais, et un gestionnaire
        // WebFlux rendrait 500 au lieu du refus voulu. Le piege est le meme que
        // celui corrige dans les filtres de la passerelle.
        return Mono.defer(() -> clePour(identifiantDeCle(jetonDIdentite)))
                .map(cle -> lireEtVerifier(jetonDIdentite, cle).getPayload())
                .onErrorMap(erreur -> !(erreur instanceof AuthException), erreur -> {
                    // L'objet erreur n'est pas journalise : le message d'une
                    // exception de verification peut contenir le jeton, donc
                    // l'identite de la personne et sa duree de validite.
                    log.warn("Jeton d'identite Apple refuse ({})",
                            erreur.getClass().getSimpleName());
                    return new AuthException(ErrorCode.TOKEN_INVALID);
                });
    }

    /**
     * Lit le {@code kid} de l'en-tete <b>sans verifier</b> la signature.
     *
     * <p>C'est l'unique information qu'on puisse lire avant verification, et
     * elle ne sert qu'a choisir la cle : rien de ce qui suit ne lui fait
     * confiance. Un {@code kid} forge mene simplement a une cle inconnue, donc
     * a un refus.
     */
    private static String identifiantDeCle(String jeton) {
        int premierPoint = jeton.indexOf('.');
        if (premierPoint <= 0) {
            throw new AuthException(ErrorCode.TOKEN_INVALID);
        }
        String enTete = new String(java.util.Base64.getUrlDecoder()
                .decode(jeton.substring(0, premierPoint)), java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"kid\"\\s*:\\s*\"([^\"]+)\"").matcher(enTete);
        if (!m.find()) {
            throw new AuthException(ErrorCode.TOKEN_INVALID);
        }
        return m.group(1);
    }

    private Jws<Claims> lireEtVerifier(String jeton, PublicKey cle) {
        return Jwts.parser()
                .verifyWith(cle)
                .requireIssuer(EMETTEUR)
                // L'audience DOIT etre notre Services ID. Sans ce controle, un
                // jeton Apple valide emis pour une AUTRE application serait
                // recevable ici — la faille publiee en 2020.
                .requireAudience(servicesId)
                .build()
                .parseSignedClaims(jeton);
    }

    /**
     * Cle correspondant au {@code kid}, en rechargeant si elle est inconnue.
     *
     * <p>Le rechargement est borne dans le temps : voir la javadoc de la
     * classe.
     */
    private Mono<PublicKey> clePour(String kid) {
        PublicKey connue = cache.get(kid);
        if (connue != null) {
            return Mono.just(connue);
        }
        if (!rechargementAutorise()) {
            log.warn("Cle Apple {} inconnue et rechargement trop recent : refus", kid);
            return Mono.error(new AuthException(ErrorCode.TOKEN_INVALID));
        }
        return recharger()
                .flatMap(nombre -> {
                    PublicKey apres = cache.get(kid);
                    if (apres == null) {
                        log.warn("Cle Apple {} introuvable apres rechargement de {} cles",
                                kid, nombre);
                        return Mono.error(new AuthException(ErrorCode.TOKEN_INVALID));
                    }
                    return Mono.just(apres);
                });
    }

    private boolean rechargementAutorise() {
        Instant dernier = dernierRechargement.get();
        Instant maintenant = Instant.now();
        if (maintenant.isBefore(dernier.plusSeconds(DELAI_MINIMAL_ENTRE_RECHARGEMENTS_SECONDES))) {
            return false;
        }
        return dernierRechargement.compareAndSet(dernier, maintenant);
    }

    /** Recharge le jeu de cles d'Apple. Rend le nombre de cles retenues. */
    Mono<Integer> recharger() {
        return disjoncteur.proteger(DISJONCTEUR, webClient.get()
                .uri(URL_JWKS)
                .retrieve()
                .bodyToMono(String.class)
                // Un corps vide rendrait un Mono VIDE, et le rechargement
                // paraitrait reussi sans qu'aucune cle soit lue.
                .defaultIfEmpty("")
                .timeout(delaiMaximal)
                .onErrorMap(TimeoutException.class, e -> {
                    log.error("JWKS d'Apple injoignable en {}", delaiMaximal);
                    return new AuthException(ErrorCode.SERVICE_UNAVAILABLE);
                }))
                .map(this::enregistrer);
    }

    /**
     * Parse le JWKS avec jjwt et retient les cles utilisables.
     *
     * <p>Une cle que jjwt ne sait pas convertir est ignoree et non fatale :
     * Apple peut publier un type que cette version ne connait pas, et refuser
     * tout le jeu de cles pour une seule inconnue empecherait les connexions
     * alors que les autres cles sont valides.
     */
    private int enregistrer(String corps) {
        if (corps.isBlank()) {
            throw new AuthException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        JwkSet jeu;
        try {
            jeu = Jwks.setParser().build().parse(corps);
        } catch (RuntimeException e) {
            // jjwt leve ses propres exceptions : jeu de cles vide, JSON
            // invalide, type inconnu. Les laisser remonter telles quelles
            // ferait sortir une exception de bibliotheque jusqu'au gestionnaire
            // d'erreurs, qui ne sait pas la traduire — donc un 500 pour une
            // indisponibilite d'Apple.
            //
            // Le corps n'est PAS journalise : il vient du reseau et sa taille
            // n'est pas bornee ici.
            log.error("JWKS d'Apple inexploitable ({})", e.getClass().getSimpleName());
            throw new AuthException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        int retenues = 0;
        for (Jwk<?> jwk : jeu.getKeys()) {
            Key cle = jwk.toKey();
            if (cle instanceof PublicKey publique && jwk.getId() != null) {
                cache.put(jwk.getId(), publique);
                retenues++;
            }
        }
        if (retenues == 0) {
            // Zero cle retenue n'est pas un succes : sans cle, aucune
            // verification n'est possible et toute connexion Apple echouera.
            throw new AuthException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        log.info("Cles publiques Apple rechargees : {}", retenues);
        return retenues;
    }

    /** Nombre de cles en cache, pour les tests et le diagnostic. */
    int tailleDuCache() {
        return cache.size();
    }
}
