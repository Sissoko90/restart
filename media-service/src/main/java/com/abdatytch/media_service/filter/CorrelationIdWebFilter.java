package com.abdatytch.media_service.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

/**
 * WebFilter pour ajouter un Correlation ID à chaque requête
 * Permet de tracer les requêtes à travers les services
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_ID_KEY = "correlationId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = exchange.getRequest()
            .getHeaders()
            .getFirst(CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String finalCorrelationId = correlationId;

        exchange.getResponse()
            .getHeaders()
            .add(CORRELATION_ID_HEADER, finalCorrelationId);

        exchange.getAttributes().put(CORRELATION_ID_KEY, finalCorrelationId);

        log.debug("Request correlation ID: {}", finalCorrelationId);

        return chain.filter(exchange)
            .contextWrite(Context.of(CORRELATION_ID_KEY, finalCorrelationId));
    }
}
