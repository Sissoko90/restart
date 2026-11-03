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
 * Filtre CSP (Content Security Policy) pour setting-service
 */
@Slf4j
@Component
@Order(3)
public class CspFilter implements WebFilter {

    @Value("${CSP_ENABLED:true}")
    private boolean enabled;

    @Value("${CSP_POLICY:default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self';}")
    private String cspPolicy;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (enabled) {
            exchange.getResponse().getHeaders().add("Content-Security-Policy", cspPolicy);
            exchange.getResponse().getHeaders().add("X-Content-Type-Options", "nosniff");
            exchange.getResponse().getHeaders().add("X-Frame-Options", "DENY");
            exchange.getResponse().getHeaders().add("X-XSS-Protection", "1; mode=block");
            exchange.getResponse().getHeaders().add("Referrer-Policy", "strict-origin-when-cross-origin");
            
            log.debug("CSP appliqué pour setting-service: {}", cspPolicy);
        }
        
        return chain.filter(exchange);
    }
}
