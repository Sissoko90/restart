package com.abdatytch.interface_service.filter;

import com.abdatytch.commons.security.GatewayClientIp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Filtre de Rate Limiting basé sur Redis
 * Limite le nombre de requêtes par IP par fenêtre de temps
 */
@Component
@Order(OrdreDesFiltres.LIMITATION_DEBIT)
public class RateLimitFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${rate-limit.window-seconds:60}")
    private int windowSeconds;

    private static final String RATE_LIMIT_PREFIX = "rate_limit:";

    public RateLimitFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        // Les trois sondes sont NOMMEES, et non /actuator en bloc.
        //
        // `startsWith("/actuator")` — sans meme la barre — exemptait tout le
        // sous-arbre de la limitation : `env`, `loggers`, `heapdump`, et aussi
        // `/actuatorXYZ`. Le meme defaut a deja ete corrige dans cinq filtres de
        // ce depot. Consul interroge `health` toutes les dix secondes et
        // Prometheus racle `prometheus` : eux seuls justifient l'exemption.
        String path = exchange.getRequest().getPath().value();
        if (estSondePublique(path)) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange);
        String key = RATE_LIMIT_PREFIX + clientIp;

        // Le comptage est isole de la suite de la chaine.
        //
        // LE DEFAUT LE PLUS GRAVE DE CE FILTRE. L'ancien `onErrorResume` etait
        // place APRES le flatMap qui appelle `chain.filter(exchange)`, donc il
        // rattrapait aussi les erreurs venues de l'AVAL — et y repondait en
        // rappelant `chain.filter(exchange)`.
        //
        // Toute erreur levee par un filtre suivant ou par un controleur faisait
        // donc EXECUTER LA REQUETE UNE SECONDE FOIS : effets de bord
        // dupliques — un POST traite deux fois — sur une reponse deja
        // partiellement ecrite.
        //
        // Le comptage et la decision vivent desormais dans un Mono distinct, et
        // le repli ne couvre que lui.
        return compterEtDecider(key, clientIp)
                .flatMap(decision -> {
                    if (!decision.autorisee()) {
                        log.warn("Rate limit dépassé pour IP: {} - {} requêtes",
                                clientIp, decision.compte());
                        return blockRequest(exchange, decision.compte());
                    }

                    exchange.getResponse().getHeaders()
                            .add("X-RateLimit-Limit", String.valueOf(requestsPerMinute));
                    exchange.getResponse().getHeaders()
                            .add("X-RateLimit-Remaining",
                                    String.valueOf(Math.max(0, requestsPerMinute - decision.compte())));
                    exchange.getResponse().getHeaders()
                            .add("X-RateLimit-Reset", String.valueOf(
                                    Instant.now().plusSeconds(windowSeconds).getEpochSecond()));

                    return chain.filter(exchange);
                });
    }

    /** Décision du comptage : le nombre vu, et si la requête passe. */
    private record Decision(long compte, boolean autorisee) {
    }

    /**
     * Incrémente le compteur et décide, sans jamais propager d'erreur.
     *
     * <h3>Repli assumé : ouvert</h3>
     *
     * Une panne de Redis laisse passer <b>sans plafond</b>. C'est plus faible
     * que le choix retenu au Gateway, qui bascule sur un compteur en mémoire —
     * « laisser passer sans limite exposerait l'essai de mots de passe au
     * moment où la supervision est déjà dégradée ». Le compteur local vit dans
     * {@code gateway-service} ; le reprendre ici demanderait de le promouvoir
     * dans {@code platform-commons}.
     *
     * <p>Ce qui est corrigé en revanche : le repli ne couvre plus que le
     * comptage. Il ne rattrape plus les erreurs de l'aval, qu'il relançait.
     */
    private Mono<Decision> compterEtDecider(String key, String clientIp) {
        return redisTemplate.opsForValue().increment(key)
                // Une reponse VIDE de Redis faisait disparaitre la requete : le
                // flatMap ne se declenchait pas, et onErrorResume ne s'applique
                // pas — il n'y a pas d'erreur, juste une absence de valeur.
                .defaultIfEmpty(1L)
                .flatMap(count -> assurerExpiration(key, count).thenReturn(count))
                .map(count -> new Decision(count, count <= requestsPerMinute))
                .onErrorResume(e -> {
                    log.error("Comptage de débit impossible, requête laissée passer : {}",
                            e.getMessage());
                    return Mono.just(new Decision(0L, true));
                });
    }

    /**
     * Pose l'expiration si elle manque, et la laisse si elle est valide.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * L'expiration n'était posée que lorsque le compteur valait 1. Si cet appel
     * échouait, la clé survivait <b>sans expiration</b> : les requêtes suivantes
     * n'y repassaient jamais, le compteur ne redescendait plus, et l'adresse
     * franchissait le plafond puis y restait <b>définitivement</b> — à cause
     * d'un incident Redis d'une seconde.
     *
     * <p>Une expiration valide n'est pas reposée : la reposer à chaque requête
     * ferait glisser la fenêtre et rendrait le plafond inatteignable pour un
     * appelant régulier.
     */
    private Mono<Void> assurerExpiration(String key, long count) {
        if (count == 1) {
            return redisTemplate.expire(key, Duration.ofSeconds(windowSeconds)).then();
        }
        return redisTemplate.getExpire(key)
                .defaultIfEmpty(Duration.ZERO)
                .flatMap(ttl -> ttl.isNegative() || ttl.isZero()
                        ? redisTemplate.expire(key, Duration.ofSeconds(windowSeconds)).then()
                        : Mono.<Void>empty());
    }

    /**
     * Les trois sondes réellement exemptées de limitation.
     *
     * <p>Consul interroge {@code /actuator/health} toutes les dix secondes et
     * Prometheus racle {@code /actuator/prometheus} : les plafonner ferait
     * croire le service mort. Les autres actuators ne sont pas des sondes.
     */
    private boolean estSondePublique(String chemin) {
        return chemin.equals("/actuator/health")
                || chemin.startsWith("/actuator/health/")
                || chemin.equals("/actuator/info")
                || chemin.equals("/actuator/prometheus");
    }

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

    private Mono<Void> blockRequest(ServerWebExchange exchange, Long count) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(windowSeconds));
        
        String jsonResponse = String.format(
                "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Maximum %d requests per %d seconds.\",\"retryAfter\":%d}",
                requestsPerMinute, windowSeconds, windowSeconds
        );
        
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(jsonResponse.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
