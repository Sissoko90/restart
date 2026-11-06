package com.abdatytch.interface_service.filter;

import com.abdatytch.commons.security.CheminInterne;
import com.abdatytch.commons.security.GatewayClientIp;
import com.abdatytch.commons.security.PrivateNetworks;
import com.abdatytch.commons.security.SecretMasking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**

 * Filtre de sécurité pour bloquer l'accès direct à interface-service
 * Seul le Gateway est autorisé à accéder aux endpoints
 *
 * SÉCURITÉ:
 * - Vérifie les headers Gateway (X-Gateway-Signature, X-Gateway-Timestamp, X-Gateway-Request-Id)
 * - Bloque tout accès direct (sans headers Gateway)
 * - Exceptions: endpoints publics (JWKS, health, docs)
 */
@Component
@Order(OrdreDesFiltres.ACCES_GATEWAY)
public class GatewayAccessFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(GatewayAccessFilter.class);

    // Aucun défaut : cette clé authentifie le Gateway auprès du service.
    @Value("${gateway.secret.key}")
    private String gatewaySecretKey;

    // Le prefixe des appels de service a service vit desormais dans
    // CheminInterne, de platform-commons : la comparaison par segment entier
    // etait ecrite trois fois, et user-service l'avait tout simplement oubliee.

    // Endpoints publics autorisés sans Gateway (ULTRA RESTREINTS)
    private static final List<String> PUBLIC_ENDPOINTS = List.of(
        "/actuator/health",             // Health check seulement
        "/dev/.well-known/jwks.json",   // JWKS pour validation JWT interne
        "/.well-known/jwks.json"// JWKS endpoint standard
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Autoriser les endpoints publics
        if (isPublicEndpoint(path)) {
            log.debug(" Accès public autorisé: {}", path);
            return chain.filter(exchange);
        }

        // Appels de service a service, qui ne passent PAS par le Gateway.
        if (CheminInterne.correspond(path)) {
            String pair = adresseDuPair(exchange);
            if (!PrivateNetworks.contient(pair)) {
                log.warn(" Appel interne refusé — pair non privé {} sur {}", pair, path);
                return accesRefuse(exchange, "Accès direct interdit. Utilisez le Gateway.");
            }
            log.debug(" Appel interne autorisé depuis {} : {}", pair, path);
            return chain.filter(exchange);
        }

        // Vérifier les headers Gateway obligatoires
        String timestamp = exchange.getRequest().getHeaders().getFirst("X-Gateway-Timestamp");
        String signature = exchange.getRequest().getHeaders().getFirst("X-Gateway-Signature");
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Gateway-Request-Id");

        if (timestamp == null || signature == null || requestId == null) {
            log.warn(" ACCÈS DIRECT BLOQUÉ - Headers Gateway manquants: {} (IP: {})",
                    path, getClientIp(exchange));
            return accesRefuse(exchange, "Accès direct interdit. Utilisez le Gateway.");
        }

        // Vérifier la validité du timestamp (anti-replay) - Gateway utilise des millisecondes
        try {
            long requestTime = Long.parseLong(timestamp);
            long currentTime = System.currentTimeMillis();
            long timeDiff = Math.abs(currentTime - requestTime);

            if (timeDiff > 5000) { // 5 secondes max (comme dans le Gateway)
                log.warn(" ACCÈS BLOQUÉ - Timestamp expiré: {} (diff: {}ms)", path, timeDiff);
                return accesRefuse(exchange, "Requête expirée");
            }
        } catch (NumberFormatException e) {
            log.warn(" ACCÈS BLOQUÉ - Timestamp invalide: {}", path);
            return accesRefuse(exchange, "Timestamp invalide");
        }

        // DEBUG: Headers Gateway reçus
        log.debug(" Headers Gateway reçus pour {}", path);

        // Vérifier la signature HMAC
        if (!verifyHmacSignature(exchange, timestamp, signature)) {
            // La valeur recue est masquee : c'est celle de l'appelant, donc pas
            // un secret a nous, mais l'empreinte suffit a correler des
            // tentatives entre elles et evite d'ecrire un justificatif brut
            // dans les journaux. verifyHmacSignature a deja journalise le
            // detail ; cette ligne ne garde que l'adresse.
            log.warn(" Signature Gateway invalide — pair {} sur {}",
                    adresseDuPair(exchange), path);
            return accesRefuse(exchange, "Signature Gateway invalide");
        }

        // SÉCURITÉ RENFORCÉE : Vérifier que la requête vient vraiment du Gateway
        if (!isRequestFromGateway(exchange)) {
            String clientIp = getClientIp(exchange);
            log.warn(" ACCÈS DIRECT DÉTECTÉ - IP: {} - Path: {} - Tentative de contournement",
                    clientIp, path);
            return accesRefuse(exchange, "Accès direct interdit - Utilisez le Gateway");
        }

        log.debug(" Accès Gateway autorisé: {} (Request-ID: {})", path, requestId);
        return chain.filter(exchange);
    }

    private boolean isPublicEndpoint(String path) {
        return PUBLIC_ENDPOINTS.stream().anyMatch(path::startsWith);
    }

    /**
     * Chemin d'appel de service à service, exempté de la signature du Gateway.
     *
     * <h3>Le blocage que cette exemption lève</h3>
     *
     * interface-service est l'autorité JWT : <b>tout</b> jeton de la plateforme
     * est signé par {@code POST /internal/jwt/sign}, appelé par auth-service.
     * Cet appel ne passe pas par le Gateway — il n'a aucune raison de le faire,
     * et le Gateway n'expose d'ailleurs aucune route vers {@code /internal/**}.
     *
     * <p>Or ce filtre exigeait les en-têtes de signature du Gateway sur ce
     * chemin. Conséquence mesurée sur l'environnement de développement :
     * {@code POST /internal/jwt/sign} répondait <b>403 « Accès direct interdit.
     * Utilisez le Gateway. »</b>, auth-service n'obtenait aucun jeton, et
     * <b>aucune connexion n'aboutissait</b> — ni administrateur, ni utilisateur
     * final. Le symptôme visible était un 500 à la connexion, qui ne désignait
     * pas la cause.
     *
     * <h3>Ce qui protège ce chemin à la place</h3>
     *
     * Deux contrôles, et il faut les deux :
     *
     * <ol>
     *   <li><b>ici</b>, le pair TCP doit être une adresse privée. Pas
     *       {@code X-Forwarded-For} ni {@code X-Client-Ip}, que l'appelant
     *       choisit : l'adresse du pair, qu'il ne peut pas falsifier ;</li>
     *   <li><b>dans le contrôleur</b>, {@code X-Internal-Secret} est obligatoire
     *       et comparé à temps constant par {@code SecretComparisonUtil.matches}
     *       sur chacun des endpoints de {@code InternalJwtController}.</li>
     * </ol>
     *
     * <p>Le secret est le contrôle qui porte : en conteneurs, « réseau privé »
     * englobe tous les voisins. L'exigence d'adresse privée n'est qu'une
     * barrière de plus, qui écarte l'exposition accidentelle du port sur
     * Internet.
     *
     * <p>Ce chemin n'entre <b>pas</b> dans {@link #PUBLIC_ENDPOINTS} : cette
     * liste contourne tout, y compris le contrôle d'adresse, et par
     * {@code startsWith} nu.
     */

    /**
     * Adresse du client, posee par le Gateway. Voir {@code GatewayClientIp} :
     * lire {@code X-Forwarded-For} laissait l'appelant choisir l'adresse sur
     * laquelle reposait la decision.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return GatewayClientIp.of(exchange);
    }

    /**

     * Vérifie la signature HMAC-SHA256 du Gateway
     * Format Gateway: timestamp|path|method|requestId (EXACTEMENT comme dans le Gateway)
     */
    private boolean verifyHmacSignature(ServerWebExchange exchange, String timestamp, String receivedSignature) {
        try {
            String path = exchange.getRequest().getPath().value();
            String method = exchange.getRequest().getMethod().name();
            String requestId = exchange.getRequest().getHeaders().getFirst("X-Gateway-Request-Id");

            // Construire la chaîne à signer EXACTEMENT comme le Gateway : timestamp|path|method|requestId
            String dataToSign = String.format("%s|%s|%s|%s", timestamp, path, method, requestId);

            // Calculer la signature HMAC-SHA256 avec Base64 (comme le Gateway)
            String expectedSignature = calculateHmacSha256Base64(dataToSign, gatewaySecretKey);

            // Comparaison sécurisée (évite les attaques de timing)
            boolean isValid = secureEquals(expectedSignature, receivedSignature);

            if (!isValid) {
                // La signature ATTENDUE n'est plus journalisee, meme tronquee.
                //
                // Le journal publiait ses dix premiers caracteres a CHAQUE
                // tentative invalide, c'est-a-dire a chaque sondage. Dix
                // caracteres Base64 font soixante bits de la signature attendue
                // pour un chemin choisi par l'appelant : repete sur plusieurs
                // chemins, cela construit un oracle sur le secret partage.
                //
                // Ce qui reste suffit au diagnostic : le chemin et la methode
                // signes, plus une empreinte de la valeur recue pour correler
                // des tentatives entre elles.
                log.warn(" Signature Gateway invalide sur {} {} — reçue {}",
                    method, path, SecretMasking.masquer(receivedSignature));
            }

            return isValid;

        } catch (Exception e) {
            log.error(" Erreur lors de la vérification HMAC: {}", e.getMessage());
            return false;
        }
    }

    /**

     * Calcule la signature HMAC-SHA256 avec encodage Base64 (comme le Gateway)
     */
    private String calculateHmacSha256Base64(String data, String key) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);

        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

        // Encoder en Base64 comme le Gateway
        return Base64.getEncoder().encodeToString(hmacBytes);
    }

    /**

     * Vérifier que la requête vient vraiment du Gateway
     * Vérifie plusieurs indicateurs pour détecter les tentatives de contournement
     */
    private boolean isRequestFromGateway(ServerWebExchange exchange) {
        // 1. En-tete pose par le Gateway.
        //
        // Il est fourni par l'appelant, donc forgeable : il ne prouve rien a lui
        // seul. Ce qui prouve la provenance est la signature HMAC, deja verifiee
        // en amont de cet appel. Ce controle reste comme filtre grossier, et son
        // absence de valeur probante doit etre dite plutot que supposee.
        String gatewayHeader = exchange.getRequest().getHeaders().getFirst("X-Gateway-Source");
        if (!"spring-cloud-gateway".equals(gatewayHeader)) {
            log.debug(" Header X-Gateway-Source manquant ou invalide: {}", gatewayHeader);
            return false;
        }

        // 2. Adresse du PAIR TCP, et non getClientIp().
        //
        // LE DEFAUT CORRIGE, et il rendait ce service inatteignable.
        //
        // `getClientIp()` delegue a GatewayClientIp.of(), qui rend en priorite
        // l'en-tete X-Client-Ip. Or le Gateway y place l'adresse de
        // l'UTILISATEUR FINAL (GatewaySignatureFilter lignes 119 et 148). La
        // comparaison portait donc sur l'adresse du client, jamais sur celle du
        // Gateway — et ne pouvait correspondre a localhost que si le client
        // lui-meme etait sur la machine.
        //
        // Consequence mesurable : toute requete venant d'un client distant,
        // acheminee par le Gateway et dont la signature HMAC etait VALIDE,
        // repondait « Accès direct interdit - Utilisez le Gateway ». Seul le
        // developpement local fonctionnait, ce qui explique que le defaut soit
        // passe inaperçu.
        //
        // Un controle cense prouver « cette requete vient du Gateway » ne peut
        // pas s'appuyer sur l'en-tete que le Gateway remplit avec l'adresse de
        // quelqu'un d'autre. Le pair TCP est la seule information sur l'identite
        // du connecte, et il n'est pas forgeable.
        String adressePair = adresseDuPair(exchange);
        if (!PrivateNetworks.contient(adressePair)) {
            log.debug(" Appel direct refusé, pair TCP hors réseau privé: {}", adressePair);
            return false;
        }

        return true;
    }

    /**
     * Adresse du pair TCP : qui s'est réellement connecté.
     *
     * <p>Aucun en-tête n'est consulté. {@code X-Forwarded-For} et
     * {@code X-Client-Ip} sont fournis par l'appelant ; les lire ici rendrait le
     * contrôle franchissable en une ligne de {@code curl}.
     */
    private String adresseDuPair(ServerWebExchange exchange) {
        var distant = exchange.getRequest().getRemoteAddress();
        if (distant != null && distant.getAddress() != null) {
            return distant.getAddress().getHostAddress();
        }
        return "inconnue";
    }

    /**

     * Comparaison sécurisée pour éviter les attaques de timing
     */
    private boolean secureEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }

        if (a.length() != b.length()) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }

        return result == 0;
    }

    private Mono<Void> accesRefuse(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");

        String jsonResponse = String.format(
            "{\"error\":\"Forbidden\",\"message\":\"%s\",\"timestamp\":\"%s\"}",
            message, Instant.now().toString()
        );

        var buffer = exchange.getResponse().bufferFactory().wrap(jsonResponse.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
