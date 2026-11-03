package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.constants.WebConstants;
import com.abdatytch.auth_service.dto.response.UserResponse;
import com.abdatytch.auth_service.service.IInternalAccountService;
import com.abdatytch.commons.security.SecretComparisonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Appels internes des autres services de la plateforme.
 *
 * <h2>Ce que ce controleur garde, et ce qu'il a cede</h2>
 *
 * Il injectait {@code UserRepository} et {@code UserMapper} : il lisait la base
 * et traduisait les entites. Cela n'etait pas qu'une affaire de couche —
 * l'ancienne version de {@code DELETE} appelait
 * {@code userRepository.delete(user)}, <b>une suppression franche de la ligne</b>,
 * parce que le repository etait a portee de main. Avec un service interpose,
 * cette operation n'est meme plus exprimable ici.
 *
 * <p>Il garde le <b>controle du secret inter-services</b> : c'est de
 * l'authentification du transport, au meme titre qu'un filtre, et non de la
 * logique de compte.
 */
@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
@Slf4j
public class InternalAuthController {

    /**
     * Inscrit au registre de preuve comme demandeur.
     *
     * <p>La valeur doit designer l'<b>appelant interne</b>, et non la personne :
     * c'est user-service qui declenche, dans le cadre de la suppression d'un
     * compte. Le registre doit permettre de repondre a « qui a demande cet
     * effacement » des annees plus tard.
     */
    private static final String DEMANDEUR_INTERNE = "user-service (suppression de compte)";

    private static final String EN_TETE_SECRET = "X-Internal-Secret";

    private final IInternalAccountService internalAccountService;

    /**
     * Secret partage entre services.
     *
     * <h3>Le trou que cela ferme</h3>
     *
     * Ce controleur ne lisait <b>aucun</b> en-tete, et {@code SecurityConfig}
     * declare {@code /internal/**} en {@code permitAll}. Le {@code DELETE} est
     * l'effacement RGPD d'un utilisateur : il n'avait aucune authentification
     * propre. L'ecart etait dans auth-service seul — les endpoints
     * {@code /internal/**} d'interface-service verifient tous ce secret a temps
     * constant.
     *
     * <p>Et l'appelant faisait sa part : user-service envoyait bien un en-tete
     * de secret. C'est le destinataire qui ne le lisait pas, ce qui est la forme
     * la plus trompeuse du defaut — en lisant le client, on conclut que le
     * chemin est protege.
     *
     * <h3>Pourquoi ce nom d'en-tete, et pas celui deja envoye</h3>
     *
     * user-service envoyait {@code X-Secret-Key: ${gateway.secret.key}} — la
     * cle du <b>Gateway</b>, dans un en-tete que le Gateway lui-meme
     * <b>purge</b> des requetes entrantes. Deux raisons de ne pas batir dessus :
     * la cle du Gateway sert a authentifier le Gateway aupres des services, pas
     * un service aupres d'un autre ; et un en-tete purge disparaitrait des
     * qu'un appel passerait par le Gateway.
     */
    @Value("${security.internal-secret}")
    private String internalSecret;

    /**
     * Refus d'un appel interne non authentifie : <b>404</b>, jamais 403.
     *
     * <p>Regle du depot pour tout refus : un 403 confirmerait l'existence du
     * point d'entree. Sur un endpoint d'effacement, il confirmerait en plus
     * l'existence de l'identifiant demande.
     */
    private boolean secretValide(String recu) {
        return SecretComparisonUtil.matches(internalSecret, recu);
    }

    @GetMapping("/users/{userId}")
    public Mono<ResponseEntity<UserResponse>> getUserById(
            @RequestHeader(value = EN_TETE_SECRET, required = false) String secret,
            @PathVariable String userId) {
        if (!secretValide(secret)) {
            log.warn(WebConstants.LOG_WARN_INTERNAL_SECRET_REFUSE);
            return Mono.just(ResponseEntity.notFound().build());
        }
        return internalAccountService.lireCompte(userId).map(ResponseEntity::ok);
    }

    /**
     * Efface les donnees d'un utilisateur, a la demande de user-service.
     *
     * <p>La conformite de l'operation — registre de preuve, ecrasement plutot
     * que suppression, diffusion aux cinq autres detenteurs de donnees — est
     * portee par {@code IInternalAccountService}, qui documente les trois
     * defauts que cela corrige.
     */
    @DeleteMapping("/users/{userId}")
    public Mono<ResponseEntity<Void>> deleteUser(
            @RequestHeader(value = EN_TETE_SECRET, required = false) String secret,
            @PathVariable String userId) {
        if (!secretValide(secret)) {
            log.warn(WebConstants.LOG_WARN_INTERNAL_SECRET_REFUSE);
            return Mono.just(ResponseEntity.notFound().build());
        }
        return internalAccountService.effacerCompte(userId, DEMANDEUR_INTERNE)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }
}
