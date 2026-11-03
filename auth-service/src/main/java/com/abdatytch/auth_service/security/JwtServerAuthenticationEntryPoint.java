package com.abdatytch.auth_service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Réponse rendue quand une requête atteint un endpoint protégé sans identité
 * recevable.
 *
 * <p>C'est le point de sortie de toute la chaîne d'authentification : un défaut
 * dans {@code JwtAuthenticationWebFilter} se manifeste <b>ici</b>, sous la forme
 * d'un 401. La réponse doit donc être exploitable par un développeur légitime
 * sans renseigner un attaquant.
 *
 * <h2>Le défaut corrigé : la réponse disait POURQUOI</h2>
 *
 * Le corps portait {@code "details": ex.getMessage()}, soit le message interne
 * de l'exception Spring Security. Un appelant apprenait ainsi si son jeton était
 * absent, expiré, mal signé, émis par la mauvaise autorité ou destiné à une
 * autre audience — c'est-à-dire exactement <b>quel</b> contrôle il reste à
 * franchir, et donc lesquels sont déjà franchis.
 *
 * <p>C'est l'inverse de la politique appliquée au Gateway, dont le filtre JWT
 * porte la raison en commentaire : « Le détail reste dans les journaux : la
 * réponse ne dit jamais POURQUOI le jeton est refusé, pour ne pas guider un
 * attaquant. » Le détail est désormais journalisé, et lui seul.
 *
 * <h2>Pourquoi le niveau de journalisation change aussi</h2>
 *
 * Chaque requête non authentifiée produisait un {@code log.error}. Or l'absence
 * de jeton sur un endpoint protégé est un événement <b>ordinaire</b> : un client
 * mal configuré, un jeton expiré en cours de session, un robot d'indexation.
 * Les compter comme des erreurs serveur noie la supervision sous des
 * défaillances qui n'en sont pas — et ce sont ces alertes-là qu'on finit par
 * ignorer, au moment précis où une vraie panne survient.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JwtServerAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    /**
     * Message unique, quelle que soit la cause réelle.
     *
     * <p>Il doit rester le même pour un jeton absent, expiré, révoqué ou mal
     * signé : toute différence est un oracle.
     */
    private static final String MESSAGE_UNIFORME =
            "Authentification requise. Jeton absent ou non recevable.";

    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        String chemin = exchange.getRequest().getURI().getPath();

        // warn, et non error : une requete sans jeton sur un endpoint protege
        // est un evenement ordinaire, pas une defaillance du serveur.
        log.warn("Authentification refusée sur {} : {}", chemin, ex.getMessage());

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> corps = new HashMap<>();
        corps.put("timestamp", LocalDateTime.now().toString());
        corps.put("status", HttpStatus.UNAUTHORIZED.value());
        corps.put("error", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        corps.put("message", MESSAGE_UNIFORME);
        corps.put("path", chemin);

        try {
            byte[] octets = objectMapper.writeValueAsBytes(corps);
            DataBuffer tampon = exchange.getResponse().bufferFactory().wrap(octets);
            return exchange.getResponse().writeWith(Mono.just(tampon));
        } catch (Exception e) {
            // Le statut est deja pose : setComplete() le rend tel quel, sans
            // corps. Mieux vaut un 401 nu qu'une requete perdue.
            log.error("Échec d'écriture de la réponse 401 sur {}", chemin, e);
            return exchange.getResponse().setComplete();
        }
    }
}
