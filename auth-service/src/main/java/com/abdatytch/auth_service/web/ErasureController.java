package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.dto.response.DemandeEffacementResponse;
import com.abdatytch.auth_service.dto.response.EtatEffacementResponse;
import com.abdatytch.auth_service.service.IAccountErasureService;
import com.abdatytch.auth_service.util.AppelantAuthentifie;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent.ErasureReason;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;


/**

 * Exercice du droit à l'effacement (RGPD art. 17).
 *
 * <p><b>Pourquoi un endpoint réservé à la personne elle-même.</b> Jusqu'ici, la
 * seule suppression de compte était réservée aux administrateurs, et elle se
 * contentait de marquer la ligne comme supprimée. Une personne ne pouvait donc
 * ni exercer son droit, ni obtenir un véritable effacement.
 *
 * <p>L'opération est <b>irréversible</b> et ne demande pas de confirmation
 * côté serveur : c'est à l'interface appelante de la recueillir. Ajouter une
 * confirmation par jeton ici donnerait une fausse impression de sécurité sans
 * empêcher un appel direct.
 */
@RestController
@RequestMapping("/auth/erasure")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Effacement", description = "Droit à l'effacement des données personnelles")
public class ErasureController {

    private final IAccountErasureService erasureService;

    /**

     * Demande l'effacement de ses propres données.
     *
     * <p>Retourne 202 et non 200 : l'effacement local est fait, mais celui des
     * autres services est en cours. Répondre 200 laisserait croire que tout est
     * terminé, ce qui serait faux — et l'identifiant de demande retourné n'aurait
     * aucune raison d'être.
     */
    @PostMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Demander l'effacement de ses données personnelles",
            description = "Irréversible. Efface les données d'identité et déclenche "
                    + "l'effacement dans les autres services.")
    public Mono<ResponseEntity<DemandeEffacementResponse>> erasePersonalData() {
        return AppelantAuthentifie.identifiant()
                .flatMap(subjectId -> {
                    log.info(" Demande d'effacement par la personne elle-même : {}", subjectId);
                    return erasureService.requestErasure(
                            subjectId, subjectId, ErasureReason.DEMANDE_DE_LA_PERSONNE);
                })
                .map(requestId -> ResponseEntity.accepted().body(
                        DemandeEffacementResponse.de(requestId.toString(),
                                AppConstants.EFFACEMENT_ENGAGE_MESSAGE)));
    }

    /**

     * Effacement déclenché par un administrateur.
     *
     * <p>Distingué du précédent parce que le motif diffère, et que le registre
     * doit conserver qui a déclenché : un effacement administratif engage
     * l'organisation autrement qu'une demande de la personne.
     */
    @PostMapping("/{subjectId}")
    // SUPER_ADMIN et non ADMIN : effacer les donnees d'autrui est irreversible et
    // engage l'organisation. Un ADMIN gere les roles et les comptes ; il n'a pas
    // a pouvoir detruire les donnees d'une personne.
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Effacer les données d'une personne (administrateur)")
    public Mono<ResponseEntity<DemandeEffacementResponse>> eraseFor(
            @PathVariable String subjectId) {
        return AppelantAuthentifie.identifiant()
                .flatMap(adminId -> {
                    log.warn(" Effacement administratif de {} demandé par {}", subjectId, adminId);
                    return erasureService.requestErasure(
                            subjectId, adminId, ErasureReason.DECISION_ADMINISTRATIVE);
                })
                .map(requestId -> ResponseEntity.accepted().body(
                        DemandeEffacementResponse.de(requestId.toString(),
                                AppConstants.EFFACEMENT_ENGAGE_MESSAGE)));
    }

    /**

     * État d'avancement d'une demande — la preuve d'exécution.
     *
     * <p>Réservé aux administrateurs : la personne concernée n'a, par
     * construction, plus de compte pour consulter quoi que ce soit une fois
     * l'effacement fait. L'identifiant de demande lui est remis pour qu'elle
     * puisse le produire en cas de réclamation.
     */
    @GetMapping("/{requestId}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Vérifier l'exécution d'un effacement")
    public Mono<ResponseEntity<EtatEffacementResponse>> status(@PathVariable String requestId) {
        return erasureService.etatDeLaDemande(requestId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
