package com.abdatytch.gateway_service.security;

import com.abdatytch.gateway_service.service.GeoLocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**

 * Filtre de géolocalisation pour bloquer des pays spécifiques
 * Utilise MaxMind GeoIP2 pour résoudre le pays d'une IP
 *
 * Configuration:
 * - geolocation.enabled=true/false
 * - geolocation.database.path=path/to/GeoLite2-Country.mmdb
 *
 * Gestion dynamique via Redis:
 * - redis-cli SADD geo_blocked_countries "CN"* - redis-cli SREM geo_blocked_countries "CN"* - redis-cli SMEMBERS geo_blocked_countries
 *
 * Ou via API REST:
 * - POST /api/v1/admin/geo/block?country=CN
 * - DELETE /api/v1/admin/geo/unblock?country=CN
 * - GET /api/v1/admin/geo/blocked-countries
 */
public class GeoLocationWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(GeoLocationWebFilter.class);

    private final GeoLocationService geoLocationService;
    private final ClientIpResolver clientIpResolver;

    public GeoLocationWebFilter(GeoLocationService geoLocationService,
                                ClientIpResolver clientIpResolver) {
        this.geoLocationService = geoLocationService;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Skip si géolocalisation désactivée
        if (!geoLocationService.isEnabled()) {
            return chain.filter(exchange);
        }

        // Skip pour endpoints publics (health, swagger, etc.)
        String path = exchange.getRequest().getPath().value();
        if (isWhitelistedPath(path)) {
            return chain.filter(exchange);
        }

        String clientIp = clientIpResolver.resolve(exchange);

        // Vérifier si l'IP est bloquée par pays
        return geoLocationService.isIpBlocked(clientIp)
            // defaultIfEmpty sur le Mono<Boolean>, PAS sur le Mono<Void> final.
            //
            // onErrorResume ne rattrape que les erreurs. Un Mono vide traversait
            // les flatMap sans les declencher : la requete n'etait ni refusee ni
            // transmise, et le client attendait jusqu'a son delai d'expiration.
            // Ce n'est pas une ouverture, c'est une perte.
            //
            // Le poser sur le resultat final serait le piege inverse :
            // chain.filter() rend un Mono<Void> qui se termine vide en cas de
            // SUCCES, donc le repli se declencherait a chaque requete acceptee.
            //
            // Valeur de repli `false`, coherente avec l'ouverture assumee par le
            // onErrorResume en aval.
            .defaultIfEmpty(Boolean.FALSE)
            .flatMap(isBlocked -> {
                if (Boolean.TRUE.equals(isBlocked)) {
                    // Obtenir le code pays pour le log
                    // La recherche de pays sert UNIQUEMENT a enrichir le
                    // message. Si elle rend un Mono vide, le refus ne doit pas
                    // disparaitre avec elle : c'etait le cas, et une adresse
                    // bloquee dont le pays etait introuvable voyait sa requete
                    // perdue au lieu d'etre refusee. Une recherche informative
                    // ne peut pas conditionner une decision de securite.
                    return geoLocationService.getCountryCode(clientIp)
                        .defaultIfEmpty("inconnu")
                        .flatMap(countryCode -> {
                            log.warn(" Blocked request from country: {} (IP: {}) on path: {}",
                                countryCode, clientIp, path);

                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

                            String errorJson = String.format(
                                "{\"error\":\"Access denied\",\"message\":\"Requests from your country (%s) are not allowed\",\"ip\":\"%s\"}",
                                countryCode, clientIp
                            );

                            return exchange.getResponse()
                                .writeWith(Mono.just(exchange.getResponse()
                                    .bufferFactory()
                                    .wrap(errorJson.getBytes())));
                        });
                }

                log.debug(" GeoLocation check passed for IP: {}", clientIp);
                return chain.filter(exchange);
            })
            .onErrorResume(error -> {
                // En cas d'erreur, laisser passer (fail-open) mais logger
                log.error(" GeoLocation check error for IP {}: {}", clientIp, error.getMessage());
                return chain.filter(exchange);
            });
    }

    /**

     * Vérifie si le path est whitelisté (pas de géo-blocage)
     */
    private boolean isWhitelistedPath(String path) {
        return path.startsWith("/actuator/") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs") ||
               path.startsWith("/webjars/") ||
               path.equals("/") ||
               path.equals("/health");
    }

}
