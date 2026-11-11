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

import java.util.regex.Pattern;

/**

 * Filtre de protection XSS pour quotas-service
 */
@Slf4j
@Component
@Order(3)
public class XssProtectionFilter implements WebFilter {

    @Value("${XSS_PROTECTION_ENABLED:true}")
    private boolean enabled;

    // Patterns XSS courants
    private static final Pattern[] XSS_PATTERNS = {
        Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("on\\w+\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<iframe[^>]*>", Pattern.CASE_INSENSITIVE)
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        // Vérifier les paramètres de requête
        exchange.getRequest().getQueryParams().forEach((key, values) -> {
            for (String value : values) {
                if (containsXss(value)) {
                    log.warn("XSS détecté dans paramètre '{}': {}", key, value);
                }
            }
        });

        // Vérifier les headers
        exchange.getRequest().getHeaders().forEach((key, values) -> {
            for (String value : values) {
                if (containsXss(value)) {
                    log.warn("XSS détecté dans header '{}': {}", key, value);
                }
            }
        });

        return chain.filter(exchange);
    }

    private boolean containsXss(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }

        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return true;
            }
        }
        return false;
    }
}
