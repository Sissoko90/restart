package com.abdatytch.security_service.security;

/*
 * Reprise du patron de quotas-service et setting-service, deliberement a
 * l'identique.
 *
 * LE DEFAUT CORRIGE : security-service n'avait NI SecurityConfig, NI filtre
 * d'acces, NI filtre JWT — alors qu'il est route et que ses endpoints decident
 * de la politique de securite de toute la plateforme (pays bloques, regles
 * d'IP, fenetres de maintenance, seuils).
 *
 * `spring-boot-starter-security` etant au classpath sans aucune chaine
 * declaree, l'auto-configuration de Spring Boot s'appliquait : un utilisateur
 * `user` au mot de passe GENERE et journalise au demarrage, et aucun controle
 * de role. Un jeton porteur de la plateforme n'y etait pas accepte, donc les
 * sept endpoints etaient en pratique injoignables par la passerelle.
 *
 * Et son `AppelantAdministrateur` lit l'identite dans le SecurityContext avec
 * un javadoc affirmant « pose par JwtAuthenticationWebFilter » — ce filtre
 * n'existait pas ici. L'auteur de chaque decision de securite, que ce service
 * ecrit en base pour servir de preuve, n'aurait jamais pu etre renseigne.
 */

import com.abdatytch.commons.security.CheminDeDecision;
import com.abdatytch.commons.security.CheminsPublics;
import com.abdatytch.commons.security.GatewayClientIp;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.commons.security.PrivateNetworks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**

 * Interdit l'accès direct au port du service : toute requête doit porter une
 * signature HMAC valide produite par le Gateway.
 *
 * <p>Même contrat que auth-service, user-service et media-service
 * (voir {@link HMACSignatureUtil}) : {@code timestamp|path|method|requestId},
 * avec une fenêtre anti-rejeu de 5 secondes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GatewayAccessWebFilter implements WebFilter, Ordered {

    private final HMACSignatureUtil hmacSignatureUtil;

    @Value("${gateway.secret.key:}")
    private String gatewaySecretKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // Le chemin BRUT, et non `getURI().getPath()` qui le DECODE.
        //
        // La signature HMAC porte sur le chemin, et le Gateway signe la forme
        // brute — telle qu'elle circule sur le reseau. Lire ici la forme
        // decodee fait comparer deux chaines differentes des que le chemin
        // contient un echappement pourcent : la signature ne peut pas
        // correspondre, et le refus est un 403 qui parle de signature invalide,
        // jamais d'encodage.
        //
        // Mesure faite sur l'environnement de developpement :
        //   /admin/accounts/+22399999999/lockout-status     -> 200
        //   /admin/accounts/%2B22399999999/lockout-status   -> 403
        // alors que %2B est l'encodage CORRECT d'un + dans un chemin, et celui
        // que produit tout client HTTP correct — Postman compris. Tous les
        // endpoints prenant un numero de telephone en variable de chemin
        // etaient donc inatteignables depuis un client normal.
        //
        // interface-service, media-service et user-service lisent deja la forme
        // brute. auth-service, quotas-service et setting-service lisaient la
        // forme decodee.
        String path = request.getPath().value();
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

        if (isPublicEndpoint(cheminDeDecision)) {
            return chain.filter(exchange);
        }

        // Un secret vide refuse TOUT, au lieu de valider n'importe quoi.
        //
        // Avec un secret vide, `validateSignature` compare une signature
        // calculee avec une cle vide : elle est donc calculable par quiconque
        // connait l'algorithme, et le controle ne protege plus rien. Pire, si
        // les deux cotes etaient vides les signatures CONCORDERAIENT, et tout
        // paraitrait fonctionner.
        //
        // Ce cas ne peut pas etre normal : le Gateway lit la meme propriete
        // SANS valeur par defaut et ne demarre pas sans elle. Un secret vide
        // ici ne signifie donc qu'une mauvaise configuration de ce service.
        //
        // Le demarrage n'est pas bloque pour autant — c'est la politique du
        // projet — mais le service refuse de servir, et le dit en ERROR parce
        // que cela ne se reparera pas tout seul.
        if (gatewaySecretKey == null || gatewaySecretKey.isBlank()) {
            log.error(" Aucun secret de passerelle configure : toute requete est refusee");
            return forbidden(exchange, "Service mal configure");
        }

        // Le reseau source, dernier rempart.
        //
        // Ce controle etait absent de ce service. Il n'est pas redondant avec la
        // signature : le secret HMAC a ete committe (commit 71af837) et doit
        // etre considere comme connu. La signature seule ne distingue alors plus
        // le Gateway d'un appelant qui a lu le depot.
        //
        // La definition d'un reseau prive est deleguee a PrivateNetworks :
        // l'ecrire ici reproduirait le defaut trouve ailleurs, ou
        // startsWith("172.") acceptait 172.0.0.0/8 alors que la plage privee
        // s'arrete a 172.31.
        // Le PAIR TCP, et non l'en-tete X-Client-Ip.
        //
        // LE DEFAUT CORRIGE : `GatewayClientIp.of()` privilegie l'en-tete
        // `X-Client-Ip`, que le Gateway pose mais qu'un appelant direct peut
        // forger. `X-Client-Ip: 10.0.0.1` franchissait donc ce controle de
        // reseau prive.
        //
        // Cela comptait precisement parce que le secret HMAC figure dans
        // l'historique Git : ce controle est cense etre le DERNIER rempart.
        // Seul auth-service lisait le pair TCP.
        //
        // `of()` reste la bonne valeur pour l'AUDIT — qui a agi — mais jamais
        // pour une decision de securite.
        String adresse = GatewayClientIp.pairTcp(exchange);
        if (!("localhost".equals(adresse) || PrivateNetworks.contient(adresse))) {
            log.warn(" Acces direct bloque depuis {} sur {}", adresse, path);
            return forbidden(exchange, "Requete hors Gateway");
        }

        String timestamp = request.getHeaders().getFirst("X-Gateway-Timestamp");
        String signature = request.getHeaders().getFirst("X-Gateway-Signature");
        String requestId = request.getHeaders().getFirst("X-Gateway-Request-Id");

        if (timestamp == null || signature == null || requestId == null) {
            log.warn(" Accès direct bloqué (en-têtes Gateway absents) sur {}", path);
            return forbidden(exchange, "Requête hors Gateway : en-têtes de signature absents");
        }

        if (!hmacSignatureUtil.isTimestampValid(timestamp)) {
            log.warn(" Horodatage expiré ou invalide sur {}", path);
            return forbidden(exchange, "Horodatage de requête expiré");
        }

        String signatureData = hmacSignatureUtil.buildSignatureData(
                timestamp, path, request.getMethod().name(), requestId);

        if (!hmacSignatureUtil.validateSignature(gatewaySecretKey, signatureData, signature)) {
            log.warn(" Signature HMAC invalide sur {}", path);
            return forbidden(exchange, "Signature de requête invalide");
        }

        return chain.filter(exchange);
    }

    /**
     * Les chemins publics de ce service.
     *
     * <p>La regle vit desormais dans {@code CheminsPublics} de
     * platform-commons. Les cinq services en tenaient chacun une copie, et
     * <b>trois defauts de la meme famille</b> en sont sortis — un controle qui
     * designe plus large que son intention : {@code startsWith("/actuator")}
     * qui exemptait env, loggers et httptrace ; {@code contains("/api-docs")}
     * qui exemptait tout chemin comportant cette chaine ; et des entrees
     * traitees comme des expressions regulieres. Chacun est maintenant un cas
     * de test nomme dans {@code CheminsPublicsTest}.
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

    private boolean isPublicEndpoint(String path) {
        return CHEMINS_PUBLICS.contient(path);
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"status\":403,\"error\":\"Forbidden\",\"message\":\"%s\"}", reason);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
