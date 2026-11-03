package com.abdatytch.security_service.web;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.dto.request.ChangementDeParametreRequest;
import com.abdatytch.security_service.dto.response.ParametreResponse;
import com.abdatytch.security_service.service.IPolitiqueDeSecuriteService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Administration de la politique de securite.
 *
 * <h2>Aucune logique ici</h2>
 *
 * Chaque methode resout l'appelant, delegue, publie, et traduit en reponse HTTP.
 * La validation des types et des bornes, le choix entre insertion et mise a
 * jour, la suppression plutot que l'ecriture du defaut : tout cela appartient au
 * service, ou un seul detenteur le porte.
 *
 * <h2>Pourquoi la publication est faite ici et non dans le service</h2>
 *
 * Le service decide ; la publication rend la decision applicable. Les lier dans
 * le service ferait d'une indisponibilite de Redis un echec d'ecriture en base —
 * alors que la base est la source et doit aboutir seule. Le controleur enchaine
 * les deux et avale l'echec de publication, que la republication periodique
 * rattrape.
 */
@RestController
@RequestMapping(SecurityConstants.ADMIN_BASE_PATH)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Politique de securite",
        description = "Parametres typees de la plateforme. Publies dans Redis, "
                + "appliques par le gateway et auth-service.")
public class PolitiqueDeSecuriteController {

    private final IPolitiqueDeSecuriteService politique;
    private final IPublicationDeLaPolitique publication;

    @GetMapping(SecurityConstants.POLITIQUE_ENDPOINT)
    @Operation(summary = "Politique complete",
            description = """
                    Rend TOUS les parametres du catalogue, pas seulement ceux qui \
                    ont ete modifies. Le champ `surcharge` distingue une decision \
                    d'un defaut : sans lui, « seuil : 5 » ne dit pas si quelqu'un \
                    l'a choisi.

                    Les bornes sont rendues pour que l'interface puisse refuser une \
                    valeur avant l'appel, et surtout **expliquer** la borne.""")
    @ApiResponse(responseCode = "200", description = "Politique, defauts compris")
    public Flux<ParametreResponse> politique() {
        return politique.politiqueDetaillee();
    }

    @GetMapping(SecurityConstants.POLITIQUE_PAR_CLE_ENDPOINT)
    @Operation(summary = "Un parametre")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Parametre et son etat"),
            @ApiResponse(responseCode = "400",
                    description = "Cle absente du catalogue. Une cle inconnue est refusee "
                            + "plutot que creee : c'est ce qui evite les parametres "
                            + "fantomes que rien ne lit.")
    })
    public Mono<ResponseEntity<ParametreResponse>> parametre(
            @Parameter(description = "Cle du catalogue",
                    example = "security.max-login-attempts")
            @PathVariable String cle) {
        return politique.parametre(cle).map(ResponseEntity::ok);
    }

    @PutMapping(SecurityConstants.POLITIQUE_PAR_CLE_ENDPOINT)
    @Operation(summary = "Change un parametre",
            description = """
                    La valeur est validee contre le **type** et les **bornes** du \
                    catalogue, a l'ecriture. Un seuil de tentatives a zero \
                    verrouillerait tout le monde a la premiere requete, et c'est un \
                    entier valide — d'ou les bornes.

                    L'auteur et la date sont conserves : une revue de securite doit \
                    pouvoir discuter une decision, pas seulement constater un etat.

                    La politique est republiee dans Redis immediatement apres.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Parametre modifie et publie"),
            @ApiResponse(responseCode = "400",
                    description = "Cle inconnue, type incorrect, ou valeur hors bornes. "
                            + "Le message nomme la borne franchie.")
    })
    public Mono<ResponseEntity<ParametreResponse>> changer(
            @PathVariable String cle,
            @Valid @RequestBody ChangementDeParametreRequest requete) {

        return AppelantAdministrateur.identifiant()
                .flatMap(auteur -> politique.changer(cle, requete.getValeur(), auteur))
                // La publication suit l'ecriture, et son echec n'annule pas la
                // decision : voir la javadoc de la classe.
                .flatMap(vue -> publication.publierLaPolitique().thenReturn(vue))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping(SecurityConstants.POLITIQUE_PAR_CLE_ENDPOINT)
    @Operation(summary = "Remet un parametre a son defaut",
            description = """
                    **Supprime** la surcharge au lieu d'y ecrire le defaut. Ecrire le \
                    defaut figerait la valeur : un changement de defaut dans une \
                    version ulterieure ne s'appliquerait plus jamais a cette \
                    installation, qui se croirait pourtant remise a zero.""")
    @ApiResponse(responseCode = "200", description = "Parametre revenu a son defaut")
    public Mono<ResponseEntity<ParametreResponse>> remettreAuDefaut(@PathVariable String cle) {
        return AppelantAdministrateur.identifiant()
                .flatMap(auteur -> politique.remettreAuDefaut(cle, auteur))
                .flatMap(vue -> publication.publierLaPolitique().thenReturn(vue))
                .map(ResponseEntity::ok);
    }
}
