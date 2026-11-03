package com.abdatytch.security_service.web;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.dto.request.PoseDeRegleIpRequest;
import com.abdatytch.security_service.dto.response.RegleDIpResponse;
import com.abdatytch.security_service.service.IPublicationDeLaPolitique;
import com.abdatytch.security_service.service.IRegleDIpService;
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
 * Administration des regles de blocage d'adresses.
 *
 * <h2>Ce que remplace cette surface</h2>
 *
 * Rien, et c'est le probleme qu'elle corrige. {@code IpBlacklistWebFilter}
 * ecrivait dans Redis depuis le gateway : une adresse, une duree de vie d'un
 * jour figee dans le code, aucun motif, aucun auteur, et <b>aucun moyen de
 * consulter ou de lever</b> un blocage. Une adresse bloquee par erreur se
 * debloquait d'elle-meme au bout d'un jour, ce qui etait le seul point positif
 * de cette forme.
 *
 * <p>Tout le reste du chemin existait deja : la table {@code regle_ip}, son
 * entite, son depot, et la publication vers Redis. Il manquait ces trois
 * endpoints — donc le seul moyen pour une regle de naitre.
 *
 * <h2>Pourquoi le gateway ne doit plus ecrire dans Redis</h2>
 *
 * Parce qu'une decision ecrite sans trace n'est pas revocable : personne ne sait
 * pourquoi l'adresse a ete bloquee, donc personne n'ose la debloquer. La base
 * porte desormais la decision et son motif ; Redis n'est que le cache que le
 * gateway interroge a chaque requete.
 */
@RestController
@RequestMapping(SecurityConstants.ADMIN_BASE_PATH)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Regles d'adresses",
        description = "Decide quelles adresses sont bloquees. L'application est faite par "
                + "IpBlacklistWebFilter, dans le gateway, qui interroge les cles publiees "
                + "dans Redis.")
public class RegleDIpController {

    private final IRegleDIpService reglesDIp;
    private final IPublicationDeLaPolitique publication;

    @GetMapping(SecurityConstants.REGLES_IP_ENDPOINT)
    @Operation(summary = "Regles de blocage non levees",
            description = """
                    Les actives d'abord, puis les plus recentes.

                    Les regles **expirees mais non levees** figurent dans la \
                    liste, marquees `active: false` : une echeance passee n'est \
                    pas une levee, et savoir qu'une adresse a ete bloquee la \
                    semaine derniere est ce qu'on vient chercher ici.""")
    @ApiResponse(responseCode = "200", description = "Regles non levees")
    public Flux<RegleDIpResponse> regles() {
        return reglesDIp.regles();
    }

    @PostMapping(SecurityConstants.REGLES_IP_ENDPOINT)
    @Operation(summary = "Bloque une adresse",
            description = """
                    L'adresse est **normalisee** avant ecriture : \
                    `041.223.010.005` et `41.223.10.5` designent la meme machine \
                    et produisent la meme cle. Sans cette normalisation, deux \
                    ecritures donneraient deux regles dont une seule serait \
                    consultee.

                    Un **prefixe CIDR est refuse**. Ce n'est pas une limitation \
                    arbitraire : le filtre repond par une correspondance de cle \
                    exacte, donc une regle de prefixe serait enregistree, \
                    publiee, et ne correspondrait a aucune requete. Le blocage \
                    serait confirme sans jamais s'appliquer.

                    Une **detection automatique doit porter une echeance** : un \
                    faux positif permanent enferme une adresse sans recours, et \
                    la victime ne peut meme plus se connecter pour le signaler. \
                    Seule une decision humaine peut etre permanente.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Adresse bloquee et publiee"),
            @ApiResponse(responseCode = "400",
                    description = "Adresse illisible, prefixe CIDR, echeance deja passee, "
                            + "detection automatique sans echeance, ou adresse deja couverte "
                            + "par une regle active")
    })
    public Mono<ResponseEntity<RegleDIpResponse>> bloquer(
            @Valid @RequestBody PoseDeRegleIpRequest requete) {

        return AppelantAdministrateur.identifiant()
                .flatMap(auteur -> reglesDIp.poser(requete, auteur))
                // La publication suit l'ecriture et son echec n'annule pas la
                // decision : la base est la source, et la republication
                // periodique ramene le cache.
                //
                // Sans cet appel, la regle n'atteindrait Redis qu'a la
                // publication planifiee suivante — cinq minutes plus tard. Un
                // administrateur qui bloque une adresse en cours d'attaque
                // verrait un succes et l'attaque continuerait.
                .flatMap(posee -> publication.publierLesReglesDIp().thenReturn(posee))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping(SecurityConstants.REGLE_IP_PAR_ID_ENDPOINT)
    @Operation(summary = "Leve une regle de blocage",
            description = """
                    La ligne n'est pas supprimee : savoir qu'une adresse a ete \
                    bloquee puis debloquee, par qui et quand, est exactement ce \
                    qu'un journal d'audit doit montrer.

                    Lever une regle deja levee rend une erreur nommee plutot \
                    qu'un succes : un succes ferait croire qu'un blocage \
                    courait encore.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Regle levee et cache mis a jour"),
            @ApiResponse(responseCode = "400", description = "Cette regle a deja ete levee"),
            @ApiResponse(responseCode = "404",
                    description = "Identifiant inconnu. 404 et non 403 : un 403 sur un "
                            + "identifiant existant permettrait d'enumerer les regles en "
                            + "lisant le seul code de statut")
    })
    public Mono<ResponseEntity<RegleDIpResponse>> lever(
            @Parameter(description = "Identifiant de la regle") @PathVariable String id) {

        return AppelantAdministrateur.identifiant()
                .flatMap(auteur -> reglesDIp.lever(id, auteur))
                // La republication RETIRE la cle : publierLesReglesDIp supprime
                // les cles des regles qui ne sont plus actives. Sans cet appel,
                // la cle Redis survivrait jusqu'a son expiration propre — et
                // indefiniment pour une regle permanente. L'adresse resterait
                // bloquee alors que la levee aurait ete confirmee.
                .flatMap(levee -> publication.publierLesReglesDIp().thenReturn(levee))
                .map(ResponseEntity::ok);
    }
}
