package com.abdatytch.user_service.client;

import com.abdatytch.user_service.constants.SecurityConstants;
import com.abdatytch.user_service.dto.response.AuthUserDTO;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * L'identité détenue par auth-service, lue sans bloquer.
 *
 * <h2>Ce que remplace cette classe</h2>
 *
 * Un client Feign, {@code AuthServiceClient}. Feign est <b>synchrone</b> : il
 * n'a pas d'API réactive, et l'appel était donc isolé sur
 * {@code boundedElastic} par {@code Mono.fromCallable(...).subscribeOn(...)}.
 * Cet isolement était correct — il tenait le blocage hors de l'event loop — mais
 * il ne faisait que le déplacer : un fil restait immobilisé pendant toute la
 * durée de l'aller-retour, et une page de vingt comptes en immobilisait huit à
 * la fois. {@code WebClient} ne bloque aucun fil : l'attente est portée par la
 * boucle d'événements de Netty, pas par un fil dédié.
 *
 * <h2>Les erreurs sont propagées, le repli est ailleurs</h2>
 *
 * Cette classe ne rattrape <b>aucune</b> erreur réseau ni aucun code HTTP
 * d'échec, et c'est délibéré : la décision de composer une réponse avec des
 * champs nuls plutôt que de la faire échouer appartient à {@code UserMapper},
 * où elle est documentée. Un repli posé ici cacherait la panne aux deux
 * appelants, dont l'un — la propagation de fermeture de compte — doit
 * impérativement échouer pour que {@code UserAdminServiceImpl} annule et
 * journalise.
 *
 * <p>Conséquence directe : <b>un échec n'est jamais mis en cache</b>. Seule une
 * réponse effectivement obtenue est écrite, puisque l'écriture est en aval du
 * succès.
 *
 * <h2>Ce que le cache coûte</h2>
 *
 * Il introduit une péremption que l'appel Feign n'avait pas : pendant au plus
 * une minute, un courriel, un nom ou un rôle modifié dans auth-service continue
 * d'être servi avec son ancienne valeur, et {@code lastLogin} peut retarder
 * d'autant. Une minute est le compromis : assez pour absorber la rafale d'un
 * administrateur qui feuillette une liste paginée, assez peu pour qu'une
 * correction d'identité ne paraisse pas perdue.
 *
 * <p>C'est pourquoi cette rétention est bien plus courte que le quart d'heure de
 * {@code CacheDesComptes} : celui-ci met en cache de l'état <b>local</b>, dont ce
 * service est la source de vérité et qu'il invalide lui-même à chaque écriture.
 * Ici la source est distante, et aucune invalidation ne nous parvient.
 *
 * <h2>Un seul détenteur de la clé</h2>
 *
 * Le préfixe {@value #PREFIXE_CACHE} ne figure qu'ici, et cette classe est la
 * seule à lire comme à écrire cette entrée. C'est la leçon de
 * {@code CacheDesComptes}, dont la convention de clé vivait à quatre endroits et
 * où deux écrivains sur quatre n'invalidaient rien.
 */
@Component
@Slf4j
public class ClientIdentiteAuth {

    /**
     * Préfixe des entrées d'identité.
     *
     * <p>Distinct de {@code user:} et {@code user:auth_}, que détient
     * {@code CacheDesComptes} : ces entrées-là portent une réponse composée,
     * celles-ci une identité brute. Les confondre ferait servir l'une pour
     * l'autre, et la désérialisation échouerait silencieusement.
     */
    static final String PREFIXE_CACHE = "auth:identite:";

    /** Péremption acceptée sur une donnée dont la source est un autre service. */
    private static final Duration RETENTION = Duration.ofSeconds(60);

    private final WebClient auth;
    private final ReactiveRedisTemplate<String, Object> redis;
    private final String secretInterne;

    /**
     * @param constructeurEquilibre obtenu par le qualificateur
     *                              {@code @LoadBalanced}, et non par type : le
     *                              constructeur ordinaire est {@code @Primary}
     *                              pour JWKS, qui appelle une URL absolue et que
     *                              l'équilibreur casserait
     * @param secretInterne         sans valeur par défaut, exactement comme le
     *                              champ qu'il remplace dans
     *                              {@code UserMapper} : le démarrage échoue si
     *                              la configuration ne le fournit pas. Un défaut
     *                              vide ferait démarrer un service dont
     *                              <b>chaque</b> lecture d'identité serait
     *                              refusée par auth-service, et dont les
     *                              réponses porteraient des champs nuls sans que
     *                              rien n'en dise la cause
     */
    public ClientIdentiteAuth(
            @LoadBalanced WebClient.Builder constructeurEquilibre,
            ReactiveRedisTemplate<String, Object> redis,
            @Value("${security.internal-secret}") String secretInterne,
            @Value("${clients.auth-service.delai-connexion-ms:3000}") int delaiConnexionMs,
            @Value("${clients.auth-service.delai-reponse-ms:5000}") int delaiReponseMs) {

        this.redis = redis;
        this.secretInterne = secretInterne;

        // Les deux delais reprennent ceux que Feign appliquait
        // (`spring.cloud.openfeign.client.config.default`). Sans delai de
        // reponse explicite, Netty attend indefiniment : une instance
        // d'auth-service qui accepte la connexion puis ne repond jamais
        // retiendrait la requete sans limite, et c'est la panne la plus
        // difficile a lire parce qu'elle ne produit aucune erreur.
        this.auth = constructeurEquilibre
                .baseUrl(SecurityConstants.BASE_URI_AUTH)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, delaiConnexionMs)
                                .responseTimeout(Duration.ofMillis(delaiReponseMs))))
                .build();

        log.info("Client d'identite vers {} : connexion {} ms, reponse {} ms",
                SecurityConstants.SERVICE_AUTH, delaiConnexionMs, delaiReponseMs);
    }

    /**
     * Identité d'un compte, depuis le cache ou depuis auth-service.
     *
     * @throws IllegalArgumentException si l'identifiant est absent — un appel
     *                                  sans identifiant partirait vers
     *                                  {@code /internal/auth/users/} et
     *                                  rendrait un 404 que rien ne
     *                                  distinguerait d'un compte inexistant
     */
    public Mono<AuthUserDTO> identite(String authUserId) {
        if (authUserId == null || authUserId.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "Identite demandee sans identifiant auth-service"));
        }
        String cle = PREFIXE_CACHE + authUserId;
        return lireDuCache(cle)
                .switchIfEmpty(Mono.defer(() -> interroger(authUserId)
                        .flatMap(identite -> ecrireAuCache(cle, identite))));
    }

    /**
     * Propage la fermeture d'un compte à auth-service.
     *
     * <p>L'entrée de cache est effacée <b>après</b> la suppression, et non
     * avant : l'effacer d'abord puis échouer laisserait le cache vide et
     * auth-service inchangé, donc une lecture suivante le rechargerait
     * aussitôt. L'ordre choisi ne laisse jamais une identité en cache pour un
     * compte effectivement supprimé.
     *
     * <p>{@code Mono.defer} et non {@code then(invaliderIdentite(...))} : un
     * argument de {@code then} est évalué à l'<b>assemblage</b> de la chaîne,
     * donc avant même que la suppression soit tentée. La différence ne se voit
     * pas aujourd'hui, {@code ReactiveRedisTemplate.delete} étant lui-même
     * paresseux, mais elle rend l'ordre énoncé ci-dessus vrai dans le code et
     * non seulement dans l'intention — et elle protège de la version suivante
     * de {@code invaliderIdentite} qui ferait un travail immédiat.
     */
    public Mono<Void> supprimer(String authUserId) {
        return auth.delete()
                .uri(SecurityConstants.PATH_INTERNAL_AUTH_USERS, authUserId)
                .header(SecurityConstants.HEADER_X_INTERNAL_SECRET, secretInterne)
                .retrieve()
                .toBodilessEntity()
                .then(Mono.defer(() -> invaliderIdentite(authUserId)));
    }

    /**
     * Oublie l'identité en cache d'un compte.
     *
     * <p>Exposée pour les cas où ce service apprend par un autre canal que
     * l'identité a changé — aujourd'hui la seule suppression. Un échec de Redis
     * n'est pas propagé : la conséquence réelle est une donnée périmée pendant
     * au plus une minute, pas une perte.
     */
    public Mono<Void> invaliderIdentite(String authUserId) {
        return redis.delete(PREFIXE_CACHE + authUserId)
                .onErrorResume(erreur -> {
                    log.warn("Identite en cache non invalidee pour {} : {}",
                            authUserId, erreur.getMessage());
                    return Mono.just(0L);
                })
                .then();
    }

    /**
     * Lecture du cache : une panne de Redis fait un manque, pas un échec.
     *
     * <p>Un cache indisponible doit ralentir le service — chaque lecture repart
     * vers auth-service — et non le faire échouer.
     */
    private Mono<AuthUserDTO> lireDuCache(String cle) {
        return redis.opsForValue().get(cle)
                .cast(AuthUserDTO.class)
                .onErrorResume(erreur -> {
                    log.warn("Identite en cache illisible pour {} : {}", cle, erreur.getMessage());
                    return Mono.empty();
                });
    }

    /** Écriture du cache : un échec ne doit pas perdre l'identité déjà obtenue. */
    private Mono<AuthUserDTO> ecrireAuCache(String cle, AuthUserDTO identite) {
        return redis.opsForValue().set(cle, identite, RETENTION)
                .onErrorResume(erreur -> {
                    log.warn("Identite non mise en cache pour {} : {}", cle, erreur.getMessage());
                    return Mono.just(false);
                })
                .thenReturn(identite);
    }

    /**
     * L'appel à auth-service.
     *
     * <h3>Le {@code Mono} vide, encore</h3>
     *
     * {@code bodyToMono} rend un {@code Mono} <b>vide</b> si la réponse arrive
     * sans corps, par exemple un 204 ou un 200 tronqué. Sans ce
     * {@code switchIfEmpty}, le vide remonterait jusqu'au {@code Mono.zip} de
     * {@code UserMapper}, qui ignore une source vide : la réponse utilisateur
     * entière disparaîtrait, et l'appelant recevrait un 404 pour un compte qui
     * existe. Le transformer en erreur le fait traiter par le repli déjà prévu.
     *
     * <p>Le {@code switchIfEmpty} est posé sur le {@code Mono} <b>typé</b>, pas
     * en fin de chaîne : c'est la seule position où il porte.
     */
    private Mono<AuthUserDTO> interroger(String authUserId) {
        return auth.get()
                .uri(SecurityConstants.PATH_INTERNAL_AUTH_USERS, authUserId)
                .header(SecurityConstants.HEADER_X_INTERNAL_SECRET, secretInterne)
                .retrieve()
                .bodyToMono(AuthUserDTO.class)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "auth-service a repondu sans corps pour " + authUserId)));
    }
}
