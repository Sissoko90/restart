package com.abdatytch.interface_service.filter;

import com.abdatytch.commons.security.ClesRedisSecurite;
import com.abdatytch.commons.security.GatewayClientIp;
import com.abdatytch.commons.security.PrivateNetworks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Filtre de blacklist IP avec Redis cache
 * Bloque automatiquement les IPs suspectes
 */
@Order(OrdreDesFiltres.LISTE_NOIRE_IP)
@Component
public class IpBlacklistFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(IpBlacklistFilter.class);

    private final ReactiveStringRedisTemplate redisTemplate;

    public IpBlacklistFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Value("${security.ip-blacklist.enabled:true}")
    private boolean enabled;

    @Value("${security.ip-blacklist.cache-ttl:1800}")
    private long cacheTtl;

    @Value("${security.ip-blacklist.max-attempts:5}")
    private int maxAttempts;

    @Value("${security.ip-blacklist.block-duration:3600}")
    private long blockDuration;

    /**
     * Préfixe partagé avec le Gateway et la console d'administration, pris dans
     * {@link ClesRedisSecurite} et <b>non configurable</b>.
     *
     * <h3>Le défaut corrigé, et il était actif</h3>
     *
     * Ce préfixe était lu par
     * {@code @Value("${security.ip-blacklist.redis-key-prefix:security:ip:blacklist:}")}.
     * Le défaut écrit dans le code était le bon. La valeur <b>déployée</b> ne
     * l'était pas : {@code config-repo/dev/application-dev.yml} posait
     * {@code redis-key-prefix: "blacklist:ip:"} — l'ancien préfixe, celui que le
     * Gateway ne lit pas.
     *
     * <p>Un défaut de {@code @Value} ne s'applique qu'en l'<b>absence</b> de la
     * propriété. Elle était présente, donc elle gagnait. Ce filtre écrivait ses
     * blocages automatiques dans {@code blacklist:ip:<ip>}, que personne ne lit,
     * tandis que la console d'administration écrivait dans
     * {@code security:ip:blacklist:<ip>}, que le Gateway lit. Deux préfixes dans
     * le même module, et la détection automatique sans aucun effet.
     *
     * <p>C'est <b>exactement</b> le défaut que la javadoc précédente de ce champ
     * déclarait corrigé. Il l'était dans le code, et réintroduit par la
     * configuration.
     *
     * <h3>Pourquoi le test ne l'a pas vu</h3>
     *
     * {@code IpBlacklistFilterTest} posait ce préfixe par réflexion, à sa valeur
     * attendue. Il vérifiait donc le défaut écrit dans le code, jamais la valeur
     * servie par le serveur de configuration. Un test qui injecte lui-même la
     * valeur qu'il va contrôler ne contrôle que lui-même.
     *
     * <h3>Pourquoi ce n'est plus une propriété</h3>
     *
     * Parce que c'est un <b>contrat entre services</b>, pas un réglage. Le
     * rendre configurable donne à un fichier de configuration le pouvoir de
     * rompre l'accord entre qui décide et qui applique — silencieusement, car
     * lire une clé absente rend simplement « pas bloqué ». Une constante
     * partagée rend la divergence impossible à écrire.
     */
    private static final String PREFIXE_LISTE_NOIRE = ClesRedisSecurite.PREFIXE_IP_BLOQUEE;

    /**
     * Compteur de violations, dans son propre espace de noms.
     *
     * <p>La clé était {@code <prefixe de liste noire> + "attempts:" + ip}, donc
     * {@code security:ip:blacklist:attempts:<ip>} : un compteur rangé <b>dans</b>
     * l'espace des adresses bloquées. {@code ClesRedisSecurite} prévoit
     * {@code security:ip:violations:} exactement pour cela, et le Gateway y tient
     * déjà le même comptage.
     */
    private static final String PREFIXE_VIOLATIONS = ClesRedisSecurite.PREFIXE_VIOLATIONS_IP;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange);
        
        // Exclure localhost et IPs privées du blacklisting
        if (isWhitelistedIp(clientIp)) {
            return chain.filter(exchange);
        }
        
        String blacklistKey = PREFIXE_LISTE_NOIRE + clientIp;
        String attemptsKey = PREFIXE_VIOLATIONS + clientIp;

        return isIpBlacklisted(blacklistKey)
            .flatMap(isBlacklisted -> {
                if (isBlacklisted) {
                    log.warn("IP blacklistée bloquée: {}", clientIp);
                    return handleBlacklisted(exchange);
                }
                
                // Surveiller le STATUT DE REPONSE, et non les erreurs Reactor.
                //
                // LE DEFAUT CORRIGE, et il rendait ce filtre entierement inerte.
                //
                // Le comptage se faisait dans un `doOnError`. Or un echec
                // d'authentification ne signale AUCUNE erreur Reactor dans cette
                // plateforme : les controleurs rendent
                // `Mono.just(ResponseEntity.status(UNAUTHORIZED))` et les filtres
                // posent `setStatusCode(...)` puis ecrivent — autant de
                // completions normales.
                //
                // `doOnError` ne se declenchait donc que sur une exception non
                // rattrapee, c'est-a-dire sur une erreur 500 : le compteur
                // comptait NOS pannes, jamais les tentatives d'un attaquant. Le
                // seuil n'etait jamais atteint, la liste noire ne se remplissait
                // pas, et `isIpBlacklisted` rendait toujours faux.
                //
                // Une protection ecrite, presente, et jamais atteinte — le meme
                // motif que `recordViolation` au Gateway, qu'aucun code
                // n'appelait.
                return chain.filter(exchange)
                    .then(Mono.defer(() -> {
                        if (!estEchecDAuthentification(exchange)) {
                            return Mono.empty();
                        }
                        // Chaine, et non `.subscribe()` : un incrementage lance
                        // de cote peut ne pas aboutir, et son echec serait
                        // invisible. Un compteur qui perd des increments ne
                        // franchit jamais son seuil.
                        return incrementFailedAttempts(attemptsKey, blacklistKey, clientIp);
                    }));
            });
    }

    /**
     * La réponse est-elle un refus d'authentification ou d'autorisation ?
     *
     * <p>401 et 403 seulement. Un 400 signale une requête malformée, un 404 un
     * chemin inexistant, un 5xx notre propre panne : aucun des trois n'est une
     * tentative d'accès à compter contre l'appelant.
     */
    private boolean estEchecDAuthentification(ServerWebExchange exchange) {
        HttpStatusCode statut = exchange.getResponse().getStatusCode();
        return statut != null
                && (statut.value() == HttpStatus.UNAUTHORIZED.value()
                    || statut.value() == HttpStatus.FORBIDDEN.value());
    }

    /**
     * Vérifier si une IP est blacklistée.
     *
     * <h3>Deux replis, pour deux raisons distinctes</h3>
     *
     * {@code hasKey} pouvait rendre une <b>erreur</b> — Redis injoignable — ou un
     * {@code Mono} <b>vide</b>. Ni l'un ni l'autre n'était traité : l'erreur
     * remontait au client en 500, et un {@code Mono} vide traversait le
     * {@code flatMap} sans le déclencher, si bien que la requête n'était <b>ni
     * transmise ni répondue</b> et que le client attendait jusqu'à son délai
     * d'expiration.
     *
     * <p>Le repli est {@code false}, donc <b>ouvert en cas de panne</b>. C'est le
     * choix retenu au Gateway pour la même décision, et pour la même raison :
     * fermer sur panne de Redis refuserait toute la plateforme à tout le monde
     * pour se protéger de quelques adresses, et offrirait un déni de service
     * complet à qui ferait tomber Redis. Le compromis inverse vaut pour la
     * révocation de jetons, où c'est l'identité du porteur qui est en cause.
     */
    private Mono<Boolean> isIpBlacklisted(String blacklistKey) {
        return redisTemplate.hasKey(blacklistKey)
                .defaultIfEmpty(Boolean.FALSE)
                .onErrorResume(e -> {
                    log.warn("Liste noire injoignable, requête laissée passer : {}",
                            e.getMessage());
                    return Mono.just(Boolean.FALSE);
                });
    }

    /**
     * Incrémenter les tentatives échouées et blacklister si nécessaire
     */
    private Mono<Void> incrementFailedAttempts(String attemptsKey, String blacklistKey, String clientIp) {
        return redisTemplate.opsForValue()
            .increment(attemptsKey)
            .flatMap(attempts -> {
                if (attempts == 1) {
                    // Premier échec, définir TTL
                    return redisTemplate.expire(attemptsKey, Duration.ofSeconds(cacheTtl))
                        .then();
                } else if (attempts < maxAttempts) {
                    // Expiration MANQUANTE : la reposer.
                    //
                    // Elle n'etait posee qu'au premier echec. Si cet appel
                    // echouait, la cle survivait SANS expiration : le compteur
                    // ne redescendait plus et l'adresse finissait blacklistee
                    // definitivement, a cause d'un incident Redis d'une
                    // seconde.
                    //
                    // Une expiration VALIDE n'est pas reposee : la reposer a
                    // chaque echec ferait glisser la fenetre, et un attaquant
                    // regulier n'atteindrait jamais le seuil.
                    return redisTemplate.getExpire(attemptsKey)
                        .defaultIfEmpty(Duration.ZERO)
                        .flatMap(ttl -> ttl.isNegative() || ttl.isZero()
                                ? redisTemplate.expire(attemptsKey,
                                        Duration.ofSeconds(cacheTtl)).then()
                                : Mono.<Void>empty());
                } else {
                    // Blacklister l'IP
                    log.warn("IP blacklistée après {} tentatives: {}", attempts, clientIp);
                    return redisTemplate.opsForValue()
                        .set(blacklistKey, LocalDateTime.now().toString(), Duration.ofSeconds(blockDuration))
                        .then(redisTemplate.delete(attemptsKey))
                        .then();
                }
            })
            // Un comptage qui echoue ne doit pas faire echouer la requete : elle
            // a deja ete traitee, cette etape n'est qu'un enregistrement. Mais
            // l'echec doit se voir, sans quoi une liste noire qui ne se remplit
            // plus passerait inaperçue — c'est exactement ce qui vient d'etre
            // corrige.
            .onErrorResume(e -> {
                log.error("Comptage des tentatives échouées impossible pour {} : {}",
                        clientIp, e.getMessage());
                return Mono.empty();
            });
    }

    /**
     * Gérer une IP blacklistée
     */
    private Mono<Void> handleBlacklisted(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        
        String errorResponse = """
            {
                "error": "IP_BLACKLISTED",
                "message": "Votre adresse IP a été temporairement bloquée",
                "timestamp": "%s"
            }
            """
.formatted(LocalDateTime.now());
            
        return exchange.getResponse().writeWith(
            Mono.just(exchange.getResponse().bufferFactory().wrap(errorResponse.getBytes()))
        );
    }

    /**
     * L'adresse échappe-t-elle au comptage ?
     *
     * <p>Un appelant interne — un autre service, le Gateway lui-même — ne doit
     * pas pouvoir se faire inscrire en liste noire : la plateforme s'y
     * couperait elle-même.
     *
     * <h3>Troisième copie du même contrôle, maintenant partagée</h3>
     *
     * Cette méthode était la <b>troisième</b> implémentation de « qu'est-ce
     * qu'une adresse privée » dans le dépôt, après celles d'auth-service et de
     * {@code GatewayAccessFilter}. Celle-ci était correcte — elle lisait bien le
     * second octet de 172 — mais les deux autres se trompaient, en sens
     * opposés. Trois copies, deux erreurs.
     *
     * <p>{@code PrivateNetworks} est désormais la seule définition, et
     * {@code "localhost"} y est ajouté ici : ce n'est pas une adresse, mais
     * {@code GatewayClientIp} peut le rendre tel quel selon la pile réseau.
     */
    private boolean isWhitelistedIp(String ip) {
        return "localhost".equals(ip) || PrivateNetworks.contient(ip);
    }

    /**
     * Extraire l'IP client en tenant compte des proxies
     */
    /**
     * Adresse du client, posee par le Gateway.
     *
     * <p>Cette methode lisait {@code X-Forwarded-For} puis {@code X-Real-IP} :
     * l'appelant choisissait donc l'adresse sur laquelle reposait la decision.
     * Le plafond de debit se contournait en faisant varier l'en-tete, et la
     * liste noire d'IP ne bloquait rien.
     *
     * <p>Voir {@code GatewayClientIp} pour le detail — le meme schema a ete
     * trouve huit fois dans ce depot.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return GatewayClientIp.of(exchange);
    }
}
