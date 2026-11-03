package com.abdatytch.auth_service.util;

import com.abdatytch.commons.security.GatewayClientIp;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

/**
 * Utilitaires pour extraire des informations des requêtes reactive
 */
public class ReactiveRequestUtil {
    
    /**
     * En-tete pose par le Gateway, porteur de l'adresse reelle du client.
     *
     * <p>Le Gateway est le seul composant place pour la connaitre : il voit le
     * pair TCP, et ne croit {@code X-Forwarded-For} que depuis un proxy declare.
     * Il purge par ailleurs cet en-tete de toute requete entrante, de sorte qu'un
     * client ne peut pas le fournir lui-meme.
     */
    public static String getClientIp(ServerWebExchange exchange) {
        return getClientIp(exchange.getRequest());
    }

    /**
     * Adresse du client, telle qu'etablie par le Gateway.
     *
     * <h3>Ce que corrige cette version</h3>
     *
     * Cette methode lisait {@code X-Forwarded-For} puis {@code X-Real-IP} avant
     * de retomber sur l'adresse du pair. Or le Gateway ne purgeait pas ces
     * en-tetes : <b>toute IP journalisee dans AuthenticationLog etait fournie par
     * le client</b>.
     *
     * <p>Consequences : la piste d'audit ne valait rien — aucune IP de ces
     * journaux n'etait digne de foi —, un plafond par IP se contournait en
     * faisant varier l'en-tete, et la geolocalisation des alertes de securite
     * designait une adresse choisie par l'attaquant.
     *
     * <p>Le Gateway purge desormais ces en-tetes et pose {@code X-Client-Ip}.
     * C'est la seule source a laquelle se fier. Le repli sur le pair TCP couvre
     * le cas d'un appel qui ne passerait pas par le Gateway — lequel serait de
     * toute facon refuse par {@code GatewayAccessWebFilter}.
     */
    public static String getClientIp(ServerHttpRequest request) {
        return GatewayClientIp.of(request);
    }
    
    /**
     * Extrait le User-Agent de la requête
     */
    public static String getUserAgent(ServerWebExchange exchange) {
        return getUserAgent(exchange.getRequest());
    }
    
    /**
     * Extrait le User-Agent d'une ServerHttpRequest
     */
    public static String getUserAgent(ServerHttpRequest request) {
        String userAgent = request.getHeaders().getFirst("User-Agent");
        return userAgent != null ? userAgent : "unknown";
    }
}
