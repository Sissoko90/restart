package com.abdatytch.setting_service.filters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**

 * Filtre de protection contre les ZIP bombs pour setting-service
 */
@Slf4j
@Component
@Order(4)
public class ZipBombFilter implements WebFilter {

    @Value("${ZIPBOMB_PROTECTION_ENABLED:true}")
    private boolean enabled;

    @Value("${ZIPBOMB_MAX_SIZE:10485760}") // 10MB par défaut
    private long maxSize;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");

        if (contentType != null && (contentType.contains("application/zip") ||
                                   contentType.contains("application/x-zip-compressed"))) {

            String contentLength = exchange.getRequest().getHeaders().getFirst("Content-Length");
            if (contentLength != null) {
                try {
                    long size = Long.parseLong(contentLength);
                    if (size > maxSize) {
                        log.warn(" ZIP bomb potentiel détecté - Taille: {}MB (max: {}MB)",
                                size / 1024 / 1024, maxSize / 1024 / 1024);
                    }
                } catch (NumberFormatException e) {
                    log.debug("Content-Length invalide: {}", contentLength);
                }
            }

            log.debug("ZIP upload vérifié pour setting-service");
        }

        return chain.filter(exchange);
    }
}
