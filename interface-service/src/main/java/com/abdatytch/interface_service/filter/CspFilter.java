package com.abdatytch.interface_service.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Filtre Content Security Policy (CSP)
 * Ajoute les headers de sécurité CSP pour prévenir les attaques XSS
 */
@Order(OrdreDesFiltres.CSP)
@Component
public class CspFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(CspFilter.class);

    @Value("${security.csp.enabled:true}")
    private boolean enabled;

    @Value("${security.csp.report-only:false}")
    private boolean reportOnly;

    @Value("${security.csp.report-uri:/security/csp-report}")
    private String reportUri;

    @Value("${security.csp.directives.default-src:'self'}")
    private String defaultSrc;

    @Value("${security.csp.directives.script-src:'self' 'unsafe-inline'}")
    private String scriptSrc;

    @Value("${security.csp.directives.style-src:'self' 'unsafe-inline'}")
    private String styleSrc;

    @Value("${security.csp.directives.img-src:'self' data: https:}")
    private String imgSrc;

    @Value("${security.csp.directives.font-src:'self'}")
    private String fontSrc;

    @Value("${security.csp.directives.connect-src:'self'}")
    private String connectSrc;

    @Value("${security.csp.directives.frame-ancestors:'none'}")
    private String frameAncestors;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        // Construire la politique CSP
        String cspPolicy = buildCspPolicy();
        
        // Ajouter le header CSP approprié
        String headerName = reportOnly ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy";
        exchange.getResponse().getHeaders().add(headerName, cspPolicy);

        // Ajouter d'autres headers de sécurité
        addSecurityHeaders(exchange);

        log.debug("CSP appliqué: {}", cspPolicy);

        return chain.filter(exchange);
    }

    /**
     * Construire la politique CSP
     */
    private String buildCspPolicy() {
        StringBuilder policy = new StringBuilder();
        
        policy.append("default-src ").append(defaultSrc).append("; ");
        policy.append("script-src ").append(scriptSrc).append("; ");
        policy.append("style-src ").append(styleSrc).append("; ");
        policy.append("img-src ").append(imgSrc).append("; ");
        policy.append("font-src ").append(fontSrc).append("; ");
        policy.append("connect-src ").append(connectSrc).append("; ");
        policy.append("frame-ancestors ").append(frameAncestors).append("; ");
        
        // Ajouter l'URI de rapport si configuré
        if (!reportUri.isEmpty()) {
            policy.append("report-uri ").append(reportUri).append("; ");
        }
        
        // Directives de sécurité supplémentaires
        policy.append("object-src 'none'; ");
        policy.append("base-uri 'self'; ");
        policy.append("form-action 'self'; ");
        policy.append("frame-src 'none'; ");
        policy.append("media-src 'self'; ");
        policy.append("worker-src 'none'; ");
        policy.append("manifest-src 'self'; ");
        
        return policy.toString().trim();
    }

    /**
     * Ajouter d'autres headers de sécurité
     */
    private void addSecurityHeaders(ServerWebExchange exchange) {
        var headers = exchange.getResponse().getHeaders();
        
        // X-Content-Type-Options
        headers.add("X-Content-Type-Options", "nosniff");
        
        // X-Frame-Options
        headers.add("X-Frame-Options", "DENY");
        
        // X-XSS-Protection
        headers.add("X-XSS-Protection", "1; mode=block");
        
        // Referrer-Policy
        headers.add("Referrer-Policy", "strict-origin-when-cross-origin");
        
        // Permissions-Policy (anciennement Feature-Policy)
        headers.add("Permissions-Policy", 
            "geolocation=(), microphone=(), camera=(), payment=(), usb=(), magnetometer=(), gyroscope=()");
        
        // Strict-Transport-Security (HSTS) - seulement si HTTPS
        // Le litteral d'abord : `getScheme().equals("https")` levait une
        // NullPointerException des que le schema etait absent de l'URI — ce qui
        // arrive sur une URI relative, et donc derriere certaines
        // configurations de proxy.
        //
        // Le symptome aurait ete un 500 sur TOUTES les requetes, puisque ce
        // filtre s'applique a chacune. L'ordre de la comparaison est ici une
        // propriete de disponibilite, pas un detail de style.
        if ("https".equals(exchange.getRequest().getURI().getScheme())) {
            headers.add("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
        }
        
        // Cross-Origin-Embedder-Policy
        headers.add("Cross-Origin-Embedder-Policy", "require-corp");
        
        // Cross-Origin-Opener-Policy
        headers.add("Cross-Origin-Opener-Policy", "same-origin");
        
        // Cross-Origin-Resource-Policy
        headers.add("Cross-Origin-Resource-Policy", "same-origin");
    }
}
