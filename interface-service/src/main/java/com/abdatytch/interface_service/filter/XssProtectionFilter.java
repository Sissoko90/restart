package com.abdatytch.interface_service.filter;

import com.abdatytch.commons.security.GatewayClientIp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Filtre de protection XSS
 * Sanitise le contenu HTML et bloque les scripts malveillants
 */
@Order(OrdreDesFiltres.PROTECTION_XSS)
@Component
public class XssProtectionFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(XssProtectionFilter.class);

    @Value("${security.xss-filter.enabled:true}")
    private boolean enabled;

    /**
     * Borne du corps examiné, en octets.
     *
     * <p>Au-delà, la requête est refusée en 413 plutôt que bufferisée. Sans
     * borne, ce filtre — dont la raison d'être est de refuser un contenu
     * hostile — offrait un épuisement de mémoire à quiconque envoyait un corps
     * assez gros, et cela <b>avant</b> tout contrôle.
     *
     * <p>256 Kio : les corps JSON de cette plateforme sont des formulaires
     * d'authentification et des descriptions de projets, pas des téléversements.
     * Les fichiers passent par media-service, qui a ses propres limites.
     */
    private static final int TAILLE_CORPS_MAX = 256 * 1024;

    @Value("${security.xss-filter.sanitize-html:true}")
    private boolean sanitizeHtml;

    @Value("${security.xss-filter.block-scripts:true}")
    private boolean blockScripts;

    @Value("${security.xss-filter.allowed-tags:p,br,strong,em,ul,ol,li,h1,h2,h3,h4,h5,h6}")
    private String allowedTags;

    @Value("${security.xss-filter.blocked-patterns:<script,javascript:,vbscript:,onload=,onerror=,onclick=}")
    private String blockedPatternsStr;

    private static final List<Pattern> XSS_PATTERNS = List.of(
        Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("vbscript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onload=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onerror=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onclick=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onmouseover=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onfocus=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onblur=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<iframe[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<object[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<embed[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("eval\\s*\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("expression\\s*\\(", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        // Exclure les endpoints JSON API de l'encodage HTML
        String path = exchange.getRequest().getPath().value();
        String contentType = exchange.getRequest().getHeaders().getFirst("Content-Type");
        
        if (isJsonApiEndpoint(path, contentType)) {
            log.debug("XSS filter bypassed for JSON API endpoint: {}", path);
            return chain.filter(exchange);
        }

        // Vérifier les headers d'abord
        if (containsXssInHeaders(exchange)) {
            log.warn("XSS détecté dans les headers de: {}", getClientIp(exchange));
            return handleXssBlocked(exchange);
        }

        // Vérifier les paramètres de requête
        if (containsXssInQueryParams(exchange)) {
            log.warn("XSS détecté dans les paramètres de: {}", getClientIp(exchange));
            return handleXssBlocked(exchange);
        }

        // Pour les requêtes POST/PUT avec body, vérifier le contenu
        if (hasBody(exchange)) {
            return filterRequestBody(exchange, chain);
        }

        return chain.filter(exchange);
    }

    /**
     * Vérifier si c'est un endpoint JSON API qui ne doit pas être encodé
     */
    private boolean isJsonApiEndpoint(String path, String contentType) {
        // Endpoints API JSON
        if (path.startsWith("/admin/") || path.startsWith("/auth/") || 
            path.startsWith("/api/") || path.startsWith("/dev/")) {
            return true;
        }
        
        // Content-Type JSON
        if (contentType != null && contentType.toLowerCase().contains("application/json")) {
            return true;
        }
        
        return false;
    }

    /**
     * Vérifier XSS dans les headers
     */
    private boolean containsXssInHeaders(ServerWebExchange exchange) {
        // Spring Framework 7 : HttpHeaders n'implémente plus Map, on itère via headerSet()
        return exchange.getRequest().getHeaders().headerSet().stream()
            .anyMatch(entry -> entry.getValue().stream()
                .anyMatch(this::containsXss));
    }

    /**
     * Vérifier XSS dans les paramètres de requête
     */
    private boolean containsXssInQueryParams(ServerWebExchange exchange) {
        return exchange.getRequest().getQueryParams().entrySet().stream()
            .anyMatch(entry -> entry.getValue().stream()
                .anyMatch(this::containsXss));
    }

    /**
     * Filtrer le body de la requête
     */
    private Mono<Void> filterRequestBody(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequestDecorator decoratedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                // `join` BORNE, et non `collectList()`.
                //
                // LE DEFAUT CORRIGE, et il est auto-contradictoire : ce filtre
                // existe pour refuser un contenu hostile, et il bufferisait la
                // totalite du corps EN MEMOIRE avant d'y regarder. Il suffisait
                // donc d'un corps enorme pour epuiser la memoire du service
                // avant que le moindre controle ne tourne.
                //
                // `spring.codec.max-in-memory-size` ne protege pas ici : il ne
                // s'applique pas a un bufferisage explicite. Le meme defaut a
                // ete corrige au Gateway sur ZipBombProtectionFilter et
                // BodyXssScannerWebFilter.
                //
                // Au-dela de la borne, `join` leve une DataBufferLimitException,
                // rattrapee en 413 par le filtre.
                return DataBufferUtils.join(super.getBody(), TAILLE_CORPS_MAX)
                    .flatMapMany(dataBuffer -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);
                        String body = new String(bytes, StandardCharsets.UTF_8);

                        if (containsXss(body)) {
                            log.warn("XSS détecté dans le body de: {}", getClientIp(exchange));
                            return Flux.error(new XssException("Contenu XSS détecté"));
                        }

                        String sanitizedBody = sanitizeHtml ? sanitizeHtml(body) : body;
                        DataBuffer buffer = exchange.getResponse().bufferFactory()
                            .wrap(sanitizedBody.getBytes(StandardCharsets.UTF_8));
                        
                        return Flux.just(buffer);
                    });
            }
        };

        return chain.filter(exchange.mutate().request(decoratedRequest).build())
            .onErrorResume(XssException.class, ex -> handleXssBlocked(exchange))
            // Un corps depassant la borne est refuse en 413, et non propage en
            // 500 : la taille d'une requete est le fait de l'appelant.
            .onErrorResume(org.springframework.core.io.buffer.DataBufferLimitException.class,
                    ex -> {
                        log.warn("Corps de requête trop volumineux refusé sur {}",
                                exchange.getRequest().getPath().value());
                        exchange.getResponse().setStatusCode(
                                org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
                        return exchange.getResponse().setComplete();
                    });
    }

    /**
     * Vérifier si le contenu contient du XSS
     */
    private boolean containsXss(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }

        // Vérifier les patterns XSS prédéfinis
        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }

        // Vérifier les patterns configurés
        String[] blockedPatterns = blockedPatternsStr.split(",");
        for (String blockedPattern : blockedPatterns) {
            if (content.toLowerCase().contains(blockedPattern.trim().toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Sanitiser le HTML
     */
    private String sanitizeHtml(String content) {
        if (content == null) {
            return null;
        }

        String sanitized = content;

        // Supprimer tous les scripts
        if (blockScripts) {
            for (Pattern pattern : XSS_PATTERNS) {
                sanitized = pattern.matcher(sanitized).replaceAll("");
            }
        }

        // Encoder les caractères dangereux
        sanitized = sanitized
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;");

        // Réautoriser les tags sûrs si configuré
        if (!allowedTags.isEmpty()) {
            String[] tags = allowedTags.split(",");
            for (String tag : tags) {
                tag = tag.trim();
                sanitized = sanitized
                    .replace("&lt;" + tag + "&gt;", "<" + tag + ">")
                    .replace("&lt;/" + tag + "&gt;", "</" + tag + ">");
            }
        }

        return sanitized;
    }

    /**
     * Vérifier si la requête a un body
     */
    private boolean hasBody(ServerWebExchange exchange) {
        String method = exchange.getRequest().getMethod().name();
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    /**
     * Gérer une requête XSS bloquée
     */
    private Mono<Void> handleXssBlocked(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        
        String errorResponse = """
            {
                "error": "XSS_DETECTED",
                "message": "Contenu potentiellement malveillant détecté",
                "timestamp": "%s"
            }
            """
.formatted(java.time.LocalDateTime.now());
            
        return exchange.getResponse().writeWith(
            Mono.just(exchange.getResponse().bufferFactory().wrap(errorResponse.getBytes()))
        );
    }

    /**
     * Extraire l'IP client
     */
    /**
     * Adresse du client, posee par le Gateway. Voir {@code GatewayClientIp} :
     * lire {@code X-Forwarded-For} laissait l'appelant choisir l'adresse sur
     * laquelle reposait la decision.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return GatewayClientIp.of(exchange);
    }

    /**
     * Exception personnalisée pour XSS
     */
    public static class XssException extends RuntimeException {
        public XssException(String message) {
            super(message);
        }
    }
}
