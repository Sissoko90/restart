package com.abdatytch.gateway_service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Protection contre les attaques Path Traversal
 * Bloque les tentatives d'accès aux fichiers système via ../../../
 * 
 * Patterns bloqués:
 * - ../ ou ..\
 * - Encodage URL de ../
 * - Double encodage
 * - Null bytes
 */
public class PathTraversalWebFilter implements WebFilter {
    
    private static final Logger log = LoggerFactory.getLogger(PathTraversalWebFilter.class);

    private final ClientIpResolver clientIpResolver;

    /**
     * Signale les violations detectees, pour alimenter la liste noire.
     *
     * <p>Peut rester {@code null} : le filtre doit refuser la requete meme sans
     * enregistreur. Le refus immediat et la mise en liste noire sont deux
     * decisions distinctes, et la seconde ne doit pas conditionner la premiere.
     */
    private SecurityViolationRecorder violationRecorder;

    /** Branche l'enregistreur de violations. */
    public PathTraversalWebFilter avecEnregistreur(SecurityViolationRecorder recorder) {
        this.violationRecorder = recorder;
        return this;
    }

    private reactor.core.publisher.Mono<Void> signaler(String clientIp, String motif) {
        return violationRecorder == null
                ? reactor.core.publisher.Mono.empty()
                : violationRecorder.enregistrer(clientIp, motif);
    }

    public PathTraversalWebFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }
    
    // Patterns dangereux
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String query = exchange.getRequest().getURI().getQuery();
        
        // Vérifier le path
        if (isPathTraversal(path)) {
            log.warn("Path traversal attempt detected in path: {} from IP: {}", 
                path, clientIpResolver.resolve(exchange));
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return signaler(clientIpResolver.resolve(exchange), "path-traversal")
                    .then(exchange.getResponse().setComplete());
        }
        
        // Vérifier les query parameters
        if (query != null && isPathTraversal(query)) {
            log.warn("Path traversal attempt detected in query: {} from IP: {}", 
                query, clientIpResolver.resolve(exchange));
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return signaler(clientIpResolver.resolve(exchange), "path-traversal")
                    .then(exchange.getResponse().setComplete());
        }
        
        // Les sondes de santé de Consul passent toutes les 10 secondes. Les
        // journaliser produisait six lignes par minute n'apprenant rien, et
        // noyait le signal utile — dont les tentatives réelles, ci-dessus, qui
        // sont en WARN.
        if (log.isDebugEnabled() && !estSondeDeSante(path)) {
            log.debug("Path traversal check passed for: {}", path);
        }
        return chain.filter(exchange);
    }

    /** Sondes de disponibilité, appelées en boucle par la découverte de services. */
    private boolean estSondeDeSante(String path) {
        return path.startsWith("/actuator/health") || path.startsWith("/actuator/info");
    }
    
    /**
     * Detecte une tentative de traversee de repertoire.
     *
     * <h2>Pourquoi un controle par SEGMENTS et non par motif positionnel</h2>
     *
     * L'ancien motif etait {@code .*[/\\]\.\.[\\/].*} : il exigeait que
     * {@code ..} soit <b>entoure</b> de separateurs. Quatre variantes passaient,
     * verifiees une a une :
     *
     * <ul>
     *   <li>{@code /api/v1/auth/..} — {@code ..} en fin de chemin, sans barre
     *       finale. C'est la forme la plus courante, et beaucoup de serveurs
     *       normalisent {@code /a/b/..} en {@code /a} ;</li>
     *   <li>{@code /..} — a la racine ;</li>
     *   <li>{@code /api/v1/auth/....//x} — la variante {@code ....//}, que
     *       certains normaliseurs reduisent a {@code ../} ;</li>
     *   <li>{@code /api/v1/auth/..;/x} — le parametre de chemin, que Tomcat et
     *       Jetty retirent avant de resoudre.</li>
     * </ul>
     *
     * <h2>Et un faux positif supprime</h2>
     *
     * {@code ENCODED_PATH_TRAVERSAL} bloquait {@code %2e}, {@code %2f} et
     * {@code %5c} <b>n'importe ou</b>. Une requete legitime comme
     * {@code /api/v1/users/me?filter=a%2Fb} etait donc refusee : une barre
     * oblique encodee dans une valeur de parametre est parfaitement normale.
     * Bloquer du trafic valide a aussi un cout — on finit par desactiver le
     * filtre.
     *
     * <p>Le controle decode <b>une seule fois</b>, puis examine chaque segment.
     * Un segment compose uniquement de points et long d'au moins deux
     * caracteres n'a aucun usage legitime. Le double encodage reste refuse en
     * amont ({@code %25}) : decoder deux fois serait precisement ce qui permet
     * de contourner un controle a decodage unique.
     */
    private boolean isPathTraversal(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        String minuscule = value.toLowerCase(Locale.ROOT);

        // Octet nul : sert a tronquer un nom de fichier cote systeme.
        if (minuscule.contains("%00")) {
            return true;
        }

        // Double encodage : refuse avant tout decodage.
        if (minuscule.contains("%25")) {
            return true;
        }

        String decode;
        try {
            decode = URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Un encodage illisible n'a pas d'usage legitime ici.
            log.debug("Encodage invalide refuse : {}", e.getMessage());
            return true;
        }

        if (decode.indexOf('\0') >= 0) {
            return true;
        }

        // Seule la partie chemin est examinee segment par segment.
        String chemin = decode;
        int interrogation = chemin.indexOf('?');
        if (interrogation >= 0) {
            chemin = chemin.substring(0, interrogation);
        }

        for (String segment : chemin.split("[/\\\\]")) {
            // Les parametres de chemin (;jsessionid=..., ;) sont retires par le
            // conteneur avant resolution : les ignorer ici aussi.
            int pointVirgule = segment.indexOf(';');
            if (pointVirgule >= 0) {
                segment = segment.substring(0, pointVirgule);
            }
            if (estUniquementDesPoints(segment)) {
                return true;
            }
        }

        return false;
    }

    /** Un segment de deux points ou plus n'a aucun usage legitime. */
    private static boolean estUniquementDesPoints(String segment) {
        if (segment.length() < 2) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (segment.charAt(i) != '.') {
                return false;
            }
        }
        return true;
    }
}
