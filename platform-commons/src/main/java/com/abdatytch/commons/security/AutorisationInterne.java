package com.abdatytch.commons.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import reactor.core.publisher.Mono;

/**
 * Autorisation des appels de service a service, en un seul detenteur.
 *
 * <h2>Pourquoi cette classe vit ici</h2>
 *
 * Elle vivait dans user-service. Le jour ou notification-service a eu besoin de
 * la meme regle, le depot comptait <b>trois reponses differentes</b> a une seule
 * question — qui peut appeler {@code /internal/**} :
 *
 * <ul>
 *   <li>auth-service et interface-service : {@code permitAll} au niveau
 *       securite, et un controle du secret recrit <b>dans chaque
 *       controleur</b> ;</li>
 *   <li>user-service : cette classe, au niveau securite ;</li>
 *   <li>notification-service : {@code hasAnyRole("SERVICE", "ADMIN")}.</li>
 * </ul>
 *
 * <p>La troisieme etait simplement fausse. Releve fait sur tout le depot :
 * {@code JWT_CLAIM_SERVICE_ROLE}, {@code JWT_SERVICE_SUBJECT} et
 * {@code JWT_TYPE_SERVICE} sont declares dans les constantes d'auth-service et
 * <b>utilises nulle part</b> — aucun code n'emet de jeton de plateforme portant
 * le role {@code SERVICE}. La regle exigeait donc un jeton que personne ne
 * delivre, et aurait refuse tout appel inter-services.
 *
 * <h2>Pourquoi au niveau securite, et non dans les controleurs</h2>
 *
 * C'est la difference qui compte entre les deux approches survivantes. Un
 * controle recrit par controleur est un controle qu'un endpoint ajoute plus
 * tard peut <b>oublier</b> : rien ne le signale, et le nouvel endpoint est
 * ouvert. Pose ici, la regle couvre {@code /internal/**} en entier, y compris
 * ce que personne n'a encore ecrit.
 *
 * <h2>Pourquoi une classe ordinaire et non un {@code @Component}</h2>
 *
 * platform-commons ne contient aucun composant Spring, et aucun service ne
 * scanne {@code com.abdatytch.commons}. Un {@code @Component} ici ne serait
 * instancie nulle part — present, et inerte. Chaque module declare donc son
 * {@code @Bean}, comme pour {@code DisjoncteurSortant}.
 *
 * <h2>Ferme a la requete, pas au demarrage</h2>
 *
 * Sans secret configure, cette classe refuse chaque appel et le dit. Elle ne
 * bloque pas le demarrage : c'est la politique du projet, un service doit
 * pouvoir demarrer et signaler ce qui lui manque.
 */
public class AutorisationInterne implements ReactiveAuthorizationManager<AuthorizationContext> {

    public static final String EN_TETE_SECRET = "X-Internal-Secret";

    private static final Logger log = LoggerFactory.getLogger(AutorisationInterne.class);

    private final String secretInterne;

    /**
     * @param secretInterne le secret partage entre les services. Vide ou
     *                      {@code null}, <b>tout</b> appel interne est refuse —
     *                      et non l'inverse : comparer deux valeurs vides les
     *                      ferait concorder, et la surface serait grande ouverte
     *                      precisement la ou personne n'a renseigne la variable
     */
    public AutorisationInterne(String secretInterne) {
        this.secretInterne = secretInterne;
    }

    @Override
    public Mono<AuthorizationResult> authorize(Mono<Authentication> authentication,
                                               AuthorizationContext contexte) {
        // `authentication` n'est pas consultee : un appel de service a service
        // ne porte pas de jeton d'utilisateur, et c'est le secret qui fait foi.
        // Ne pas s'y abonner evite d'attendre une resolution inutile.
        if (secretInterne == null || secretInterne.isBlank()) {
            log.warn("Appel interne refuse : aucun secret inter-services configure "
                    + "(security.internal-secret). La surface /internal/** reste fermee.");
            return Mono.just(new AuthorizationDecision(false));
        }

        String recu = contexte.getExchange().getRequest()
                .getHeaders().getFirst(EN_TETE_SECRET);

        boolean accorde = SecretComparisonUtil.matches(secretInterne, recu);

        if (!accorde) {
            // Le chemin est journalise, pas le secret recu : un secret errone
            // dans les journaux est un secret divulgue.
            log.warn("Appel interne refuse sur {} : secret absent ou invalide",
                    contexte.getExchange().getRequest().getPath().value());
        }

        return Mono.just(new AuthorizationDecision(accorde));
    }
}
