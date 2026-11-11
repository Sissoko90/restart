package com.abdatytch.market_service.web;

import com.abdatytch.market_service.constants.CheminsDuMarche;
import com.abdatytch.market_service.dto.request.AvisRequest;
import com.abdatytch.market_service.dto.response.AvisResponse;
import com.abdatytch.market_service.dto.response.PageResponse;
import com.abdatytch.market_service.dto.response.ProduitResponse;
import com.abdatytch.market_service.security.ResolveurDePortee;
import com.abdatytch.market_service.service.IAvisService;
import com.abdatytch.market_service.service.IProduitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Le catalogue, tel que le demandeur a le droit de le voir.
 *
 * <h2>Aucun parametre ne decide de la portee</h2>
 *
 * Ni le pays, ni la qualite de professionnel ne figurent dans la signature de
 * ces methodes : {@link ResolveurDePortee} les etablit a partir du jeton, de la
 * base et de user-service. Les accepter en parametre serait offrir a chacun le
 * moyen d'elargir ce qu'il voit.
 *
 * <h2>Pourquoi ce catalogue n'est PAS public</h2>
 *
 * Une vitrine ordinaire se lit sans compte. Ici, ce qu'on montre depend de qui
 * regarde — son pays, sa qualite — donc il faut le savoir pour repondre. Un
 * acces anonyme devrait montrer soit tout, soit rien.
 */
@RestController
@RequestMapping(CheminsDuMarche.PRODUITS)
@Tag(name = "Catalogue", description = "Les articles visibles par le demandeur")
public class CatalogueController {

    private final IProduitService produits;
    private final IAvisService avis;
    private final ResolveurDePortee portees;
    private final Pagination pagination;

    public CatalogueController(IProduitService produits, IAvisService avis,
                               ResolveurDePortee portees, Pagination pagination) {
        this.produits = produits;
        this.avis = avis;
        this.portees = portees;
        this.pagination = pagination;
    }

    @GetMapping
    @Operation(summary = "Lister le catalogue",
            description = "Un particulier ne voit que les articles publics de SON pays ; "
                    + "un compte professionnel voit tous les pays et les prix de gros.")
    public Mono<PageResponse<ProduitResponse>> lister(
            @RequestParam(required = false) UUID categorie,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer taille) {
        int p = pagination.page(page);
        int t = pagination.taille(taille);
        return portees.courante()
                .flatMap(portee -> produits.catalogue(portee, categorie, q, p, t));
    }

    @GetMapping("/{produitId}")
    @Operation(summary = "La fiche d'un article",
            description = "Rend 404 si l'article est hors de la portee du demandeur : "
                    + "un 403 confirmerait que la reference existe.")
    public Mono<ProduitResponse> fiche(@PathVariable UUID produitId) {
        return portees.courante().flatMap(portee -> produits.fiche(portee, produitId));
    }

    @GetMapping("/{produitId}/avis")
    @Operation(summary = "Les avis publies d'un article")
    public Flux<AvisResponse> avis(@PathVariable UUID produitId,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer taille) {
        int p = pagination.page(page);
        int t = pagination.taille(taille);
        return portees.courante()
                .flatMapMany(portee -> avis.publies(portee, produitId, p, t));
    }

    @PostMapping("/{produitId}/avis")
    @Operation(summary = "Deposer ou remplacer son avis",
            description = "Un seul avis par personne et par article. Un second envoi "
                    + "remplace le premier, et repart en moderation.")
    public Mono<AvisResponse> noter(@PathVariable UUID produitId,
                                    @Valid @RequestBody AvisRequest demande) {
        return portees.courante()
                .flatMap(portee -> avis.deposer(portee, produitId, demande));
    }

    @GetMapping("/{produitId}/avis/moi")
    @Operation(summary = "Son propre avis, meme en attente ou rejete",
            description = "Un avis qui disparait apres envoi se reecrit, et la contrainte "
                    + "d'unicite le refuse alors sans expliquer pourquoi.")
    public Mono<AvisResponse> monAvis(@PathVariable UUID produitId) {
        return portees.courante()
                .flatMap(portee -> avis.mien(portee.authUserId(), produitId));
    }
}
