package com.abdatytch.quotas_service.filters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**

 * Filtre de protection contre les attaques Zip Bomb pour quotas-service
 */
@Slf4j
@Component
@Order(2)
public class ZipBombFilter implements WebFilter {

    @Value("${ZIPBOMB_ENABLED:true}")
    private boolean enabled;

    @Value("${ZIPBOMB_MAX_COMPRESSION_RATIO:100}")
    private int maxCompressionRatio;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.contains("application/zip")) {
            return chain.filter(exchange);
        }

        log.debug("ZipBomb check for: {}", exchange.getRequest().getPath());

        // Implémentation simplifiée - vérification basique
        Long contentLength = exchange.getRequest().getHeaders().getContentLength();
        if (contentLength != null && contentLength > 50 * 1024 * 1024) { // 50MB max
            log.warn("Fichier ZIP trop volumineux: {} bytes", contentLength);
            return blockRequest(exchange);
        }

        return chain.filter(exchange);
    }

    private Mono<Void> blockRequest(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String jsonResponse = "{\"error\":\"File too large or suspicious\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(jsonResponse.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
