package com.abdatytch.commons.security;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

/**
 * Adresse du client, telle qu'etablie par le Gateway.
 *
 * <h2>Pourquoi cette classe existe</h2>
 *
 * Le schema suivant a ete trouve <b>huit fois</b> dans ce depot :
 *
 * <pre>{@code
 * String ip = request.getHeaders().getFirst("X-Forwarded-For");
 * if (ip != null) return ip.split(",")[0].trim();
 * }</pre>
 *
 * Quatre fois dans les filtres du Gateway, une fois dans le controle d'acces
 * direct d'auth-service, une fois dans sa journalisation d'audit, et trois fois
 * dans les filtres d'interface-service. Chaque fois avec le meme effet : l'IP sur
 * laquelle repose une decision de securite est <b>fournie par l'appelant</b>.
 *
 * <p>Consequences observees selon les cas : plafond de debit contournable en
 * faisant varier l'en-tete, liste noire d'IP inoperante, geo-blocage inoperant,
 * et piste d'audit sans valeur — aucune adresse des journaux d'authentification
 * n'etait digne de foi.
 *
 * <h2>Le seul composant qui connait l'adresse reelle</h2>
 *
 * C'est le Gateway : il voit le pair TCP, et ne croit {@code X-Forwarded-For} que
 * depuis une plage declaree dans {@code gateway.trusted-proxies}. Il purge cet
 * en-tete de toute requete entrante, puis pose {@code X-Client-Ip}.
 *
 * <p>Un service aval doit donc lire {@code X-Client-Ip}, et rien d'autre. Le
 * repli sur le pair TCP couvre un appel qui ne passerait pas par le Gateway —
 * lequel serait de toute facon refuse par le filtre de signature.
 */
public final class GatewayClientIp {

    /** En-tete pose par le Gateway. Voir {@code GatewaySignatureFilter}. */
    public static final String HEADER = "X-Client-Ip";

    private static final String INCONNUE = "unknown";

    private GatewayClientIp() {
    }

    public static String of(ServerWebExchange exchange) {
        return of(exchange.getRequest());
    }

    /** Voir {@link #pairTcp(ServerHttpRequest)}. */
    public static String pairTcp(ServerWebExchange exchange) {
        return pairTcp(exchange.getRequest());
    }

    /**
     * L'adresse du <b>pair TCP</b>, seule valeur qu'un appelant ne peut pas
     * choisir.
     *
     * <h3>Le defaut corrige</h3>
     *
     * Les filtres d'acces de quatre services decidaient « cet appel vient-il
     * d'un reseau prive » sur {@link #of(ServerHttpRequest)}, qui privilegie
     * l'en-tete {@code X-Client-Ip}. Un appel direct au port portant
     * {@code X-Client-Ip: 10.0.0.1} franchissait donc ce controle.
     *
     * <p>Cela comptait precisement parce que le secret HMAC de cette plateforme
     * figure dans l'historique Git : le controle de reseau etait cense etre le
     * <b>dernier rempart</b>, et il ne l'etait pas. Seul auth-service lisait le
     * pair TCP.
     *
     * <h3>Pourquoi aucun en-tete n'est consulte ici</h3>
     *
     * Aucun proxy ne s'interpose entre le Gateway et un service : le Gateway
     * les appelle directement. Le pair TCP est donc la seule information
     * necessaire, et la seule qu'un attaquant distant ne controle pas.
     *
     * @return l'adresse du pair, ou {@code "unknown"} si elle est indisponible
     *         — valeur qu'aucune plage privee ne reconnait, donc un refus
     */
    public static String pairTcp(ServerHttpRequest request) {
        if (request.getRemoteAddress() != null
                && request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return INCONNUE;
    }

    /**
     * L'adresse du <b>client final</b>, pour l'audit et la journalisation.
     *
     * <h3>Ce qu'elle vaut, et ce qu'elle ne vaut pas</h3>
     *
     * Elle rend l'en-tete {@code X-Client-Ip} pose par le Gateway, qui l'a
     * lui-meme derive du pair TCP ou d'un {@code X-Forwarded-For} venu d'un
     * proxy declare de confiance. C'est la bonne valeur pour dire <b>qui</b> a
     * agi : tracer une suppression, enqueter sur un incident.
     *
     * <p><b>Ce n'est PAS une valeur de confiance pour une decision de
     * securite.</b> Sur un appel direct au port du service, cet en-tete est
     * fourni par l'appelant : {@code X-Client-Ip: 10.0.0.1} suffit a se faire
     * passer pour une adresse interne. L'ancienne javadoc de cette classe
     * affirmait « jamais une valeur issue d'un en-tete que l'appelant
     * controle » — c'etait faux precisement dans le cas qui compte.
     *
     * <p>Pour decider si l'appelant est sur un reseau d'ou le Gateway peut
     * legitimement appeler, utiliser {@link #pairTcp(ServerHttpRequest)}.
     *
     * @return l'adresse posee par le Gateway, a defaut celle du pair TCP, a
     *         defaut {@code "unknown"}
     */
    public static String of(ServerHttpRequest request) {
        String fromGateway = request.getHeaders().getFirst(HEADER);
        if (fromGateway != null && !fromGateway.isBlank()) {
            return fromGateway.trim();
        }

        if (request.getRemoteAddress() != null
                && request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }

        return INCONNUE;
    }
}
