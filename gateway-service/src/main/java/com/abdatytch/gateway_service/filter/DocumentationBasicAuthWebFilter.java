package com.abdatytch.gateway_service.filter;

import com.abdatytch.commons.security.CheminDeDecision;
import com.abdatytch.commons.security.SecretComparisonUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * La documentation agrégée n'est lisible qu'après authentification.
 *
 * <h2>Pourquoi un filtre, et pas le modèle de rôles de la plateforme</h2>
 *
 * Un navigateur qui charge {@code /swagger-ui/index.html} <b>ne porte aucun
 * jeton JWT</b>. Protéger cette page par {@code hasRole("ADMIN")} supposerait
 * une page de connexion ou un cookie de session sur la passerelle, mécanisme
 * qui n'existe pas ici et dont la documentation ne justifie pas le coût.
 *
 * <p>L'authentification HTTP, elle, est demandée par le navigateur lui-même.
 * Identifiants dédiés, distincts de tout compte de la plateforme : compromettre
 * l'accès à la documentation ne donne aucun accès aux données.
 *
 * <h2>Ce qui est protégé, et pourquoi tout l'ensemble</h2>
 *
 * Protéger la page sans protéger les documents qu'elle charge ne protégerait
 * rien : {@code /api/v1/docs/media} se lit tout aussi bien à la main. Les quatre
 * surfaces sont donc gardées ensemble :
 *
 * <ul>
 *   <li>{@code /swagger-ui} — l'interface ;</li>
 *   <li>{@code /v3/api-docs} — le document de la passerelle elle-même ;</li>
 *   <li>{@code /webjars} — les ressources de l'interface ;</li>
 *   <li>{@code /api/v1/docs} — les documents <b>agrégés</b> des sept services.</li>
 * </ul>
 *
 * <h2>Sans identifiants configurés, la documentation est FERMÉE</h2>
 *
 * Et le service <b>démarre quand même</b>. C'est le compromis retenu dans ce
 * dépôt : jamais de refus de démarrage pour une variable absente, mais jamais
 * non plus d'ouverture par défaut. Une documentation accessible parce que
 * personne n'a configuré le mot de passe serait le pire des deux.
 *
 * <p>Dans ce cas le refus est un <b>404</b> et non un 401 : il n'y a aucun
 * identifiant à proposer, donc réclamer ceux d'un navigateur serait mentir sur
 * l'existence d'un accès, et ferait chercher un mot de passe qui n'existe pas.
 *
 * <h2>Ordre</h2>
 *
 * Avant {@code GatewaySignatureFilter} ({@code HIGHEST_PRECEDENCE + 10}) :
 * inutile de signer et de proxifier une requête qu'on va refuser.
 */
@Component
@Slf4j
public class DocumentationBasicAuthWebFilter implements WebFilter, Ordered {

    /**
     * Les préfixes gardés.
     *
     * <p>{@code startsWith} sur des préfixes ancrés, jamais {@code contains} :
     * ce dépôt a corrigé trois fois la même famille de défaut — un contrôle de
     * chemin qui désigne plus large que son intention. Ici l'erreur inverse
     * serait plus grave encore : un préfixe trop <b>étroit</b> laisserait une
     * partie de la documentation en accès libre.
     */
    private static final List<String> PREFIXES_GARDES = List.of(
            "/swagger-ui",
            "/v3/api-docs",
            "/webjars",
            "/api/v1/docs");

    private static final String PREFIXE_BASIC = "Basic ";
    private static final String DOMAINE = "Documentation Restart";

    @Value("${gateway.documentation.utilisateur:}")
    private String utilisateurAttendu;

    @Value("${gateway.documentation.mot-de-passe:}")
    private String motDePasseAttendu;

    /**
     * Dit une fois, au démarrage, que la documentation est fermée.
     *
     * <p>Sans ce message, l'exploitant conclurait à une panne de routage devant
     * un 404, et chercherait du côté des routes — qui sont correctes.
     */
    @PostConstruct
    void annoncerLEtat() {
        if (estConfigure()) {
            log.info("Documentation agregee protegee par authentification HTTP dediee");
        } else {
            log.warn("Documentation agregee FERMEE : ni gateway.documentation.utilisateur "
                    + "ni gateway.documentation.mot-de-passe ne sont configures. "
                    + "/swagger-ui, /v3/api-docs et /api/v1/docs rendront 404. "
                    + "Le service demarre normalement.");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Le chemin de DECISION, decode, et non le chemin brut.
        //
        // LE CONTOURNEMENT CORRIGE : `getPath().value()` rend le brut, alors que
        // les `pathMatchers` de SecurityConfig et le routeur comparent des
        // segments DECODES. `/%73wagger-ui/index.html` ne commencait donc pas
        // par `/swagger-ui` pour cette garde, mais correspondait bien au motif
        // `/swagger-ui/**` ensuite : la requete franchissait l'authentification
        // puis etait servie. La documentation de la plateforme se lisait sans
        // mot de passe, en encodant une seule lettre du chemin.
        //
        // La signature HMAC, elle, continue de porter sur le chemin BRUT : voir
        // la javadoc de CheminDeDecision, ces deux chemins ne se substituent
        // jamais l'un a l'autre.
        String chemin = CheminDeDecision.de(exchange);

        if (!estGarde(chemin)) {
            return chain.filter(exchange);
        }

        if (!estConfigure()) {
            // Fermee, et dite comme inexistante : voir le javadoc de classe.
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }

        String entete = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (entete == null || !entete.startsWith(PREFIXE_BASIC)) {
            return reclamerIdentifiants(exchange, chemin, "en-tete absent");
        }

        String[] identifiants = decoder(entete.substring(PREFIXE_BASIC.length()));
        if (identifiants == null) {
            return reclamerIdentifiants(exchange, chemin, "en-tete illisible");
        }

        // Les DEUX comparaisons sont a temps constant, et les deux sont
        // EVALUEES : un `&&` court-circuiterait, donc un nom d'utilisateur faux
        // rendrait la reponse mesurablement plus rapide qu'un mot de passe faux,
        // ce qui dirait a un attaquant lequel des deux il a trouve.
        boolean nomValide = SecretComparisonUtil.matches(utilisateurAttendu, identifiants[0]);
        boolean motDePasseValide = SecretComparisonUtil.matches(motDePasseAttendu, identifiants[1]);

        if (!(nomValide & motDePasseValide)) {
            return reclamerIdentifiants(exchange, chemin, "identifiants refuses");
        }

        return chain.filter(exchange);
    }

    private boolean estGarde(String chemin) {
        return PREFIXES_GARDES.stream().anyMatch(chemin::startsWith);
    }

    private boolean estConfigure() {
        return utilisateurAttendu != null && !utilisateurAttendu.isBlank()
                && motDePasseAttendu != null && !motDePasseAttendu.isBlank();
    }

    /**
     * Découpe {@code utilisateur:motDePasse}.
     *
     * <p>Sur le <b>premier</b> deux-points : un mot de passe peut en contenir,
     * un nom d'utilisateur non. Découper sur le dernier, ou sur tous,
     * rejetterait des mots de passe légitimes.
     */
    private String[] decoder(String encode) {
        try {
            String clair = new String(Base64.getDecoder().decode(encode.trim()),
                    StandardCharsets.UTF_8);
            int separateur = clair.indexOf(':');
            if (separateur < 0) {
                return null;
            }
            return new String[]{clair.substring(0, separateur), clair.substring(separateur + 1)};
        } catch (IllegalArgumentException base64Invalide) {
            return null;
        }
    }

    /**
     * Rend 401 avec {@code WWW-Authenticate}, sans dire ce qui a échoué.
     *
     * <p>L'en-tête est ce qui déclenche la fenêtre d'identification du
     * navigateur ; sans lui, la page reste blanche sans que personne comprenne
     * pourquoi.
     *
     * <p>La raison est <b>journalisée</b>, jamais renvoyée. Distinguer
     * « utilisateur inconnu » de « mot de passe faux » dans la réponse dirait à
     * un attaquant lequel des deux il a trouvé.
     */
    private Mono<Void> reclamerIdentifiants(ServerWebExchange exchange, String chemin,
                                            String raison) {
        log.warn("Acces a la documentation refuse sur {} : {}", chemin, raison);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add(HttpHeaders.WWW_AUTHENTICATE,
                "Basic realm=\"" + DOMAINE + "\", charset=\"UTF-8\"");
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        // Avant GatewaySignatureFilter (HIGHEST_PRECEDENCE + 10) : ne pas
        // signer ni proxifier une requete qu'on refuse.
        return Ordered.HIGHEST_PRECEDENCE + 8;
    }
}
