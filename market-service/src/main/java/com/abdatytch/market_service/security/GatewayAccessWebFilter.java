package com.abdatytch.market_service.security;

import com.abdatytch.commons.security.CheminDeDecision;
import com.abdatytch.commons.security.CheminInterne;
import com.abdatytch.commons.security.CheminsPublics;
import com.abdatytch.commons.security.GatewayClientIp;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.commons.security.PrivateNetworks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Interdit l'acces direct au port du service : tout doit passer par la
 * passerelle.
 *
 * <h2>Pourquoi ce filtre, alors qu'il y a deja des jetons</h2>
 *
 * Parce qu'un jeton prouve QUI appelle, pas PAR OU. Sans ce filtre, qui atteint
 * le port 8086 parle au service sans traverser la limitation de debit, la liste
 * noire d'adresses, le geo-blocage ni la revocation de jetons — tous appliques
 * au Gateway, et nulle part ailleurs.
 *
 * <p>Meme contrat que auth-service, user-service, media-service,
 * setting-service, quotas-service et security-service, et c'est le point :
 * {@code HMACSignatureUtil} porte la regle une seule fois.
 * {@code timestamp|path|method|requestId}, fenetre anti-rejeu de 5 secondes.
 *
 * <h2>DEUX chemins, et les confondre casse tout</h2>
 *
 * <ul>
 *   <li>Le chemin <b>brut</b> ({@code getPath().value()}) pour la SIGNATURE :
 *       la passerelle signe la forme telle qu'elle circule. Lire la forme
 *       decodee ferait comparer deux chaines differentes des qu'un chemin
 *       contient un echappement pourcent, et le refus parlerait de signature
 *       invalide sans jamais mentionner l'encodage.</li>
 *   <li>Le chemin <b>de decision</b> ({@code CheminDeDecision.de}) pour la
 *       question « ce chemin est-il public » : les {@code pathMatchers} de
 *       Spring Security comparent des segments decodes, et une exemption qui ne
 *       voit pas la meme requete qu'eux n'exempte pas ce qu'elle croit.</li>
 * </ul>
 *
 * <p>Ce depot a paye les deux : des endpoints prenant un numero de telephone en
 * variable de chemin etaient inatteignables depuis tout client HTTP correct,
 * parce que {@code %2B} — l'encodage juste de {@code +} — faisait echouer la
 * signature.
 *
 * <h2>Le pair TCP, et non un en-tete</h2>
 *
 * Le controle de reseau lit {@code GatewayClientIp.pairTcp}, la seule valeur
 * qu'un appelant ne choisit pas. {@code GatewayClientIp.of()} privilegie
 * l'en-tete {@code X-Client-Ip}, que le Gateway pose mais qu'un appelant direct
 * forge : un {@code X-Client-Ip: 10.0.0.1} franchissait ce controle dans quatre
 * services avant correction.
 *
 * <p>Et ce controle n'est pas redondant avec la signature : le secret HMAC
 * figure dans l'historique Git du depot et doit etre considere comme connu. La
 * signature seule ne distingue donc plus la passerelle d'un appelant qui a lu le
 * depot — le reseau source est le dernier rempart.
 */
@Component
public class GatewayAccessWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAccessWebFilter.class);

    /**
     * Les chemins publics de ce service : sondes, page d'erreur, documentation.
     *
     * <p>La documentation exige la signature de passerelle mais pas de jeton :
     * elle decrit toute la surface d'API, donc elle ne doit pas etre lisible en
     * attaquant le port en direct. C'est la passerelle qui decide qui peut la
     * lire — une surface a proteger au lieu de sept.
     */
    private static final CheminsPublics CHEMINS_PUBLICS = CheminsPublics.sondes()
            .pageDErreur()
            .documentationViaPasserelle()
            .construire();


    private final HMACSignatureUtil hmac;

    @Value("${gateway.secret.key:}")
    private String secretDePasserelle;

    public GatewayAccessWebFilter(HMACSignatureUtil hmac) {
        this.hmac = hmac;
    }

    @jakarta.annotation.PostConstruct
    void annoncerLEtat() {
        if (secretDePasserelle == null || secretDePasserelle.isBlank()) {
            log.error("gateway.secret.key n'est pas configure : ce service "
                    + "refusera TOUTE requete metier. Renseigner GATEWAY_SECRET_KEY.");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest requete = exchange.getRequest();
        String cheminSigne = requete.getPath().value();
        String cheminDeDecision = CheminDeDecision.de(requete);

        if (CHEMINS_PUBLICS.contient(cheminDeDecision)) {
            return chain.filter(exchange);
        }

        // L'ORDRE qui suit est delibere : adresse privee D'ABORD, exemption
        // interne ENSUITE. Un chemin /internal reste soumis au controle
        // d'adresse ; seule la signature de passerelle est levee.
        String adresse = GatewayClientIp.pairTcp(exchange);
        if (!("localhost".equals(adresse) || PrivateNetworks.contient(adresse))) {
            log.warn("Acces direct bloque depuis {} sur {}", adresse, cheminSigne);
            return refuser(exchange, "Requete hors Gateway");
        }

        // Appel inter-services : la passerelle n'expose AUCUNE route vers
        // /internal/**, donc ce chemin est injoignable depuis l'exterieur et
        // l'appelant est un service du reseau prive.
        //
        // Exiger ici la signature de passerelle serait un blocage net, et
        // interface-service l'a deja paye : POST /internal/jwt/sign repondait
        // 403 « Utilisez le Gateway », auth-service n'obtenait aucun jeton et
        // plus aucune connexion n'aboutissait — le symptome visible etant un
        // 500 qui ne designait pas la cause.
        //
        // Ce qui protege ce chemin a la place : l'adresse privee verifiee
        // ci-dessus, et X-Internal-Secret compare a temps constant DANS le
        // controleur. Le secret est le controle qui porte — en conteneurs,
        // « reseau prive » englobe tous les voisins.
        //
        // L'exemption est posee avant le controle de `gateway.secret.key` a
        // dessein : ce chemin ne se sert pas de ce secret, donc une passerelle
        // mal configuree ne doit pas couper les appels inter-services.
        if (CheminInterne.correspond(cheminDeDecision)) {
            return chain.filter(exchange);
        }

        // Un secret vide REFUSE tout, au lieu de valider n'importe quoi.
        //
        // Avec un secret vide, la comparaison porterait sur une signature
        // calculable par quiconque connait l'algorithme. Pire : si les deux
        // cotes etaient vides, les signatures CONCORDERAIENT et tout paraitrait
        // fonctionner.
        //
        // Le demarrage n'est pas bloque — politique du projet — mais le service
        // refuse de servir et le dit en ERROR, parce que cela ne se reparera pas
        // tout seul.
        if (secretDePasserelle == null || secretDePasserelle.isBlank()) {
            log.error("Aucun secret de passerelle configure : requete refusee");
            return refuser(exchange, "Service mal configure");
        }

        String horodatage = requete.getHeaders().getFirst("X-Gateway-Timestamp");
        String signature = requete.getHeaders().getFirst("X-Gateway-Signature");
        String idRequete = requete.getHeaders().getFirst("X-Gateway-Request-Id");

        if (horodatage == null || signature == null || idRequete == null) {
            log.warn("Acces direct bloque (en-tetes de passerelle absents) sur {}",
                    cheminSigne);
            return refuser(exchange, "Requete hors Gateway : en-tetes absents");
        }

        if (!hmac.isTimestampValid(horodatage)) {
            log.warn("Horodatage expire ou invalide sur {}", cheminSigne);
            return refuser(exchange, "Horodatage de requete expire");
        }

        String donnees = hmac.buildSignatureData(
                horodatage, cheminSigne, requete.getMethod().name(), idRequete);

        if (!hmac.validateSignature(secretDePasserelle, donnees, signature)) {
            log.warn("Signature HMAC invalide sur {}", cheminSigne);
            return refuser(exchange, "Signature de requete invalide");
        }

        return chain.filter(exchange);
    }


    private Mono<Void> refuser(ServerWebExchange exchange, String motif) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String corps = String.format(
                "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"%s\"}", motif);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory()
                        .wrap(corps.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Avant la chaine de Spring Security, qui vit a {@code -100}.
     *
     * <p>{@code HIGHEST_PRECEDENCE + 10} : la provenance se verifie avant tout
     * ce qui lui fait confiance. Un {@code @Order(1)} serait execute APRES
     * Spring Security, donc apres la validation du jeton — et un appelant direct
     * au port aurait deja ete authentifie avant qu'on constate qu'il n'est pas
     * passe par la passerelle.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
