package com.abdatytch.media_service.security;

import com.abdatytch.commons.security.CheminDeDecision;
import com.abdatytch.commons.security.GatewayClientIp;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.commons.security.PrivateNetworks;
import com.abdatytch.media_service.constants.SecurityConstants;
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


/**
 * N'accepte que les requêtes effectivement passées par la passerelle.
 *
 * <h2>Le défaut corrigé : ce filtre refusait TOUT</h2>
 *
 * Il exigeait l'en-tête {@code X-Secret-Key} et refusait en 403 dès qu'il
 * manquait. Or la passerelle <b>ne l'émet plus</b> — le javadoc de
 * {@code GatewaySignatureFilter} le dit explicitement — et elle le
 * <b>purge</b> des requêtes entrantes, puisqu'il figure dans sa liste
 * {@code TRUSTED_IDENTITY_HEADERS}. Aucune requête légitime ne pouvait donc le
 * porter, et media-service rendait 403 sur {@code /media/**},
 * {@code /admin/documents/**} et {@code /admin/document-types/**}.
 *
 * <p>Le retrait de cet en-tête côté passerelle était volontaire et juste : il
 * <b>transportait le secret partagé</b> sur le réseau à chaque requête. Une
 * signature HMAC prouve la même chose sans jamais transmettre le secret. Ce
 * filtre était simplement resté sur l'ancien contrat, et c'est le seul des cinq
 * services dans ce cas.
 *
 * <h2>Ce qu'il vérifie maintenant, et dans cet ordre</h2>
 *
 * <ol>
 *   <li><b>le réseau source</b> — un appel direct au port, hors réseau privé,
 *       est refusé avant tout calcul ;</li>
 *   <li><b>la présence</b> des trois en-têtes de signature ;</li>
 *   <li><b>l'horodatage</b>, contre le rejeu d'une requête interceptée ;</li>
 *   <li><b>la signature</b>, sur le chemin reçu, la méthode et l'identifiant de
 *       requête.</li>
 * </ol>
 *
 * <p>Le contrôle d'adresse n'est pas redondant avec la signature : le secret
 * HMAC a été committé (commit {@code 71af837}) et doit être considéré comme
 * connu. Il est donc le <b>dernier rempart</b> contre un appel direct, et c'est
 * pourquoi sa définition est déléguée à {@code PrivateNetworks} — l'implémenter
 * ici reproduirait l'erreur trouvée ailleurs, où {@code startsWith("172.")}
 * acceptait 172.0.0.0/8 alors que la plage privée s'arrête à 172.31.
 *
 * <h2>Le chemin signé est celui que le service reçoit</h2>
 *
 * {@code GatewaySignatureFilter} signe le chemin <b>transformé</b>, calculé par
 * {@code CheminCible} — soit le chemin sans le préfixe public {@code /api/v1}.
 * C'est donc bien {@code getPath().value()} qu'il faut resigner ici. Signer le
 * chemin d'origine ferait échouer toutes les vérifications, avec un 403 parlant
 * de signature invalide et non de chemin.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class GatewayAccessWebFilter implements WebFilter {

    private final HMACSignatureUtil hmacSignatureUtil;

    @Value("${security.gateway.secret-key}")
    private String gatewaySecretKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String chemin = exchange.getRequest().getPath().value();
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
        String cheminDeDecision = CheminDeDecision.de(exchange);

        if (estPublic(cheminDeDecision)) {
            return chain.filter(exchange);
        }

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
        if (!estReseauAutorise(adresse)) {
            return refuser(exchange, adresse, chemin, "reseau source non autorise");
        }

        var enTetes = exchange.getRequest().getHeaders();
        String horodatage = enTetes.getFirst(SecurityConstants.HEADER_X_GATEWAY_TIMESTAMP);
        String signature = enTetes.getFirst(SecurityConstants.HEADER_X_GATEWAY_SIGNATURE);
        String identifiant = enTetes.getFirst(SecurityConstants.HEADER_X_GATEWAY_REQUEST_ID);

        if (horodatage == null || signature == null || identifiant == null) {
            return refuser(exchange, adresse, chemin, "en-tetes de signature absents");
        }

        if (!hmacSignatureUtil.isTimestampValid(horodatage)) {
            return refuser(exchange, adresse, chemin, "horodatage expire");
        }

        String donneesSignees = hmacSignatureUtil.buildSignatureData(
                horodatage,
                chemin,
                exchange.getRequest().getMethod().name(),
                identifiant);

        if (!hmacSignatureUtil.validateSignature(gatewaySecretKey, donneesSignees, signature)) {
            return refuser(exchange, adresse, chemin, "signature invalide");
        }

        log.debug("Acces passerelle valide : {} {} (requete {})",
                exchange.getRequest().getMethod(), chemin, identifiant);
        return chain.filter(exchange);
    }

    /**
     * Refus : un seul WARN, et rien dans la réponse.
     *
     * <h3>Pourquoi WARN et non ERROR</h3>
     *
     * La version précédente écrivait <b>trois lignes ERROR par requête
     * refusée</b>, dont une annonçant le port à utiliser. Deux problèmes.
     *
     * <p>Un appel direct au port est une situation <b>normale</b> — un scanner,
     * une sonde mal configurée — et ce service traite la chose correctement. Or
     * un ERROR doit désigner ce qui ne se réparera pas tout seul ; sinon on
     * cesse de lire le journal. C'est la leçon déjà tirée de
     * {@code JwksKeyProvider}, qui journalisait en ERROR une absence que la
     * classe traite comme normale.
     *
     * <p>Et le volume est <b>choisi par l'appelant</b> : trois lignes par
     * requête refusée donnent à qui martèle le port un moyen de remplir le
     * disque. Ce service écrit ses journaux sur le même système de fichiers que
     * les pièces justificatives qu'il stocke.
     *
     * <h3>Pourquoi la réponse ne dit rien</h3>
     *
     * L'ancienne posait {@code X-Error-Reason: DIRECT ACCESS FORBIDDEN - USE
     * GATEWAY} et journalisait le port de la passerelle. Cela explique le
     * mécanisme de défense à qui le sonde. Le motif reste dans le journal, où il
     * sert au diagnostic ; il ne part pas dans la réponse.
     */
    private Mono<Void> refuser(ServerWebExchange exchange, String adresse,
                               String chemin, String motif) {
        log.warn("Acces refuse ({}) : {} depuis {}", motif, chemin, adresse);
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }

    /**
     * Les réseaux d'où la passerelle peut légitimement appeler.
     *
     * <p>{@code localhost} est accepté nommément : la résolution ne rend pas
     * toujours une forme numérique, et un développement local passerait sinon
     * par un refus incompréhensible.
     */
    private boolean estReseauAutorise(String adresse) {
        return "localhost".equals(adresse) || PrivateNetworks.contient(adresse);
    }

    /**
     * Les chemins exemptés, sondes comprises.
     *
     * <p>Les trois sondes sont nommées, et <b>non</b> {@code /actuator/} en
     * bloc : la version d'origine exemptait tout le sous-arbre, donc
     * {@code env}, {@code httptrace} et {@code loggers}. Consul et Prometheus,
     * qui ne portent ni jeton ni signature, n'ont besoin que de ces trois
     * chemins.
     */
    private boolean estPublic(String chemin) {
        return SecurityConstants.estCheminPublic(chemin);
    }
}
