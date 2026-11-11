package com.abdatytch.market_service.web;

import com.abdatytch.market_service.dto.request.DecisionRequest;
import com.abdatytch.market_service.dto.response.AvisResponse;
import com.abdatytch.market_service.dto.response.BoutiqueResponse;
import com.abdatytch.market_service.dto.response.CategorieResponse;
import com.abdatytch.market_service.dto.response.ProduitResponse;
import com.abdatytch.market_service.service.ICategorieService;
import com.abdatytch.market_service.service.IModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * La moderation, et l'arbre du catalogue en ecriture.
 *
 * <h2>Deux couches, et c'est volontaire</h2>
 *
 * {@code /admin/**} exige deja {@code ROLE_ADMIN} dans la chaine de securite.
 * Les {@code @PreAuthorize} ci-dessous ne la remplacent pas : ils la doublent.
 *
 * <p>Ce depot a deja trouve trente-trois {@code @PreAuthorize} rendues inertes
 * par l'absence de l'annotation qui les active. Reposer sur une seule couche,
 * c'est reposer sur celle qui a deja disparu une fois.
 *
 * <h2>Le moderateur est identifie, et son identite est ECRITE</h2>
 *
 * {@code decidePar} garde le sujet du jeton. Une decision anonyme ne peut ni
 * se discuter, ni s'expliquer a celui qui l'a subie.
 */
@RestController
@RequestMapping("/admin/market")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Moderation", description = "Validation des boutiques, articles et avis")
public class AdminMarketController {

    private final IModerationService moderation;
    private final ICategorieService categories;
    private final Pagination pagination;

    public AdminMarketController(IModerationService moderation, ICategorieService categories,
                                 Pagination pagination) {
        this.moderation = moderation;
        this.categories = categories;
        this.pagination = pagination;
    }

    // -------------------------------------------------------------- boutiques

    @GetMapping("/boutiques")
    @Operation(summary = "Les boutiques en attente, les plus anciennes d'abord")
    public Flux<BoutiqueResponse> boutiques(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer taille) {
        return moderation.boutiquesAModerer(pagination.page(page), pagination.taille(taille));
    }

    @PostMapping("/boutiques/{boutiqueId}/decision")
    @Operation(summary = "Approuver, rejeter ou suspendre une boutique",
            description = "Un refus ou une suspension exige un motif : c'est lui qui "
                    + "permet de corriger.")
    public Mono<BoutiqueResponse> deciderBoutique(
            @AuthenticationPrincipal Jwt jeton, @PathVariable UUID boutiqueId,
            @Valid @RequestBody DecisionRequest decision) {
        return moderation.deciderBoutique(jeton.getSubject(), boutiqueId, decision);
    }

    // --------------------------------------------------------------- articles

    @GetMapping("/produits")
    @Operation(summary = "Les articles en attente")
    public Flux<ProduitResponse> produits(@RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer taille) {
        return moderation.produitsAModerer(pagination.page(page), pagination.taille(taille));
    }

    @PostMapping("/produits/{produitId}/decision")
    @Operation(summary = "Approuver ou rejeter un article")
    public Mono<ProduitResponse> deciderProduit(
            @AuthenticationPrincipal Jwt jeton, @PathVariable UUID produitId,
            @Valid @RequestBody DecisionRequest decision) {
        return moderation.deciderProduit(jeton.getSubject(), produitId, decision);
    }

    // ------------------------------------------------------------------ avis

    @GetMapping("/avis")
    @Operation(summary = "Les avis en attente")
    public Flux<AvisResponse> avis(@RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer taille) {
        return moderation.avisAModerer(pagination.page(page), pagination.taille(taille));
    }

    @PostMapping("/avis/{avisId}/decision")
    @Operation(summary = "Approuver ou rejeter un avis",
            description = "La note moyenne de l'article est recalculee : un avis approuve "
                    + "y entre, un avis rejete en sort.")
    public Mono<AvisResponse> deciderAvis(
            @AuthenticationPrincipal Jwt jeton, @PathVariable UUID avisId,
            @Valid @RequestBody DecisionRequest decision) {
        return moderation.deciderAvis(jeton.getSubject(), avisId, decision);
    }

    // ------------------------------------------------------------ categories

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Creer une categorie")
    public Mono<CategorieResponse> creerCategorie(@RequestParam String nom,
                                                  @RequestParam(required = false) UUID parent,
                                                  @RequestParam(required = false) Integer position) {
        return categories.creer(nom, parent, position);
    }

    @PutMapping("/categories/{categorieId}")
    @Operation(summary = "Renommer, deplacer ou desactiver une categorie",
            description = "Le slug ne change PAS avec le nom : une URL publiee et "
                    + "indexee ne doit pas mourir sur une correction de libelle.")
    public Mono<CategorieResponse> modifierCategorie(
            @PathVariable UUID categorieId,
            @RequestParam(required = false) String nom,
            @RequestParam(required = false) Integer position,
            @RequestParam(required = false) Boolean actif) {
        return categories.renommer(categorieId, nom, position, actif);
    }

    @DeleteMapping("/categories/{categorieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une categorie vide")
    public Mono<Void> supprimerCategorie(@PathVariable UUID categorieId) {
        return categories.supprimer(categorieId);
    }
}
