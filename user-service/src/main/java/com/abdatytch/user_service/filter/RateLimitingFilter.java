package com.abdatytch.user_service.filter;

import com.abdatytch.user_service.constants.RateLimitConstants;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Filtre WebFlux pour appliquer le Rate Limiting sur les endpoints
 * Protège contre les attaques DDoS et l'abus d'API
 * Toutes les constantes sont centralisées dans RateLimitConstants
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitingFilter implements WebFilter {

    private final RateLimiter publicEndpointsRateLimiter;
    private final RateLimiter userEndpointsRateLimiter;
    private final RateLimiter adminEndpointsRateLimiter;
    private final RateLimiter fileUploadRateLimiter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String clientIp = getClientIp(exchange);
        
        RateLimiter rateLimiter = selectRateLimiter(path);
        
        if (rateLimiter == null) {
            return chain.filter(exchange);
        }
        
        return Mono.fromCallable(() -> rateLimiter.acquirePermission())
            .flatMap(permitted -> {
                if (permitted) {
                    return chain.filter(exchange);
                } else {
                    log.warn(RateLimitConstants.LOG_WARN_LIMIT_EXCEEDED, clientIp, path);
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    exchange.getResponse().getHeaders().add(
                        RateLimitConstants.HEADER_RETRY_AFTER, 
                        RateLimitConstants.DEFAULT_RETRY_AFTER_SECONDS
                    );
                    return exchange.getResponse().setComplete();
                }
            })
            .onErrorResume(RequestNotPermitted.class, e -> {
                log.warn(RateLimitConstants.LOG_WARN_LIMIT_EXCEEDED_EXCEPTION, clientIp, path);
                exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                exchange.getResponse().getHeaders().add(
                    RateLimitConstants.HEADER_RETRY_AFTER,
                    RateLimitConstants.DEFAULT_RETRY_AFTER_SECONDS
                );
                return exchange.getResponse().setComplete();
            });
    }

    private RateLimiter selectRateLimiter(String path) {
        if (path.startsWith(RateLimitConstants.PATH_ACTUATOR)) {
            return publicEndpointsRateLimiter;
        } else if (path.startsWith(RateLimitConstants.PATH_ADMIN)) {
            return adminEndpointsRateLimiter;
        } else if (path.contains(RateLimitConstants.PATH_PICTURE) || path.contains(RateLimitConstants.PATH_UPLOAD)) {
            return fileUploadRateLimiter;
        } else if (path.startsWith(RateLimitConstants.PATH_USERS)) {
            return userEndpointsRateLimiter;
        }
        return null;
    }

    private String getClientIp(ServerWebExchange exchange) {
        String xForwardedFor = exchange.getRequest().getHeaders().getFirst(RateLimitConstants.HEADER_X_FORWARDED_FOR);
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(RateLimitConstants.SEPARATOR_COMMA)[RateLimitConstants.FIRST_IP_INDEX].trim();
        }
        
        String xRealIp = exchange.getRequest().getHeaders().getFirst(RateLimitConstants.HEADER_X_REAL_IP);
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        
        return exchange.getRequest().getRemoteAddress() != null 
            ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
            : RateLimitConstants.DEFAULT_CLIENT_IP;
    }
}
