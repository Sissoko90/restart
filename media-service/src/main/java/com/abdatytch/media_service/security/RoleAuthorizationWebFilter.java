package com.abdatytch.media_service.security;

import com.abdatytch.commons.security.CheminDeDecision;
import com.abdatytch.commons.security.JwtClaimsReader;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filtre de vérification des rôles pour les endpoints admin
 */
@Component
@Order(3)
@Slf4j
public class RoleAuthorizationWebFilter implements WebFilter {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Les chemins d'administration — <b>tous</b>.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * La liste ne contenait que {@code /admin/documents}. Or ce module expose
     * <b>deux</b> contrôleurs d'administration, et le second —
     * {@code /admin/document-types}, onze endpoints dont la création, la
     * suppression et l'attribution de rôles aux types de documents — n'était pas
     * couvert par ce filtre.
     *
     * <p>La règle déclarative {@code /admin/**} de {@code SecurityConfig} le
     * couvrait, elle, et c'est elle le détenteur principal : une liste de
     * préfixes tenue à la main ici se désynchronise du jour où un contrôleur
     * s'ajoute, ce qui vient de se produire. Mais ce filtre existe comme seconde
     * couche, et une seconde couche qui protège la moitié du périmètre donne une
     * assurance qu'elle n'honore pas.
     *
     * <p>Le préfixe est donc {@code /admin}, qui couvre tout le sous-arbre, et
     * non une énumération de contrôleurs.
     */
    private static final List<String> ADMIN_PATHS = List.of(
        "/admin"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Le chemin de DECISION, decode. `/%61dmin/documents` ne declenchait
        // pas ce filtre alors que la regle declarative /admin/** le reconnait :
        // la seconde couche annoncee etait inoperante sur cette forme.
        String path = CheminDeDecision.de(exchange);

        // Vérifier si c'est un endpoint admin
        boolean isAdminPath = ADMIN_PATHS.stream().anyMatch(path::startsWith);
        
        if (isAdminPath) {
            // Extraire les claims du JWT (ajoutés par JwtAuthenticationWebFilter)
            Object claimsObj = exchange.getAttribute("claims");
            
            if (claimsObj == null) {
                log.warn("Admin access denied - No JWT claims found for path: {}", path);
                return writeForbiddenResponse(exchange, "Accès refusé - Authentification requise");
            }

            // Vérifier le rôle
            try {
                io.jsonwebtoken.Claims claims = (io.jsonwebtoken.Claims) claimsObj;

                // La lecture est déléguée à platform-commons : `roles` est une
                // LISTE pour un jeton d'utilisateur final. L'ancien
                // `claims.get("roles", String.class)` levait donc une exception,
                // captée plus bas et transformée en 403 : un administrateur
                // authentifié se voyait refuser l'accès aux documents.
                if (!JwtClaimsReader.hasRole(claims, "ADMIN")) {
                    // Le role de l'appelant est JOURNALISE, jamais RENVOYE.
                    //
                    // Le message rendait auparavant « Votre role actuel: X ». Un
                    // refus n'a pas a decrire le modele d'autorisation a qui
                    // vient de s'y heurter : cela confirme a la fois le role
                    // reconnu dans le jeton et le role attendu par le chemin,
                    // soit de quoi cartographier les privileges sans y avoir
                    // droit. C'est la meme raison qui a fait retirer
                    // `X-Error-Reason` du filtre d'acces de ce module.
                    //
                    // Le code 403 est CONSERVE. La regle « refuser en 404 » de
                    // ce depot vise les refus de propriete de ressource (IDOR),
                    // ou le statut confirmerait l'existence d'un identifiant
                    // enumerable. Ici le chemin est statique et publie dans
                    // l'OpenAPI : un 404 n'y masquerait rien, et effacerait la
                    // distinction utile entre 401 « authentifie-toi » et 403
                    // « tu n'as pas le droit ».
                    // La valeur extraite est un NOM DE ROLE, jamais un jeton ni
                    // un code. Elle est nommee plutot qu'appelee en ligne pour
                    // que ce qui part dans le journal se lise sans remonter a
                    // JwtClaimsReader — `AucunJustificatifDansLesJournauxTest`
                    // de platform-commons balaie le texte de l'appel, et un
                    // `claims` inline y ressemble a une fuite.
                    //
                    // Le role est conserve au journal deliberement : c'est lui
                    // qui a permis de diagnostiquer le defaut ou un
                    // administrateur authentifie se voyait refuser l'acces aux
                    // documents, faute d'une lecture correcte du claim `roles`.
                    String roleDeLAppelant = JwtClaimsReader.primaryRole(claims);
                    log.warn("Admin access denied - User role: {} for path: {}",
                        roleDeLAppelant, path);
                    return writeForbiddenResponse(exchange, "Accès refusé");
                }

                log.debug("Admin access granted on path: {}", path);
            } catch (Exception e) {
                log.error("Error checking admin role", e);
                return writeForbiddenResponse(exchange, "Accès refusé - Erreur de vérification des permissions");
            }
        }

        return chain.filter(exchange);
    }
    
    /**
     * Écrit une réponse JSON 403 Forbidden
     */
    private Mono<Void> writeForbiddenResponse(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now().toString());
        errorResponse.put("status", 403);
        errorResponse.put("error", "Forbidden");
        errorResponse.put("message", message);
        errorResponse.put("path", exchange.getRequest().getPath().value());
        
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Error writing forbidden response", e);
            return exchange.getResponse().setComplete();
        }
    }
}
