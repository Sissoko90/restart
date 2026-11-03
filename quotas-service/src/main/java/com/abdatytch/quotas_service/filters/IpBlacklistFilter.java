package com.abdatytch.quotas_service.filters;

import com.abdatytch.commons.security.GatewayClientIp;

import lombok.extern.slf4j.Slf4j;
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
import java.util.Set;

/**

 * Filtre de liste noire IP avec cache Redis et blocage automatique
 */
@Slf4j
@Component
@Order(1) // Premier filtre à s'exécuter
public class IpBlacklistFilter implements WebFilter {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${IP_BLACKLIST_ENABLED:true}")
    private boolean enabled;

    @Value("${IP_BLACKLIST_CACHE_TTL:1800}")
    private long cacheTtl;

    @Value("${IP_BLACKLIST_MAX_ATTEMPTS:5}")
    private int maxAttempts;

    @Value("${IP_BLACKLIST_BLOCK_DURATION:3600}")
    private long blockDuration;

    // IPs permanentes en liste noire
    private static final Set<String> PERMANENT_BLACKLIST = Set.of(
        "0.0.0.0", "127.0.0.1"// Exemples - à configurer selon besoins
    );

    public IpBlacklistFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange);

        // Vérifier la liste noire permanente
        if (PERMANENT_BLACKLIST.contains(clientIp)) {
            log.warn(" IP permanente en liste noire: {}", clientIp);
            return blockRequest(exchange, "IP permanently blacklisted");
        }

        // Vérifier la liste noire temporaire (Redis)
        return isIpBlocked(clientIp)
            .flatMap(blocked -> {
                if (blocked) {
                    log.warn(" IP temporaire en liste noire: {} - Path: {}",
                            clientIp, exchange.getRequest().getPath());
                    return blockRequest(exchange, "IP temporarily blocked");
                }

                // Continuer avec la chaîne de filtres
                return chain.filter(exchange)
                    .doOnError(throwable -> {
                        // En cas d'erreur, incrémenter le compteur de tentatives
                        incrementFailedAttempts(clientIp).subscribe();
                    });
            });
    }

    /**

     * Vérifier si une IP est bloquée
     */
    private Mono<Boolean> isIpBlocked(String ip) {
        String blockKey = "ip:blocked:" + ip;
        return redisTemplate.hasKey(blockKey);
    }

    /**

     * Incrémenter les tentatives échouées et bloquer si nécessaire
     */
    private Mono<Void> incrementFailedAttempts(String ip) {
        String attemptsKey = "ip:attempts:" + ip;

        return redisTemplate.opsForValue()
            .increment(attemptsKey)
            .flatMap(attempts -> {
                if (attempts == 1) {
                    // Première tentative - définir TTL
                    return redisTemplate.expire(attemptsKey, Duration.ofSeconds(cacheTtl))
                        .then();
                } else if (attempts >= maxAttempts) {
                    // Trop de tentatives - bloquer l'IP
                    return blockIp(ip);
                }
                return Mono.empty();
            });
    }

    /**

     * Bloquer une IP temporairement
     */
    private Mono<Void> blockIp(String ip) {
        String blockKey = "ip:blocked:" + ip;
        log.warn(" Blocage automatique IP: {} pour {} secondes", ip, blockDuration);

        return redisTemplate.opsForValue()
            .set(blockKey, "blocked", Duration.ofSeconds(blockDuration))
            .then();
    }

    /**

     * Obtenir l'IP réelle du client (gestion des proxies)
     */
    /**
     * Adresse du client, posee par le Gateway.
     *
     * <p>Lisait {@code X-Forwarded-For} : l'appelant choisissait donc l'adresse
     * sur laquelle reposait la decision de blocage ou de plafonnement. Voir
     * {@code GatewayClientIp} — le meme schema a ete trouve huit fois.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return GatewayClientIp.of(exchange);
    }

    /**

     * Bloquer la requête avec une réponse d'erreur
     */
    private Mono<Void> blockRequest(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String jsonResponse = String.format(
            "{\"error\":\"Access denied\",\"reason\":\"%s\",\"timestamp\":\"%s\"}",
            reason,
            java.time.Instant.now()
        );

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(jsonResponse.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
