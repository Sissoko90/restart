package com.abdatytch.gateway_service.filter;

import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.gateway_service.security.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import com.abdatytch.gateway_service.routing.CheminCible;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**

 * Filtre WebFilter qui ajoute des headers de sécurité HMAC à toutes les requêtes sortantes du Gateway
 *
 * Headers ajoutés:
 * - X-Gateway-Timestamp: Timestamp de la requête (protection anti-replay)
 * - X-Gateway-Signature: Signature HMAC-SHA256 (authentification Gateway)
 * - X-Gateway-Request-Id: UUID unique (détection de rejeu)
 *
 * Sécurité:
 * - Signature unique par requête (timestamp + path + method + requestId)
 * - Protection contre replay attacks (timestamp validé côté service)
 * - Impossible de forger sans connaître secretKey et algorithme exact
 *
 * <p><b>L'en-tête X-Secret-Key n'est plus émis.</b> Il transportait la clé
 * partagée en clair vers chaque service, à chaque requête : le moindre journal
 * d'en-têtes, trace de débogage ou capture réseau interne la divulguait, et
 * elle authentifie tout le trafic inter-services. Les services valident
 * uniquement la signature HMAC (voir {@code GatewayAccessWebFilter}), qui
 * prouve la même chose sans jamais transmettre le secret.
 *
 * <p><b>Limite connue.</b> La signature couvre la méthode, le chemin, l'horodatage
 * et l'identifiant de requête — pas le corps. Elle authentifie l'origine de la
 * requête, elle n'en garantit pas l'intégrité. C'est acceptable tant que le
 * réseau entre le Gateway et les services est maîtrisé ; ça ne l'est plus si ce
 * trafic traverse un segment partagé.
 */
@Component
public class GatewaySignatureFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewaySignatureFilter.class);

    /**

     * Headers d'identité réservés au Gateway : ils sont dérivés du JWT validé et ne
     * doivent JAMAIS provenir du client. Ils sont purgés de toute requête entrante.
     */
    private static final List<String> TRUSTED_IDENTITY_HEADERS = List.of(
        "X-Auth-User-Id",
        "X-User-Role",
        "X-Gateway-Timestamp",
        "X-Gateway-Signature",
        "X-Gateway-Request-Id",
        "X-Gateway-Source",
        "X-Secret-Key",
        "X-Admin-Secret",
        // Ces deux en-tetes n'etaient pas purges, et auth-service les lisait en
        // priorite pour journaliser l'adresse de l'appelant. Toute IP des
        // journaux d'authentification etait donc fournie par le client : la
        // piste d'audit ne valait rien, et un plafond par IP se contournait en
        // faisant varier l'en-tete.
        //
        // Le Gateway les remplace desormais par X-Client-Ip, seule adresse a
        // laquelle les services aval doivent se fier.
        "X-Forwarded-For",
        "X-Real-IP",
        "X-Client-Ip");

    /**
     * En-tete porteur de l'adresse reelle du client, etablie par
     * {@code ClientIpResolver}.
     *
     * <p>Le Gateway est le seul composant place pour la connaitre : il voit le
     * pair TCP, et ne croit {@code X-Forwarded-For} que depuis un proxy declare
     * dans {@code gateway.trusted-proxies}.
     */
    public static final String CLIENT_IP_HEADER = "X-Client-Ip";

    private final HMACSignatureUtil hmacSignatureUtil;
    private final ClientIpResolver clientIpResolver;

    @Value("${gateway.secret.key}")
    private String secretKey;

    @Value("${admin.secret.key}")
    private String adminSecretKey;

    public GatewaySignatureFilter(HMACSignatureUtil hmacSignatureUtil,
                                  ClientIpResolver clientIpResolver) {
        this.hmacSignatureUtil = hmacSignatureUtil;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String originalPath = request.getPath().value();
        String method = request.getMethod().name();

        // La PURGE des en-tetes d'identite a lieu dans TOUS les cas, y compris
        // sur les chemins publics. Seule la signature est sautee.
        //
        // La version precedente retournait immediatement sur un chemin public,
        // sans rien purger. Les routes re-excluent bien ces en-tetes au moment
        // de transmettre, donc la defense en profondeur tenait — mais faire
        // reposer la purge sur une seconde couche alors qu'elle coute une ligne
        // ici est un choix difficile a defendre. Purger tot, toujours.
        if (isPublicEndpoint(originalPath)) {
            ServerHttpRequest purgee = request.mutate()
                .headers(headers -> TRUSTED_IDENTITY_HEADERS.forEach(headers::remove))
                .header(CLIENT_IP_HEADER, clientIpResolver.resolve(exchange))
                .build();
            return chain.filter(exchange.mutate().request(purgee).build());
        }

        // Transformer le path pour correspondre à ce que le service recevra.
        //
        // Le calcul est delegue a CheminCible, et c'est essentiel : le routeur
        // doit construire EXACTEMENT ce chemin pour proxifier. Les deux le
        // faisaient chacun de leur cote ; a la premiere reecriture, un ecart
        // d'un caractere refuserait chaque requete en 403, avec un message
        // parlant de signature invalide et non de chemin.
        String targetPath = CheminCible.pour(originalPath);

        // Générer timestamp et request-id
        String timestamp = String.valueOf(System.currentTimeMillis());
        String requestId = UUID.randomUUID().toString();

        // Construire données à signer avec le path transformé (ce que le service recevra)
        String signatureData = hmacSignatureUtil.buildSignatureData(timestamp, targetPath, method, requestId);

        // Générer signature HMAC-SHA256
        String signature = hmacSignatureUtil.generateSignature(secretKey, signatureData);

        // Ajouter headers de sécurité
        // NB: `headers(...)` s'applique AVANT `header(...)`; on purge d'abord les headers
        // d'identité que seul le Gateway a le droit de produire. Sans cela, un client
        // pourrait envoyer X-Auth-User-Id/X-User-Role et ils seraient recopiés tels quels
        // vers les services aval lorsque le Gateway ne parvient pas à les dériver du JWT.
        ServerHttpRequest mutatedRequest = request.mutate()
            .headers(headers -> TRUSTED_IDENTITY_HEADERS.forEach(headers::remove))
            .header("X-Gateway-Timestamp", timestamp)
            .header("X-Gateway-Signature", signature)
            .header("X-Gateway-Request-Id", requestId)
            .header(CLIENT_IP_HEADER, clientIpResolver.resolve(exchange))
            .header("X-Gateway-Source", "spring-cloud-gateway")  // Identification Gateway
            .build();

        // Ajouter header X-Admin-Secret pour les requêtes admin
        if (originalPath.startsWith("/api/v1/admin/") && adminSecretKey != null && !adminSecretKey.isEmpty()) {
            mutatedRequest = mutatedRequest.mutate()
                .header("X-Admin-Secret", adminSecretKey)
                .build();
            log.debug(" Added X-Admin-Secret for admin endpoint: {}", originalPath);
        }

        log.debug(" Added HMAC signature for {} {} -> {} (requestId: {})", method, originalPath, targetPath, requestId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**

     * Vérifie si l'endpoint est public (pas besoin de signature)
     */
    private boolean isPublicEndpoint(String path) {
        // startsWith, et NON contains.
        //
        // `contains("/v3/api-docs")` exemptait de signature tout chemin
        // comportant cette chaine n'importe ou — y compris
        // /api/v1/auth/quelquechose/v3/api-docs, qui correspond bien a la route
        // /api/v1/auth/**. La requete etait alors transmise SANS signature.
        //
        // Elle echouait en aval, le GatewayAccessWebFilter exigeant la
        // signature : le comportement etait donc ferme, pas ouvert. Mais un
        // controle de contournement doit designer exactement les chemins voulus,
        // sans quoi la prochaine exemption ajoutee en aval change la portee de
        // celui-ci sans que personne ne fasse le lien. C'est le meme defaut que
        // les exemptions /actuator/ corrigees dans les cinq filtres des
        // services.
        return path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/webjars/");
    }

    @Override
    public int getOrder() {
        // Un ordre strictement inférieur à celui de Spring Security (-100) : à
        // égalité, l'ordre entre les deux n'était pas défini. Les en-têtes
        // d'identité doivent être purgés le plus tôt possible, avant que quoi que
        // ce soit d'autre ne les lise.
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
