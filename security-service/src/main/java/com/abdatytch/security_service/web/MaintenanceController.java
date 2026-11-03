package com.abdatytch.security_service.web;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.dto.request.OuvertureDeMaintenanceRequest;
import com.abdatytch.security_service.dto.response.MaintenanceResponse;
import com.abdatytch.security_service.service.IMaintenanceService;
import com.abdatytch.security_service.service.IPublicationDeLaPolitique;
import com.abdatytch.security_service.utils.AppelantAdministrateur;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Administration des fenetres de maintenance.
 *
 * <h2>Le defaut que cette surface corrige</h2>
 *
 * {@code setting-service} declarait {@code system.maintenance-mode}, le
 * journalisait en {@code WARN} avec un message dedie — et <b>rien ne le
 * lisait</b>. Un administrateur pouvait « mettre la plateforme en maintenance »,
 * voir la confirmation dans l'interface et dans les journaux, et le trafic
 * continuait de passer.
 *
 * <p>Le service {@code IMaintenanceService} a ete ecrit pour y repondre, avec sa
 * table, son entite et sa publication vers Redis. Il n'etait appele par
 * <b>personne</b> : aucun controleur ne l'exposait, et son interface n'etait
 * referencee que par sa propre implementation. La protection etait complete sauf
 * sa porte d'entree — le motif de ce depot, un cran plus loin.
 *
 * <h2>Pourquoi un drapeau ne suffisait pas</h2>
 *
 * Une fenetre porte un <b>service</b>, un <b>message</b> et des <b>bornes</b>.
 * Le service permet de couper auth-service sans couper la plateforme ; le
 * message distingue, pour l'appelant, une coupure voulue d'une panne ; le debut
 * dans le futur permet d'annoncer avant d'interrompre. Un booleen
 * {@code maintenance-mode} ne porte aucun des trois.
 */
@RestController
@RequestMapping(SecurityConstants.ADMIN_BASE_PATH)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Fenetres de maintenance",
        description = "Decide quels services sont coupes, et ce que lit l'appelant pendant "
                + "la coupure. L'application est faite par MaintenanceWebFilter, dans le "
                + "gateway, qui lit le hachage publie dans Redis.")
public class MaintenanceController {

    private final IMaintenanceService maintenance;
    private final IPublicationDeLaPolitique publication;

    @GetMapping(SecurityConstants.MAINTENANCE_ENDPOINT)
    @Operation(summary = "Fenetres non levees",
            description = """
                    Les fenetres **a venir** y figurent, marquees `aVenir: \
                    true` : c'est ce qui permet de verifier qu'une coupure \
                    annoncee pour la nuit prochaine est bien enregistree.

                    L'etat `active` est **calcule** a la lecture, et non \
                    stocke : une fenetre devient active au passage de son heure \
                    de debut, sans que personne ecrive quoi que ce soit. Une \
                    colonne demanderait une tache qui la met a jour, donc un \
                    second mecanisme susceptible de divergence.""")
    @ApiResponse(responseCode = "200", description = "Fenetres non levees, a venir comprises")
    public Flux<MaintenanceResponse> fenetres() {
        return maintenance.fenetres();
    }

    @PostMapping(SecurityConstants.MAINTENANCE_ENDPOINT)
    @Operation(summary = "Ouvre une fenetre de maintenance",
            description = """
                    Le **service** est le nom Consul — `auth-service` — ou `*` \
                    pour toute la plateforme. Le nom Consul plutot qu'un nom \
                    libre, parce que c'est celui que le gateway utilise pour \
                    router : un nom libre obligerait a une table de \
                    correspondance, donc a un endroit de plus ou se tromper.

                    Le **message est obligatoire**. Une maintenance sans \
                    message rend un refus que personne ne sait interpreter : le \
                    client ne distingue pas une coupure voulue d'une panne, et \
                    appelle le support pour le dire.

                    Un **debut dans le futur annonce sans couper** : c'est ce \
                    qui permet de prevenir avant d'interrompre. Une **fin \
                    absente** signifie une coupure sans terme, signalee comme \
                    telle a l'appelant.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fenetre ouverte et publiee"),
            @ApiResponse(responseCode = "400",
                    description = "Service ou message absent, message contenant le "
                            + "caractere « | » qui sert de separateur au cache, ou fenetre "
                            + "dont la fin precede le debut ou est deja passee")
    })
    public Mono<ResponseEntity<MaintenanceResponse>> ouvrir(
            @Valid @RequestBody OuvertureDeMaintenanceRequest requete) {

        return AppelantAdministrateur.identifiant()
                .flatMap(auteur -> maintenance.ouvrir(requete, auteur))
                // La publication suit l'ecriture et son echec n'annule pas la
                // decision : la base est la source, et la republication
                // periodique ramene le cache.
                //
                // Sans cet appel, la coupure n'atteindrait Redis qu'a la
                // publication planifiee suivante. Couper un service est
                // precisement l'operation ou cinq minutes d'ecart comptent.
                .flatMap(ouverte -> publication.publierLaMaintenance().thenReturn(ouverte))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping(SecurityConstants.MAINTENANCE_PAR_ID_ENDPOINT)
    @Operation(summary = "Leve une fenetre avant son terme",
            description = """
                    La ligne n'est pas supprimee : savoir qu'un service a ete \
                    coupe, par qui et pour combien de temps est exactement ce \
                    qu'un journal d'audit doit montrer.

                    Lever une fenetre deja levee rend une erreur nommee plutot \
                    qu'un succes : un succes ferait croire qu'une coupure vient \
                    d'etre levee, donc qu'elle courait.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fenetre levee et cache mis a jour"),
            @ApiResponse(responseCode = "400", description = "Cette fenetre a deja ete levee"),
            @ApiResponse(responseCode = "404",
                    description = "Identifiant inconnu. 404 et non 403 : un 403 sur un "
                            + "identifiant existant permettrait d'enumerer les fenetres en "
                            + "lisant le seul code de statut")
    })
    public Mono<ResponseEntity<MaintenanceResponse>> lever(
            @Parameter(description = "Identifiant de la fenetre") @PathVariable String id) {

        return AppelantAdministrateur.identifiant()
                .flatMap(auteur -> maintenance.lever(id, auteur))
                // La republication PURGE le hachage avant de le reecrire : une
                // fenetre levee doit disparaitre. Sans cet appel, le gateway
                // continuerait de refuser les requetes du service pendant cinq
                // minutes alors que la levee aurait ete confirmee — une panne
                // causee par l'outil cense l'annoncer.
                .flatMap(levee -> publication.publierLaMaintenance().thenReturn(levee))
                .map(ResponseEntity::ok);
    }
}
