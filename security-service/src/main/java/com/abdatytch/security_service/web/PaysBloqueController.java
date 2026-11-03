package com.abdatytch.security_service.web;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.dto.request.BlocageDePaysRequest;
import com.abdatytch.security_service.dto.response.PaysBloqueResponse;
import com.abdatytch.security_service.service.IPaysBloqueService;
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
 * Administration des pays refuses.
 *
 * <h2>D'ou vient cette surface, et pourquoi elle a demenage</h2>
 *
 * De {@code GeoAdminController}, qui vivait dans le <b>gateway</b>. La
 * passerelle achemine et applique ; decider quels pays sont refuses est une
 * politique. Un endroit qui fait les deux finit par porter un magasin de
 * decisions sans historique — ce qui etait le cas : un ensemble Redis portant
 * des codes pays, sans motif, sans auteur, sans date.
 *
 * <h2>Ce qui a change pour l'appelant</h2>
 *
 * <ul>
 *   <li>le chemin : {@code /api/v1/admin/geo/*} devient
 *       {@code /api/v1/security-admin/pays} ;</li>
 *   <li>le motif est <b>obligatoire</b> ;</li>
 *   <li>un code en minuscules est accepte. Il etait refuse, parce que la
 *       validation exigeait des majuscules et precedait la normalisation ;</li>
 *   <li>accepter un pays qui n'est pas refuse rend une erreur nommee, et non la
 *       meme reponse qu'une faute de frappe.</li>
 * </ul>
 *
 * <p>Les endpoints {@code check} et {@code info} de l'ancien controleur ne sont
 * pas reportes : ils consultaient MaxMind pour une adresse donnee, ce qui est
 * une fonction de diagnostic distincte de la politique. Elle reviendra avec la
 * surface de consultation d'adresse, dont l'historique de connexion a besoin.
 */
@RestController
@RequestMapping(SecurityConstants.ADMIN_BASE_PATH)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Pays refuses",
        description = "Decide quels pays sont refuses. L'application est faite par "
                + "GeoLocationWebFilter, dans le gateway, qui lit l'ensemble publie "
                + "dans Redis.")
public class PaysBloqueController {

    private final IPaysBloqueService paysBloques;
    private final IPublicationDeLaPolitique publication;

    @GetMapping(SecurityConstants.PAYS_ENDPOINT)
    @Operation(summary = "Pays actuellement refuses",
            description = """
                    Avec le motif, l'auteur et la date de chaque decision — ce que \
                    l'ensemble Redis remplace ne portait pas. Un pays refuse sans \
                    motif reste refuse indefiniment, parce que personne n'ose lever \
                    une decision dont il ignore la raison.""")
    @ApiResponse(responseCode = "200", description = "Liste par code ISO croissant")
    public Flux<PaysBloqueResponse> refuses() {
        return paysBloques.refuses();
    }

    @PostMapping(SecurityConstants.PAYS_ENDPOINT)
    @Operation(summary = "Refuse un ou plusieurs pays",
            description = """
                    Plusieurs codes en une demande, parce que l'usage reel est de \
                    refuser une liste et que traiter un code par appel multiplierait \
                    les republications.

                    La casse est libre : les codes sont normalises en majuscules \
                    **avant** validation. L'implementation remplacee validait \
                    d'abord, et refusait donc « ml » comme code invalide.

                    Un pays deja refuse voit son motif et son auteur mis a jour \
                    plutot que de produire une erreur.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pays refuses et publies"),
            @ApiResponse(responseCode = "400",
                    description = "Un code n'est pas un ISO 3166-1 alpha-2, ou le motif "
                            + "est absent")
    })
    public Flux<PaysBloqueResponse> refuser(@Valid @RequestBody BlocageDePaysRequest requete) {
        return AppelantAdministrateur.identifiant()
                .flatMapMany(auteur -> paysBloques.refuser(requete, auteur))
                .collectList()
                // La publication suit l'ecriture et son echec n'annule pas la
                // decision : la base est la source, et la republication
                // periodique ramene le cache.
                .flatMap(refuses -> publication.publierLesPaysBloques().thenReturn(refuses))
                .flatMapMany(Flux::fromIterable);
    }

    @DeleteMapping(SecurityConstants.PAYS_PAR_CODE_ENDPOINT)
    @Operation(summary = "Accepte de nouveau un pays",
            description = """
                    La ligne n'est pas supprimee : savoir qu'un pays a ete refuse \
                    puis accepte, par qui et quand, est ce qu'un journal d'audit \
                    doit montrer.

                    Accepter un pays qui n'est pas refuse rend une erreur nommee. \
                    L'implementation remplacee ne validait rien ici : une faute de \
                    frappe et un pays deja accepte rendaient la meme reponse.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pays accepte et publie"),
            @ApiResponse(responseCode = "400",
                    description = "Code invalide, ou ce pays n'est pas refuse")
    })
    public Mono<ResponseEntity<PaysBloqueResponse>> accepter(
            @Parameter(description = "Code ISO 3166-1 alpha-2, casse libre", example = "KP")
            @PathVariable String codeIso) {

        return AppelantAdministrateur.identifiant()
                .flatMap(auteur -> paysBloques.accepter(codeIso, auteur))
                .flatMap(accepte -> publication.publierLesPaysBloques().thenReturn(accepte))
                .map(ResponseEntity::ok);
    }
}
