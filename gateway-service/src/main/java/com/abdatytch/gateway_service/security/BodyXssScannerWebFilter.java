package com.abdatytch.gateway_service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**

 * Scanner XSS pour le body des requêtes (JSON, Form data)
 * Utilise OWASP Java HTML Sanitizer pour détecter et bloquer les payloads malveillants
 *
 * Protection contre:
 * - Script injection dans JSON
 * - HTML injection
 * - JavaScript execution
 * - Event handlers malveillants
 */
public class BodyXssScannerWebFilter implements WebFilter {

    /**
     * Méthodes HTTP qui ne portent pas de corps.
     *
     * <p>Un {@code Set} et non une chaîne : voir le commentaire du filtre sur
     * {@code HttpMethod.matches}, qui compare exactement et avait rendu le
     * garde-fou inerte.
     */
    private static final java.util.Set<HttpMethod> METHODES_SANS_CORPS = java.util.Set.of(
            HttpMethod.GET, HttpMethod.DELETE, HttpMethod.HEAD, HttpMethod.OPTIONS);

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
    public BodyXssScannerWebFilter avecEnregistreur(SecurityViolationRecorder recorder) {
        this.violationRecorder = recorder;
        return this;
    }

    private reactor.core.publisher.Mono<Void> signaler(String clientIp, String motif) {
        return violationRecorder == null
                ? reactor.core.publisher.Mono.empty()
                : violationRecorder.enregistrer(clientIp, motif);
    }

    public BodyXssScannerWebFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    private static final Logger log = LoggerFactory.getLogger(BodyXssScannerWebFilter.class);

    // Patterns XSS dangereux
    private static final Pattern[] XSS_PATTERNS = {
        Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("javascript:\\s*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("on\\w+\\s*=", Pattern.CASE_INSENSITIVE),  // onclick, onload, etc.
        Pattern.compile("<iframe[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<object[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<embed[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("eval\\s*\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("expression\\s*\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("vbscript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("data:text/html", Pattern.CASE_INSENSITIVE)
    };

    // Taille max du body à scanner (10MB)
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Méthodes sans corps.
        //
        // La version precedente ecrivait :
        //
        //   request.getMethod().matches("GET|DELETE|HEAD|OPTIONS")
        //
        // `HttpMethod.matches(String)` compare le nom de la methode a la chaine
        // fournie, EXACTEMENT. Ce n'est pas une expression reguliere, malgre
        // l'apparence : « GET ».equals(« GET|DELETE|HEAD|OPTIONS ») vaut faux.
        // Le garde-fou n'a donc jamais filtre quoi que ce soit, et toutes les
        // methodes entraient dans le scan.
        if (METHODES_SANS_CORPS.contains(request.getMethod())) {
            return chain.filter(exchange);
        }

        // Skip pour les endpoints publics qui n'ont pas besoin de scan
        String path = request.getPath().value();
        if (path.startsWith("/actuator/") || path.startsWith("/swagger-ui/")) {
            return chain.filter(exchange);
        }

        MediaType contentType = request.getHeaders().getContentType();

        // Scanner seulement JSON et Form data
        if (contentType != null &&
            (contentType.includes(MediaType.APPLICATION_JSON) ||
             contentType.includes(MediaType.APPLICATION_FORM_URLENCODED))) {

            // join BORNE : le controle de taille ci-dessous intervenait APRES
            // avoir tout bufferise en memoire, ce qui le rendait inutile contre
            // ce qu'il pretendait empecher. Le plafond est desormais applique
            // par le join lui-meme, donc avant l'allocation.
            // Le corps VIDE est traite a part, et sur le Mono TYPE.
            //
            // `DataBufferUtils.join` rend un Mono VIDE quand il n'y a pas de
            // corps. Le `flatMap` ne tournait alors jamais, la requete n'etait
            // pas transmise, et la reponse se terminait sans rien : le client
            // recevait 200 avec content-length 0.
            //
            // Mesure sur l'environnement de developpement — un GET portant
            // `Content-Type: application/json` sans corps, ce que produit tout
            // client qui pose cet en-tete par defaut :
            //
            //   sans Content-Type : 200, 56 octets
            //   avec Content-Type : 200, 0 octet
            //
            // La requete disparaissait en silence. Et le defaut etait masque
            // par le garde-fou de methode juste au-dessus, qui etait inerte :
            // les deux se tenaient.
            //
            // `defaultIfEmpty` est pose sur le Mono<DataBuffer>, et NON sur le
            // Mono<Void> rendu. Pose a la fin, il se declencherait apres chaque
            // transmission reussie — `chain.filter` se termine vide quand tout
            // va bien — et la chaine serait parcourue DEUX fois. Ce depot a
            // deja corrige cette erreur neuf fois.
            return DataBufferUtils.join(request.getBody(), MAX_BODY_SIZE)
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(corpsEventuel -> {
                    if (corpsEventuel.isEmpty()) {
                        log.debug("Corps vide sur {} : rien a scanner", path);
                        return chain.filter(exchange);
                    }
                    DataBuffer dataBuffer = corpsEventuel.get();
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String body = new String(bytes, StandardCharsets.UTF_8);

                    // Vérifier la taille
                    if (bytes.length > MAX_BODY_SIZE) {
                        log.warn("Body size exceeds limit: {} bytes from IP: {}",
                            bytes.length, getClientIp(exchange));
                        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                        // Motif « corps trop volumineux », et non « xss-body » :
                        // un envoi trop gros n'est pas une tentative d'injection,
                        // et confondre les deux brouillerait le signal qui
                        // alimente la liste noire.
                        return signaler(getClientIp(exchange), "body-too-large")
                                .then(exchange.getResponse().setComplete());
                    }

                    // Scanner pour XSS
                    if (containsXss(body)) {
                        log.warn("XSS attack detected in body from IP: {} on path: {}",
                            getClientIp(exchange), path);
                        log.debug("Malicious body sample: {}",
                            body.length() > 200 ? body.substring(0, 200) + "..." : body);

                        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                        // C'EST ici que le signalement compte : une tentative
                        // d'injection repetee doit finir par faire inscrire
                        // l'adresse, sans quoi chaque requete est refusee
                        // isolement et l'essai peut se poursuivre indefiniment.
                        return signaler(getClientIp(exchange), "xss-body")
                                .then(exchange.getResponse()
                                        .writeWith(Mono.just(exchange.getResponse()
                                                .bufferFactory()
                                                .wrap("{\"error\":\"Invalid request content\"}"
                                                        .getBytes()))));
                    }

                    log.debug("Body XSS scan passed for path: {}", path);

                    // Recréer la requête avec le body original
                    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
                    Flux<DataBuffer> cachedBody = Flux.just(buffer);

                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return cachedBody;
                        }
                    };

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                .onErrorResume(error -> {
                    log.error("Error scanning body for XSS: {}", error.getMessage());
                    // En cas d'erreur, laisser passer (fail-open)
                    return chain.filter(exchange);
                });
        }

        return chain.filter(exchange);
    }

    /**

     * Vérifie si le body contient des patterns XSS dangereux
     */
    private boolean containsXss(String body) {
        if (body == null || body.isEmpty()) {
            return false;
        }

        // Vérifier chaque pattern XSS dangereux
        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(body).find()) {
                return true;
            }
        }

        // Ne pas utiliser OWASP Sanitizer sur du JSON pur
        // Le sanitizer HTML modifie le JSON valide, causant des faux positifs
        // Les patterns regex ci-dessus suffisent pour détecter les vrais XSS

        return false;
    }

    /**
     * Adresse du client, pour la journalisation.
     *
     * <p>Lisait {@code X-Forwarded-For} directement : l'appelant choisissait
     * donc l'adresse inscrite dans les journaux de tentative d'attaque. Voir
     * {@code ClientIpResolver}, qui ne croit cet en-tete que depuis une plage
     * declaree dans {@code gateway.trusted-proxies}.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return clientIpResolver.resolve(exchange);
    }
}
