package com.abdatytch.user_service.security;

import com.abdatytch.commons.security.SecretComparisonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Autorise {@code /internal/**} sur présentation du secret inter-services.
 *
 * <h2>Pourquoi la décision est ICI, et non dans le contrôleur</h2>
 *
 * Elle y a été, le temps d'une correction, et c'était une erreur : la chaîne de
 * filtres déclarait {@code permitAll} et {@code InternalUserReactiveController}
 * vérifiait le secret lui-même.
 *
 * <p>Cela protégeait l'endpoint existant, et <b>seulement lui</b>. Le jour où un
 * second endpoint apparaît sous {@code /internal/**} sans reprendre la
 * vérification, il est ouvert — et rien ne le signale, puisque la chaîne a dit
 * oui. C'est le défaut dominant de ce dépôt dans sa forme la plus coûteuse : une
 * protection qui dépend de la mémoire de celui qui ajoutera le prochain
 * endpoint.
 *
 * <p>Ce n'est pas théorique. auth-service a vécu exactement cela : son
 * {@code /internal/**} était en {@code permitAll} et son contrôleur <b>ne lisait
 * aucun en-tête</b> — l'effacement RGPD d'un compte était joignable sans
 * authentification, alors que l'appelant envoyait bien un secret.
 *
 * <h2>Ce qu'elle remplace</h2>
 *
 * {@code ServiceTokenFilter} gardait les chemins commençant par
 * {@code /api/v1/internal/} — un préfixe que ce service ne sert pas, la
 * passerelle le retirant avant transmission — et exigeait un jeton JWT de
 * service que personne n'émet. Il ne se déclenchait jamais, et la règle
 * {@code hasRole("SERVICE")} qui l'accompagnait refusait donc tout.
 *
 * <p>Deux mécanismes, aucun qui fonctionne. Il n'y en a plus qu'un, posé sur le
 * chemin réellement servi, et qui vérifie le secret réellement envoyé.
 *
 * <h2>Secret non configuré : tout est refusé</h2>
 *
 * Un secret absent ou vide fait refuser chaque appel, et <b>n'empêche pas le
 * service de démarrer</b> — c'est une contrainte explicite du projet. Le choix
 * compte : la logique inverse, « pas de secret configuré donc pas de
 * vérification », transformerait une variable d'environnement oubliée en
 * ouverture complète de la surface inter-services.
 *
 * <p>Le refus est journalisé à chaque appel, pour qu'une configuration manquante
 * se voie dans les journaux au lieu de passer pour un service muet.
 */
@Component
@Slf4j
public class AutorisationInterne implements ReactiveAuthorizationManager<AuthorizationContext> {

    static final String EN_TETE_SECRET = "X-Internal-Secret";

    /**
     * Secret partagé entre services, distinct de la clef du Gateway.
     *
     * <p>La clef du Gateway authentifie le Gateway auprès des services ;
     * celle-ci authentifie un service auprès d'un autre. Et l'en-tête
     * {@code X-Secret-Key} ne conviendrait pas : le Gateway le <b>purge</b> des
     * requêtes entrantes, donc un appel passant par lui perdrait sa preuve.
     *
     * <p>Défaut vide, et non absent : sans lui, une variable d'environnement
     * manquante empêcherait le démarrage.
     */
    @Value("${security.internal-secret:}")
    private String internalSecret;

    /**
     * {@code authorize} et non {@code check}, et {@code AuthorizationResult} et
     * non {@code AuthorizationDecision}.
     *
     * <p>Spring Security 7 a retiré {@code check}, méthode abstraite jusqu'en
     * 6.x, et {@code authorize} y rend {@code Mono<AuthorizationResult>}.
     * {@code AuthorizationDecision} en est une implémentation, mais les
     * génériques Java sont invariants : {@code Mono<AuthorizationDecision>} ne
     * satisfait pas la signature. Vérifié dans le jar 7.0.2, pas supposé — deux
     * versions de Spring Security coexistent dans le dépôt local Maven, et la
     * 6.5.2 porte l'ancienne forme.
     */
    @Override
    public Mono<AuthorizationResult> authorize(Mono<Authentication> authentication,
                                               AuthorizationContext contexte) {
        // `authentication` n'est pas consultee : un appel de service a service
        // ne porte pas de jeton d'utilisateur, et c'est le secret qui fait foi.
        // Ne pas s'y abonner evite d'attendre une resolution inutile.
        if (internalSecret == null || internalSecret.isBlank()) {
            log.warn("Appel interne refuse : aucun secret inter-services configure "
                    + "(security.internal-secret). La surface /internal/** reste fermee.");
            return Mono.just(new AuthorizationDecision(false));
        }

        String recu = contexte.getExchange().getRequest()
                .getHeaders().getFirst(EN_TETE_SECRET);

        boolean accorde = SecretComparisonUtil.matches(internalSecret, recu);
        if (!accorde) {
            // Le chemin est journalise, pas le secret recu : un secret errone
            // dans les journaux est un secret divulgue.
            log.warn("Appel interne refuse sur {} : secret absent ou invalide",
                    contexte.getExchange().getRequest().getPath().value());
        }
        return Mono.just(new AuthorizationDecision(accorde));
    }
}
