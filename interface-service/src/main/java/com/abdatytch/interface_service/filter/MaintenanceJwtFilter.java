package com.abdatytch.interface_service.filter;

import com.abdatytch.interface_service.service.IDevJwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**

 * Applique le token JWT de maintenance sur {@code /maintenance/**}.
 *
 * <p><b>C'est la seule barrière d'autorisation de ces endpoints.</b> Ni Spring
 * Security ({@code /maintenance/**} est en {@code permitAll}) ni le Gateway
 * ({@code /api/v1/maintenance/} est déclaré public) ne les protègent : ce filtre
 * doit donc échouer fermé et ne laisser passer qu'un token de maintenance valide.
 *
 * <p>Historique du défaut corrigé ici :
 * <ul>
 * <li>la validation chargeait une clé publique depuis {@code classpath:keys/public.pem},
 * fichier <b>inexistant</b> (les clés réelles sont {@code dev_public.pem} /
 * {@code dev_private.pem}) : toute requête partait en exception ;</li>
 * <li>elle exigeait un claim {@code role=MAINTENANCE} que
 * {@code MaintenanceController} n'émet pas — il pose {@code type=maintenance}
 * avec {@code roles=[ADMIN]} ;</li>
 * <li>les méthodes du contrôleur déclarent {@code @RequestHeader("Authorization")}
 * sans jamais s'en servir : réparer la clé sans réparer le reste aurait ouvert
 * en grand {@code /unlock-account}, {@code /totp-config} et {@code /security-audit}.</li>
 * </ul>
 *
 * <p>La validation passe désormais par {@link IDevJwtService}, c'est-à-dire par la
 * même autorité et les mêmes clés (avec rotation) que le reste de la plateforme.
 */
@Order(OrdreDesFiltres.JWT_MAINTENANCE)
@Component
public class MaintenanceJwtFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJwtFilter.class);

    private static final String MAINTENANCE_PREFIX = "/maintenance";
    private static final String BEARER_PREFIX = "Bearer ";

    /** Claim `type` que porte un token émis par {@code POST /maintenance/auth}. */
    private static final String MAINTENANCE_TOKEN_TYPE = "maintenance";

    /**

     * Seul endpoint de maintenance accessible sans token : c'est lui qui le délivre,
     * après vérification du secret admin, de la clé de maintenance, du mot de passe
     * et du code TOTP.
     */
    private static final Set<String> UNAUTHENTICATED_ENDPOINTS = Set.of("/maintenance/auth");

    private final IDevJwtService jwtService;

    public MaintenanceJwtFilter(IDevJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (!path.startsWith(MAINTENANCE_PREFIX) || UNAUTHENTICATED_ENDPOINTS.contains(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("Accès maintenance sans token sur {}", path);
            return deny(exchange, HttpStatus.UNAUTHORIZED, "Token de maintenance requis");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        return jwtService.validateTokenReactive(token)
                // Pose sur le Mono<BOOLEAN>, et surtout pas sur le Mono<Void>
                // final : chain.filter() se termine vide quand tout va bien.
                //
                // Une validation vide ferait disparaitre la requete — le
                // flatMap ne se declencherait pas, et onErrorResume ne
                // s'applique pas puisqu'il n'y a pas d'erreur. Repli FALSE,
                // conforme a la politique d'echec ferme de ce filtre.
                //
                // La source ne peut plus etre vide depuis que
                // DevJwtServiceImpl.validateTokenReactive a son propre
                // defaultIfEmpty ; ce repli-ci est une seconde barriere, pour
                // que la garde ne depende pas d'une propriete d'ailleurs.
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(valid -> {
                    if (!Boolean.TRUE.equals(valid)) {
                        log.warn("Token de maintenance invalide ou révoqué sur {}", path);
                        return deny(exchange, HttpStatus.UNAUTHORIZED, "Token de maintenance invalide");
                    }

                    // Un token utilisateur ou développeur ordinaire ne doit pas ouvrir
                    // la maintenance : on exige explicitement le type dédié.
                    String tokenType = jwtService.extractClaim(token, "type");
                    if (!MAINTENANCE_TOKEN_TYPE.equals(tokenType)) {
                        log.warn("Token de type '{}' refusé sur {} (type '{}' attendu)",
                                tokenType, path, MAINTENANCE_TOKEN_TYPE);
                        return deny(exchange, HttpStatus.FORBIDDEN, "Token de maintenance requis");
                    }

                    log.info("Accès maintenance autorisé sur {}", path);
                    return chain.filter(exchange);
                })
                .onErrorResume(error -> {
                    // Échouer fermé : aucune erreur de validation ne doit laisser passer
                    log.error("Erreur de validation du token de maintenance sur {} : {}",
                            path, error.getMessage());
                    return deny(exchange, HttpStatus.UNAUTHORIZED, "Token de maintenance invalide");
                });
    }

    /**

     * Réponse d'échec volontairement laconique : ne pas indiquer à l'appelant
     * laquelle des vérifications a échoué.
     */
    private Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"status\":%d,\"error\":\"%s\"}", status.value(), message);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        // Avant les contrôleurs, après les filtres de périmètre (IP, rate limit)
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
