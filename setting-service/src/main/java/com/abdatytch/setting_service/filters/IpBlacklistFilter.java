package com.abdatytch.setting_service.filters;

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

/**
 * Filtre de liste noire IP pour setting-service
 */
@Slf4j
@Component
@Order(1)
public class IpBlacklistFilter implements WebFilter {

    private final ReactiveStringRedisTemplate redisTemplate;

    @Value("${IP_BLACKLIST_ENABLED:true}")
    private boolean enabled;

    public IpBlacklistFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange);
        log.debug("IP check for setting-service: {}", clientIp);
        
        return chain.filter(exchange);
    }

    /**
     * Adresse du client, posee par le Gateway.
     *
     * <p>Lisait {@code X-Forwarded-For} : l'appelant choisissait donc l'adresse
     * sur laquelle reposait la decision. Voir {@code GatewayClientIp} — le meme
     * schema a ete trouve huit fois dans ce depot.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return GatewayClientIp.of(exchange);
    }
}
