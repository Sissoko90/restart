package com.abdatytch.commons.security;

import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.util.stream.Collectors;

/**
 * Le chemin sur lequel une décision de sécurité doit être prise.
 *
 * <h2>Le défaut que cette classe ferme</h2>
 *
 * Les filtres comparaient {@code request.getPath().value()}, qui rend le chemin
 * <b>brut</b>, tandis que Spring Security et le routeur comparent leurs
 * {@code PathPattern} sur des segments <b>décodés</b>. Les deux ne voient donc
 * pas la même requête.
 *
 * <p>Conséquence mesurée sur {@code PathPattern} de Spring Framework 7 :
 * {@code /%73wagger-ui/index.html} ne commence <b>pas</b> par
 * {@code /swagger-ui} au sens d'une comparaison de chaînes, mais correspond
 * bien au motif {@code /swagger-ui/**}. Un filtre qui garde par
 * {@code startsWith} sur le brut est donc contourné, et la couche déclarative
 * sert ensuite la requête qu'elle croit autorisée.
 *
 * <p>La même forme existait sur {@code MaintenanceWebFilter}, qui résolvait le
 * service cible sur le chemin brut : {@code /api/v1/%6dedia/...} rendait « hors
 * routage », donc la fenêtre de maintenance d'un service ne s'appliquait pas à
 * une requête que le routeur dirigeait bien vers lui.
 *
 * <h2>Ce que fait la normalisation</h2>
 *
 * Elle reconstruit le chemin à partir des segments tels que {@code PathPattern}
 * les voit : {@code valueToMatch()} rend la valeur <b>décodée</b> et <b>sans
 * paramètre de chemin</b>. Ce dernier point écarte la variante
 * {@code /admin;x=1/documents}, qui franchissait un
 * {@code startsWith("/admin/")}.
 *
 * <h2>Ce qu'elle NE fait PAS, et c'est essentiel</h2>
 *
 * <b>Elle ne sert jamais à calculer ni à vérifier une signature HMAC.</b> La
 * passerelle signe le chemin brut transformé, et chaque service recalcule la
 * signature sur {@code getPath().value()}. Substituer ici le chemin décodé
 * ferait échouer <b>toutes</b> les vérifications, avec un 403 parlant de
 * signature invalide et jamais de chemin — le défaut serait cherché du côté des
 * clés.
 *
 * <p>Deux usages, deux chemins, et la distinction est la règle :
 * <ul>
 *   <li><b>signature</b> : {@code getPath().value()}, le brut, inchangé ;</li>
 *   <li><b>décision</b> — ce chemin est-il public, gardé, administratif, en
 *       maintenance — : cette classe.</li>
 * </ul>
 *
 * <p>Elle ne résout pas non plus {@code .} ni {@code ..} : c'est le travail de
 * {@code PathTraversalWebFilter}, qui refuse ces segments en amont. Les
 * résoudre ici donnerait deux détenteurs à une même règle.
 */
public final class CheminDeDecision {

    private CheminDeDecision() {
    }

    /** Voir {@link #de(ServerHttpRequest)}. */
    public static String de(ServerWebExchange exchange) {
        return de(exchange.getRequest());
    }

    /**
     * Le chemin décodé, sans paramètres de segment, tel que
     * {@code PathPattern} le compare.
     *
     * @return un chemin commençant toujours par {@code /} ; {@code "/"} pour
     *         une requête à la racine.
     */
    public static String de(ServerHttpRequest request) {
        String decode = request.getPath().elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(PathContainer.PathSegment.class::cast)
                .map(PathContainer.PathSegment::valueToMatch)
                .collect(Collectors.joining("/"));

        return decode.isEmpty() ? "/" : "/" + decode;
    }
}
