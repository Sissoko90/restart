package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.dto.response.EtatVerrouillageResponse;
import com.abdatytch.auth_service.dto.response.MessageResponse;
import com.abdatytch.auth_service.dto.response.OperationCompteResponse;
import com.abdatytch.auth_service.service.IAccountLockoutService;
import com.abdatytch.auth_service.service.IAdminAuthService;
import com.abdatytch.auth_service.util.AppelantAuthentifie;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;


/**
 * Administration des comptes : verrouillage, compteur d'echecs, second facteur.
 *
 * <h2>Ce que ce controleur ne fait plus</h2>
 *
 * Il assemblait ses reponses en {@code HashMap} au cas par cas, avec des cles
 * qui differaient d'une operation a l'autre et une forme qui changeait selon
 * l'etat du compte — {@code remainingAttempts} n'etait present que pour un
 * compte non verrouille. La documentation OpenAPI annonçait donc un objet libre
 * sur six endpoints d'administration.
 *
 * <p>Il portait aussi le branchement « si verrouille, sinon tentatives
 * restantes », qui provoquait <b>deux lectures de la base</b> pour une question
 * dont l'entite porte les deux reponses. Ce branchement est desormais dans
 * {@code IAccountLockoutService.etatDuCompte}, en une seule lecture.
 *
 * <h2>Autorisation</h2>
 *
 * {@code /admin/**} exige {@code ROLE_ADMIN} dans {@code SecurityConfig}, et
 * c'est cette regle de chemin qui fait foi. Les {@code @PreAuthorize} la
 * doublent et ne la remplacent pas : ce depot a deja trouve 33
 * {@code @PreAuthorize} inertes faute de {@code @EnableReactiveMethodSecurity},
 * qui est depuis en place. Le retrait du second facteur, lui, exige en plus
 * {@code SUPER_ADMIN}.
 */
@RestController
@RequestMapping("/admin/accounts")
@RequiredArgsConstructor
@Slf4j
public class AdminAccountController {
    
    private final IAccountLockoutService accountLockoutService;
    private final IAdminAuthService adminAuthService;
    
    /**
     * Etat de verrouillage d'un compte.
     *
     * <p>Une seule lecture de la base. Cette methode appelait
     * {@code isAccountLocked} puis, selon le resultat,
     * {@code getRemainingAttempts} — deux allers-retours pour une question dont
     * l'entite porte les deux reponses. Et elle omettait
     * {@code remainingAttempts} pour un compte verrouille, donc la reponse
     * changeait de forme selon l'etat.
     *
     * <p>{@code GET /admin/accounts/{identifier}/lockout-status}
     */
    @GetMapping("/{identifier}/lockout-status")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<EtatVerrouillageResponse>> getLockoutStatus(
            @PathVariable String identifier) {
        log.info(AppConstants.LOG_INFO_ADMIN_LOCKOUT_STATUS, identifier);

        return accountLockoutService.etatDuCompte(identifier).map(ResponseEntity::ok);
    }

    /**
     * Deverrouiller un compte manuellement.
     *
     * <p>{@code POST /admin/accounts/{identifier}/unlock}
     */
    @PostMapping("/{identifier}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<OperationCompteResponse>> unlockAccount(
            @PathVariable String identifier) {
        log.info(AppConstants.LOG_INFO_ADMIN_UNLOCK, identifier);

        return accountLockoutService.unlockAccount(identifier)
                .thenReturn(ResponseEntity.ok(OperationCompteResponse.de(
                        identifier, AppConstants.COMPTE_DEVERROUILLE_MESSAGE)));
    }

    /**
     * Remettre a zero le compteur de tentatives echouees.
     *
     * <p>{@code POST /admin/accounts/{identifier}/reset-attempts}
     */
    @PostMapping("/{identifier}/reset-attempts")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<OperationCompteResponse>> resetFailedAttempts(
            @PathVariable String identifier) {
        log.info(AppConstants.LOG_INFO_ADMIN_RESET_ATTEMPTS, identifier);

        return accountLockoutService.resetFailedAttempts(identifier)
                .thenReturn(ResponseEntity.ok(OperationCompteResponse.de(
                        identifier, AppConstants.COMPTEUR_REINITIALISE_MESSAGE)));
    }

    /**
     * Tentatives restantes avant verrouillage.
     *
     * <p>{@code GET /admin/accounts/{identifier}/remaining-attempts}
     */
    @GetMapping("/{identifier}/remaining-attempts")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<EtatVerrouillageResponse>> getRemainingAttempts(
            @PathVariable String identifier) {
        log.info(AppConstants.LOG_INFO_ADMIN_REMAINING_ATTEMPTS, identifier);

        // Meme reponse que lockout-status : l'etat complet vient d'une seule
        // lecture, donc rendre moins n'economiserait rien et obligerait
        // l'appelant a un second appel pour savoir si le compte est verrouille.
        return accountLockoutService.etatDuCompte(identifier).map(ResponseEntity::ok);
    }

    /**
     * Verrouiller manuellement un compte, pour la duree demandee.
     *
     * <p>{@code POST /admin/accounts/{identifier}/lock}
     */
    @PostMapping("/{identifier}/lock")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<OperationCompteResponse>> lockAccount(
            @PathVariable String identifier,
            @RequestParam(required = false, defaultValue = "30") int durationMinutes) {

        log.warn(AppConstants.LOG_WARN_ADMIN_LOCK, identifier, durationMinutes);

        // Verrouillage direct, avec la duree demandee.
        //
        // La version precedente appelait recordFailedAttempt cinq fois pour
        // « simuler » un verrouillage. Elle dependait donc de
        // auth.lockout.max-attempts valant exactement 5 — porte a 10, le compte
        // ne se verrouillait plus et la reponse annonçait quand meme un succes.
        // Et la duree demandee etait ignoree : le verrouillage prenait celle de
        // la configuration, tandis que la reponse rapportait celle demandee.
        return accountLockoutService.lockAccount(identifier, durationMinutes)
                .thenReturn(ResponseEntity.ok(OperationCompteResponse.verrou(
                        identifier, AppConstants.COMPTE_VERROUILLE_MESSAGE, durationMinutes)));
    }

    /**
     * Retirer le second facteur d'un administrateur qui a perdu son appareil.
     *
     * <h2>Pourquoi cet endpoint est ici et pas sous /auth/admin</h2>
     *
     * {@code /auth/admin/totp/reset} exige le code de l'appareil actuel, donc il
     * est ferme a qui l'a perdu — c'est son interet. Il faut une sortie, et elle
     * ne peut pas etre publique : ce serait le contournement du second facteur
     * lui-meme. Elle est donc <b>authentifiee</b> et reservee au
     * {@code SUPER_ADMIN}.
     *
     * <h2>Ce que le super administrateur peut, et ne peut pas</h2>
     *
     * Il <b>retire</b> le second facteur ; il ne le lit pas. La reponse ne
     * contient aucun secret, et un secret neuf est pose au passage pour que
     * l'ancien appareil — perdu, peut-etre vole — ne redevienne pas valide au
     * re-enrolement. L'administrateur re-enrole ensuite lui-meme via
     * {@code /auth/admin/totp/enroll}.
     *
     * <h2>404 et non 403</h2>
     *
     * Un identifiant qui ne designe aucun compte d'administration rend 404,
     * comme partout dans ce depot : un refus se presente comme une absence,
     * sinon la reponse permet d'enumerer les comptes administrateurs.
     */
    @PostMapping(AppConstants.ACCOUNT_TOTP_RESET_PATH)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Mono<ResponseEntity<MessageResponse>> retirerSecondFacteur(
            @PathVariable String identifier) {
        log.warn(AppConstants.LOG_WARN_ADMIN_TOTP_RETRAIT_DEMANDE);

        return AppelantAuthentifie.identifiant()
                .flatMap(acteurId -> adminAuthService.retirerSecondFacteur(identifier, acteurId))
                // L'indicateur est porte par le Mono TYPE, et non deduit de la
                // vacuite du Mono<Void> rendu par le service : un Mono<Void>
                // est toujours vide, donc une branche de repli posee dessus
                // s'executerait TOUJOURS — y compris apres un retrait reussi,
                // qui rendrait alors 404. Ce depot a deja corrige cette erreur
                // plusieurs fois.
                .thenReturn(true)
                .defaultIfEmpty(false)
                .map(retire -> Boolean.TRUE.equals(retire)
                        ? ResponseEntity.ok(MessageResponse.de(AppConstants.TOTP_MESSAGE_RETIRE))
                        : ResponseEntity.status(HttpStatus.NOT_FOUND).<MessageResponse>build());
    }

}
