package com.abdatytch.gateway_service.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**

 * WebFilter Reactive pour validation de version API (WebFlux compatible)
 * Remplace ApiVersionFilter (Servlet) pour architecture non-bloquante
 * Valide que les requêtes utilisent une version API supportée
 */
public class ApiVersionWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionWebFilter.class);

    // Le nombre de chiffres est BORNE a trois.
    //
    // Avec `\\d+`, le chemin /api/v99999999999999999999/x produisait un groupe
    // de vingt chiffres, et Integer.parseInt levait une NumberFormatException
    // non rattrapee : une erreur 500 sur une entree que l'appelant controle
    // entierement. Un 500 renseigne sur la pile d'appels et pollue la
    // supervision avec de fausses defaillances serveur.
    //
    // Trois chiffres laissent de la marge pour mille versions d'API, ce qui
    // suffira.
    private static final Pattern API_VERSION_PATTERN = Pattern.compile("/api/v(\\d{1,3})/.*");
    private static final int MIN_SUPPORTED_VERSION = 1;
    private static final int MAX_SUPPORTED_VERSION = 1;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Skip validation pour les endpoints non-API
        if (!path.startsWith("/api/v")) {
            return chain.filter(exchange);
        }

        Matcher matcher = API_VERSION_PATTERN.matcher(path);

        if (!matcher.matches()) {
            log.warn("Invalid API path format: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        int version = Integer.parseInt(matcher.group(1));

        if (version < MIN_SUPPORTED_VERSION || version > MAX_SUPPORTED_VERSION) {
            log.warn("Unsupported API version: v{} (supported: v{}-v{})",
                version, MIN_SUPPORTED_VERSION, MAX_SUPPORTED_VERSION);
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }

        log.debug("API version v{} validated for path: {}", version, path);
        return chain.filter(exchange);
    }
}
