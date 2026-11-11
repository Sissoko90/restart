package com.abdatytch.interface_service.erasure;

import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.repository.ProjectRepository;
import com.abdatytch.interface_service.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**

 * Efface les données d'un développeur du portail.
 *
 * <h2>Deux populations distinctes</h2>
 *
 * interface-service tient son <b>propre</b> registre d'utilisateurs : les
 * développeurs inscrits au portail. Ils n'ont rien à voir avec les utilisateurs
 * finaux des applications, dont l'identité vit dans auth-service. Deux tables
 * {@code users}, deux espaces d'identifiants, deux populations.
 *
 * <p>Cette distinction a une conséquence qu'il vaut mieux connaître : le
 * dispositif d'effacement initial ne couvrait que les utilisateurs finaux. Les
 * développeurs n'avaient <b>aucun</b> moyen d'exercer leur droit — ni endpoint,
 * ni suppression, ni même une suppression logique. Leur adresse, leur empreinte
 * de mot de passe et leur secret TOTP restaient indéfiniment.
 *
 * <h2>Ce qui est effacé</h2>
 *
 * L'identité et tous les secrets rattachés au compte : empreinte du mot de
 * passe, jetons de rafraîchissement, codes à usage unique en cours, jetons de
 * réinitialisation, <b>secret TOTP</b> et secret de récupération. Un secret TOTP
 * laissé en base permettrait de générer indéfiniment des codes valides pour un
 * compte censé ne plus exister.
 *
 * <p>Les projets sont supprimés : ils portent un nom et une description saisis
 * librement, et n'ont plus de titulaire.
 */
@Service
public class DeveloperErasureService {

    private static final Logger log = LoggerFactory.getLogger(DeveloperErasureService.class);

    private static final String EFFACE = "[effacé]";

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    public DeveloperErasureService(UserRepository userRepository,
                                   ProjectRepository projectRepository) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
    }

    /**

     * @param subjectId identifiant du développeur
     * @return le nombre d'enregistrements effacés ; zéro si l'identifiant ne
     * désigne pas un développeur — cas normal lorsqu'il s'agit d'un
     * utilisateur final
     */
    public Mono<Integer> erase(String subjectId) {
        UUID developerId;
        try {
            developerId = UUID.fromString(subjectId);
        } catch (IllegalArgumentException e) {
            // Les identifiants d'utilisateurs finaux n'ont pas nécessairement
            // cette forme : ce n'est pas une erreur, simplement une demande qui
            // ne concerne pas ce service.
            log.debug("Identifiant {} hors du format attendu — rien à effacer ici", subjectId);
            return Mono.just(0);
        }

        return supprimerProjets(developerId)
                .flatMap(projets -> effacerIdentite(developerId).map(compte -> projets + compte))
                .doOnSuccess(n -> log.info("Effacement développeur {} — {} enregistrement(s)",
                        subjectId, n));
    }

    private Mono<Integer> supprimerProjets(UUID developerId) {
        return projectRepository.findByUserId(developerId)
                .collectList()
                .flatMap(projets -> Flux.fromIterable(projets)
                        .flatMap(projectRepository::delete)
                        .then(Mono.just(projets.size())));
    }

    private Mono<Integer> effacerIdentite(UUID developerId) {
        return userRepository.findById(developerId)
                .flatMap(developer -> {
                    effacer(developer);
                    return userRepository.save(developer);
                })
                .map(saved -> 1)
                .defaultIfEmpty(0);
    }

    private void effacer(User developer) {
        developer.setEmail(null);
        developer.setPhoneNumber(null);
        developer.setFirstName(EFFACE);
        developer.setLastName(EFFACE);
        // Le nom d'utilisateur est unique en base : on ne peut pas le mettre à
        // null sans risquer un conflit sur un second effacement. Une valeur
        // dérivée de l'identifiant reste unique et n'identifie personne.
        developer.setUsername("efface-" + developer.getId());

        developer.setPasswordHash(EFFACE);
        developer.setRecoverySecretHash(null);

        // Un secret TOTP conservé permettrait de générer indéfiniment des codes
        // valides pour un compte censé ne plus exister.
        developer.setTotpSecret(null);
        developer.setTotpEnabled(false);

        // Seule l'empreinte reste a effacer : la colonne `refresh_token`, qui
        // portait la valeur en clair, a ete supprimee par la migration V9. Deux
        // colonnes coexistaient pour le meme etat, et c'est la dangereuse que le
        // code employait.
        developer.setRefreshTokenHash(null);
        developer.setRefreshTokenExpiresAt(null);

        developer.setEmailVerificationToken(null);
        developer.setEmailOtpCode(null);
        developer.setPhoneOtpCode(null);
        developer.setPasswordResetToken(null);
        developer.setPasswordResetOtpCode(null);

        developer.setEmailVerified(false);
        developer.setPhoneVerified(false);
        developer.setMarketingConsent(false);
        developer.setLastLoginAt(null);
        developer.setIsActive(false);
    }
}
