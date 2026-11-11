package com.abdatytch.user_service.filter;

import com.abdatytch.commons.security.CheminDeDecision;
import com.abdatytch.commons.security.CheminsPublics;
import com.abdatytch.commons.security.CheminInterne;
import com.abdatytch.commons.security.GatewayClientIp;

import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.commons.security.PrivateNetworks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

/**

 * WebFilter RÉACTIF pour vérifier que les requêtes proviennent du Gateway
 * Migré de Servlet Filter vers WebFlux WebFilter
 * PRÉSERVE TOUTE LA LOGIQUE: validation signature HMAC, IP whitelist, paths exclus
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class GatewayAccessFilter implements WebFilter {

    private static final Set<String> ALLOWED_IPS = Set.of(
        "127.0.0.1",
        "localhost",
        "0:0:0:0:0:0:0:1",
        "::1",
        "gateway-service");

    @Value("${gateway.secret.key}")
    private String secretKey;

    private final HMACSignatureUtil hmacSignatureUtil;

    /**
     * Les chemins exemptes du controle d'acces.
     *
     * <p>La regle vit desormais dans {@code CheminsPublics} de
     * platform-commons. Les cinq services en tenaient chacun une copie, et
     * <b>trois defauts de la meme famille</b> en sont sortis — un controle
     * qui designe plus large que son intention. Les trois sont maintenant
     * des cas de test nommes dans {@code CheminsPublicsTest}, et le
     * constructeur <b>refuse</b> ce qui ressemble a un motif : ce qui
     * n'etait qu'un avertissement en commentaire est devenu une erreur de
     * demarrage.
     *
     * <p>La documentation OpenAPI suit la <b>troisieme categorie</b> :
     * signature de passerelle <b>exigee</b>, jeton non. Elle n'est donc pas
     * lisible en attaquant le port du service en direct, et elle est servie a
     * une requete que la passerelle a signee. C'est la passerelle qui agrege
     * les documents et decide qui peut les lire — une surface a proteger au
     * lieu de sept.
     */
    private static final CheminsPublics CHEMINS_PUBLICS = CheminsPublics.sondes()
            .pageDErreur()
            .documentationViaPasserelle()
            .construire();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        var response = exchange.getResponse();

        // Autoriser les endpoints actuator (health, metrics), pages d'erreur et Swagger/OpenAPI
        String requestURI = request.getPath().value();
        // Le chemin de DECISION, decode, distinct du chemin SIGNE.
        //
        // La signature HMAC porte sur le chemin BRUT : la passerelle signe
        // exactement ce que le service recoit, et y substituer le chemin decode
        // ferait echouer TOUTES les verifications, avec un 403 parlant de
        // signature invalide et jamais de chemin.
        //
        // Mais la question « ce chemin est-il public » doit, elle, se decider
        // sur le chemin decode : les `pathMatchers` de SecurityConfig comparent
        // des segments decodes, et une exemption qui ne voit pas la meme
        // requete qu'eux est une exemption qui ne protege pas ce qu'elle croit.
        String cheminDeDecision = CheminDeDecision.de(request);
        // Les trois sondes sont nommees, et NON /actuator/ en bloc.
        //
        // `contains("/actuator/")` exemptait tout le sous-arbre : env,
        // httptrace et loggers passaient sans signature ni jeton. Et
        // `contains` et non `startsWith` : n'importe quel chemin contenant
        // la chaine suffisait, donc `/auth/x/actuator/y` aussi.
        if (CHEMINS_PUBLICS.contient(cheminDeDecision)) {
            return chain.filter(exchange);
        }

        // 1. Vérifier l'IP source (protection contre accès direct)
        String clientIp = getClientIp(exchange);
        if (!isAllowedIp(clientIp)) {
            log.warn("Direct access blocked from IP: {} to {}", clientIp, requestURI);
            return sendForbiddenResponse(exchange, requestURI,
                "Direct access not allowed. Requests must go through Gateway.");
        }

        // 2. Appel inter-services : la signature de passerelle est levee.
        //
        // APRES le controle d'adresse ci-dessus, et c'est l'ordre qui compte :
        // un chemin /internal reste reserve au reseau prive.
        //
        // Cette exemption MANQUAIT, et InternalUserReactiveController etait donc
        // injoignable — un controleur complet et teste, derriere une porte
        // fermee. Rien ne le signalait : son unique appelant avait ete retire,
        // il ne restait qu'un commentaire pour en parler, et la passerelle
        // n'expose aucune route vers /internal/** donc aucun trafic externe ne
        // venait buter dessus.
        //
        // interface-service avait deja paye ce defaut, en pire : son
        // POST /internal/jwt/sign repondait 403, auth-service n'obtenait aucun
        // jeton, et plus aucune connexion n'aboutissait.
        //
        // Ce qui protege ce chemin a la place : l'adresse privee verifiee
        // ci-dessus, et X-Internal-Secret compare a temps constant par
        // AutorisationInterne dans la chaine de securite — voir le
        // `.access(autorisationInterne)` de SecurityConfig.
        if (CheminInterne.correspond(cheminDeDecision)) {
            return chain.filter(exchange);
        }

        // 3. Vérifier la signature HMAC (OBLIGATOIRE)
        String timestamp = request.getHeaders().getFirst("X-Gateway-Timestamp");
        String signature = request.getHeaders().getFirst("X-Gateway-Signature");
        String requestId = request.getHeaders().getFirst("X-Gateway-Request-Id");

        // Vérifier que tous les headers HMAC sont présents
        if (timestamp == null || signature == null || requestId == null) {
            log.warn("Missing HMAC headers from IP: {} to {}", clientIp, requestURI);
            return sendForbiddenResponse(exchange, requestURI,
                "Missing required security headers. Requests must go through Gateway.");
        }

        // Valider timestamp (anti-replay)
        if (!hmacSignatureUtil.isTimestampValid(timestamp)) {
            log.warn("Expired or invalid timestamp from IP: {}", clientIp);
            return sendForbiddenResponse(exchange, requestURI, "Request timestamp expired");
        }

        // Valider signature HMAC
        String signatureData = hmacSignatureUtil.buildSignatureData(
            timestamp, requestURI, request.getMethod().name(), requestId
        );

        if (!hmacSignatureUtil.validateSignature(secretKey, signatureData, signature)) {
            log.warn("Invalid HMAC signature from IP: {}", clientIp);
            return sendForbiddenResponse(exchange, requestURI, "Invalid request signature");
        }

        log.debug("HMAC signature validated for {} {}", request.getMethod(), requestURI);
        log.debug("Request authorized: {}", requestURI);

        // Requête autorisée
        return chain.filter(exchange);
    }

    private Mono<Void> sendForbiddenResponse(ServerWebExchange exchange, String requestURI, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        String jsonResponse = String.format(
            "{\"timestamp\":\"%s\",\"status\":403,\"error\":\"Forbidden\",\"message\":\"Access Denied\",\"path\":\"%s\",\"reason\":\"%s\",\"details\":\"Requests must include valid Gateway signature\"}",
            java.time.LocalDateTime.now().toString(),
            requestURI,
            reason
        );

        return exchange.getResponse().writeWith(
            Mono.just(exchange.getResponse().bufferFactory().wrap(jsonResponse.getBytes()))
        );
    }

    /**
     * Adresse du <b>pair TCP</b>, sur laquelle repose la decision de reseau.
     *
     * <h3>Deux defauts successifs au meme endroit</h3>
     *
     * Elle lisait d'abord {@code X-Forwarded-For} : l'appelant choisissait
     * l'adresse sur laquelle reposait la decision. Corrige par
     * {@code GatewayClientIp.of()} — mais celui-ci privilegie l'en-tete
     * {@code X-Client-Ip}, que le Gateway pose et qu'un appelant <b>direct</b>
     * peut tout autant forger. Le defaut avait change de nom d'en-tete, pas de
     * nature.
     *
     * <p>Seul le pair TCP n'est pas choisi par l'appelant, et aucun proxy ne
     * s'interpose entre le Gateway et ce service. {@code of()} reste la bonne
     * valeur pour l'audit, jamais pour une decision.
     */
    private String getClientIp(ServerWebExchange exchange) {
        return GatewayClientIp.pairTcp(exchange);
    }

    /**
     * L'appelant est-il sur un réseau d'où le Gateway peut légitimement appeler ?
     *
     * <h3>Le défaut corrigé : {@code 172.} n'est pas une plage privée</h3>
     *
     * Le test {@code ip.startsWith("172.")} autorisait <b>172.0.0.0/8</b>, soit
     * 16,7 millions d'adresses. Or la plage privée est 172.<b>16</b>.0.0/12 :
     * elle s'arrête à 172.31. Tout ce qui est en dehors — 172.0 à 172.15 et
     * 172.32 à 172.255 — est de l'<b>espace public routable</b>. 172.217.x.x
     * appartient à Google.
     *
     * <p>La portée se mesure à ce que ce contrôle protège : le secret HMAC a été
     * committé (commit {@code 71af837}) et doit être considéré comme connu, donc
     * ce contrôle est le <b>dernier rempart</b> contre un appel direct au
     * service. Ce rempart acceptait 93 % d'une plage publique.
     *
     * <p>Le même défaut avait été corrigé dans auth-service, qui délègue à
     * {@code PrivateNetworks} depuis. Ce module était resté sur sa copie — deux
     * implémentations d'une même notion, dont une seule avait été réparée. C'est
     * exactement pourquoi la définition n'a qu'un détenteur.
     */
    private boolean isAllowedIp(String ip) {
        return ALLOWED_IPS.contains(ip) || PrivateNetworks.contient(ip);
    }

}
