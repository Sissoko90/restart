package com.abdatytch.commons.security;

import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Locator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.math.BigInteger;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**

 * Provider de clés publiques via JWKS (JSON Web Key Set) — implémentation unique
 * partagée par tous les services consommateurs de JWT.
 *
 * <p>Remplace les 6 copies divergentes qui existaient dans auth-service,
 * gateway-service, media-service, user-service, quotas-service et setting-service.
 * Ces copies avaient des API différentes ({@code getFirstPublicKey} vs
 * {@code getDefaultPublicKey}) et des comportements différents (certaines bloquaient
 * l'EventLoop Netty via {@code refreshKeys().block()}).
 *
 * <p>Récupère les clés publiques depuis l'autorité de signature (interface-service) :
 * {@code GET /dev/.well-known/jwks.json}. Voir {@code docs/JWT-CONTRACT.md}.
 *
 * <p>Garanties :
 * <ul>
 * <li>chargement initial <b>non bloquant</b> : le service démarre même si l'autorité
 * n'est pas encore disponible, avec réessais exponentiels ;</li>
 * <li>rafraîchissement périodique, ce qui permet la rotation de clés sans redéploiement ;</li>
 * <li>résolution <b>par {@code kid}</b>, indispensable pendant la période de grâce
 * d'une rotation où plusieurs clés sont valides simultanément ;</li>
 * <li>aucun appel bloquant : jamais de {@code block()} sur le chemin de validation.</li>
 * </ul>
 */
public class JwksKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyProvider.class);

    /**

     * URL du JWKS de l'autorité de signature.
     *
     * <p>Aucune valeur par défaut n'est écrite ici : elle vit dans
     * {@code platform-commons-defaults.properties}, chargé par
     * {@link PlatformSecurityAutoConfiguration}. Une adresse en dur dans le code
     * finit par être déployée telle quelle.
     */
    @Value("${jwt.jwks.url}")
    private String jwksUrl;

    @Value("${jwt.jwks.refresh-interval-seconds}")
    private int refreshIntervalSeconds;

    /**

     * Nombre de réessais au démarrage. Configurable pour deux raisons : permettre
     * d'ajuster la patience selon l'environnement, et rendre le comportement
     * d'échec testable sans attendre une trentaine de secondes de temporisation.
     */
    @Value("${jwt.jwks.retry-attempts}")
    private int retryAttempts;

    private final WebClient.Builder webClientBuilder;

    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();
    private volatile PublicKey defaultKey;
    private volatile long lastRefreshTime = 0;

    public JwksKeyProvider(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        log.info(" Initialisation du JWKS Key Provider depuis: {}", jwksUrl);
        log.info(" Chargement des clés en arrière-plan - le service démarre sans attendre l'autorité JWT");

        refreshKeysWithRetry()
            .doOnSuccess(count -> log.info(" JWKS initialisé: {} clés chargées depuis {}", count, jwksUrl))
            .doOnError(e -> log.warn(" JWKS indisponible pour l'instant, nouvelle tentative périodique: {}", e.getMessage()))
            // L'échec est attendu au démarrage : l'autorité JWT peut ne pas être
            // encore levée, et scheduledRefresh() reprendra. Il doit donc être
            // CONSOMMÉ ici.
            // // Sans ce onErrorComplete, `subscribe()` sans consommateur d'erreur
            // laissait l'échec sans destinataire : Reactor le signalait alors en
            // onErrorDropped, avec une pile de plus de cent lignes au niveau
            // ERROR, juste après le message d'avertissement qui l'expliquait
            // déjà. Un démarrage normal en environnement de développement
            // ressemblait ainsi à une panne grave.
            .onErrorComplete()
            .subscribe();
    }

    /**

     * Clé publique par défaut (première clé du JWKS).
     * À n'utiliser que pour un test de disponibilité : la validation d'un token
     * doit passer par {@link #keyLocator()} afin de résoudre la clé par son {@code kid}.
     */
    public PublicKey getDefaultPublicKey() {
        return defaultKey;
    }

    /**

     * Résout une clé publique par son Key ID, avec repli sur la clé par défaut.
     * Non bloquant : si le {@code kid} est inconnu, le rafraîchissement périodique
     * le chargera; on ne bloque jamais le thread appelant.
     */
    public PublicKey getPublicKey(String keyId) {
        if (keyId != null) {
            PublicKey key = keyCache.get(keyId);
            if (key != null) {
                return key;
            }
            log.warn(" Clé publique inconnue pour kid={} - repli sur la clé par défaut", keyId);
        }
        return defaultKey;
    }

    /**

     * Locator jjwt prêt à l'emploi pour {@code Jwts.parser().keyLocator(...)}.
     * C'est le point d'entrée à utiliser dans tous les validateurs de token.
     */
    public Locator<Key> keyLocator() {
        return header -> header instanceof JwsHeader jwsHeader
                ? getPublicKey(jwsHeader.getKeyId())
                : getDefaultPublicKey();
    }

    /** Indique si au moins une clé publique est disponible. */
    public boolean isInitialized() {
        return defaultKey != null;
    }

    /** Nombre de clés actuellement en cache. */
    public int getCacheSize() {
        return keyCache.size();
    }

    private Mono<Integer> refreshKeysWithRetry() {
        return refreshKeys()
            .retryWhen(Retry.backoff(retryAttempts, Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(10))
                .doBeforeRetry(signal -> log.warn(
                    " Autorité JWT injoignable sur {} - nouvelle tentative {}/{} : {}",
                    jwksUrl, signal.totalRetries() + 1, retryAttempts,
                    signal.failure().getMessage())));
    }

    public Mono<Integer> refreshKeys() {
        return webClientBuilder.build()
            .get()
            .uri(jwksUrl)
            .retrieve()
            .bodyToMono(Map.class)
            .map(this::parseJwks)
            .doOnSuccess(count -> {
                lastRefreshTime = System.currentTimeMillis();
                log.debug(" JWKS rafraîchi: {} clés chargées", count);
            })
            // DEBUG, et non ERROR : cette méthode est appelée une fois par
            // tentative, et chaque tentative suivie d'un réessai est déjà
            // annoncée par le doBeforeRetry ci-dessus. Journaliser en ERROR ici
            // produisait DEUX lignes par tentative, dont une au niveau le plus
            // grave, pour une situation que la classe traite comme normale :
            // « le service démarre sans attendre l'autorité JWT ».
            //
            // Un démarrage de développement où interface-service n'est pas
            // encore levé remplissait ainsi la console d'ERROR, au point de
            // faire croire que le service lui-même avait échoué — alors qu'il
            // écoutait et répondait. Un message de gravité ERROR doit désigner
            // quelque chose qui ne se réparera pas tout seul.
            .doOnError(e -> log.debug(" Tentative de récupération JWKS échouée: {}", e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private int parseJwks(Map<String, Object> jwks) {
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");
        if (keys == null || keys.isEmpty()) {
            log.warn(" Le JWKS ne contient aucune clé");
            return 0;
        }

        // Remplacer atomiquement le contenu du cache : les clés retirées du JWKS
        // (expirées après rotation) ne doivent plus servir à valider quoi que ce soit.
        Map<String, PublicKey> refreshed = new ConcurrentHashMap<>();
        PublicKey firstKey = null;

        for (Map<String, Object> jwk : keys) {
            try {
                if (!"RSA".equals(jwk.get("kty"))) {
                    continue;
                }

                String kid = (String) jwk.get("kid");
                String modulus = (String) jwk.get("n");
                String exponent = (String) jwk.get("e");

                if (modulus == null || exponent == null) {
                    continue;
                }

                PublicKey publicKey = parseRsaPublicKey(modulus, exponent);
                if (kid != null) {
                    refreshed.put(kid, publicKey);
                }
                if (firstKey == null) {
                    firstKey = publicKey;
                }
                log.debug(" Clé RSA chargée: kid={}", kid);
            } catch (Exception ex) {
                log.error(" Échec du parsing d'une JWK: {}", ex.getMessage());
            }
        }

        if (refreshed.isEmpty()) {
            log.warn(" Aucune clé RSA exploitable dans le JWKS - cache conservé en l'état");
            return 0;
        }

        keyCache.keySet().retainAll(refreshed.keySet());
        keyCache.putAll(refreshed);
        defaultKey = firstKey;

        return refreshed.size();
    }

    private PublicKey parseRsaPublicKey(String modulusB64, String exponentB64) throws Exception {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(modulusB64));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(exponentB64));

        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    /**

     * Rafraîchissement périodique.
     * Nécessite {@code @EnableScheduling}, activé par {@link PlatformSecurityAutoConfiguration}.
     */
    @Scheduled(fixedDelayString = "${jwt.jwks.refresh-interval-seconds:300}000")
    public void scheduledRefresh() {
        refreshKeys().subscribe(
            count -> log.debug(" Rafraîchissement JWKS planifié: {} clés", count),
            error -> log.warn(" Rafraîchissement JWKS planifié en échec: {}", error.getMessage())
        );
    }

    public boolean needsRefresh() {
        return (System.currentTimeMillis() - lastRefreshTime) > (refreshIntervalSeconds * 1000L);
    }
}
