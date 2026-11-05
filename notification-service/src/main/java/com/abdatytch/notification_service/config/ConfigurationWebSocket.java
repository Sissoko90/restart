package com.abdatytch.notification_service.config;

import com.abdatytch.notification_service.web.GestionnaireWebSocket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

import java.util.Map;

/**
 * Publie le point d'entree WebSocket.
 *
 * <h2>Le chemin : {@code /notifications-flux}, et le tiret compte</h2>
 *
 * Pas {@code /notifications/flux}. La passerelle route les appels REST de ce
 * service par un {@code RouterFunction} sur {@code /api/v1/notifications/**},
 * et le {@code RouterFunctionMapping} de Spring a une priorite plus forte que
 * le {@code RoutePredicateHandlerMapping} de Spring Cloud Gateway.
 *
 * <p>Un chemin WebSocket sous {@code /notifications/} serait donc <b>masque</b> :
 * la passerelle le proxifierait avec {@code WebClient}, qui ne sait pas
 * effectuer une montee en protocole. La poignee de main echouerait avec une
 * erreur parlant de HTTP, jamais de WebSocket.
 *
 * <p>Avec un segment distinct, la route YAML {@code lb://notification-service}
 * s'applique — et Spring Cloud Gateway, lui, gere nativement la montee en
 * protocole.
 *
 * <h2>Pourquoi un ordre explicite</h2>
 *
 * {@code HIGHEST_PRECEDENCE + 1} : ce {@code HandlerMapping} doit etre consulte
 * avant celui des controleurs annotes. Sans ordre, Spring les classe de maniere
 * non garantie, et un jour un {@code @RequestMapping} large pourrait capter la
 * poignee de main — qui echouerait alors en 404 ou en 405, sans rapport visible
 * avec le WebSocket.
 */
@Configuration
public class ConfigurationWebSocket {

    /** Chemin interne, celui que le service recoit apres {@code StripPrefix=2}. */
    public static final String CHEMIN = "/notifications-flux";

    @Bean
    public SimpleUrlHandlerMapping webSocketHandlerMapping(GestionnaireWebSocket gestionnaire) {
        Map<String, WebSocketHandler> routes = Map.of(CHEMIN, gestionnaire);
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(routes);
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return mapping;
    }
}
