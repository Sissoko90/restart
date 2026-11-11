package com.abdatytch.market_service.web;

import com.abdatytch.market_service.constants.CheminsDuMarche;
import com.abdatytch.market_service.dto.request.CaracteristiqueRequest;
import com.abdatytch.market_service.dto.request.CreationBoutiqueRequest;
import com.abdatytch.market_service.dto.request.CreationProduitRequest;
import com.abdatytch.market_service.dto.request.PhotoRequest;
import com.abdatytch.market_service.dto.request.PieceJustificativeRequest;
import com.abdatytch.market_service.dto.request.VarianteRequest;
import com.abdatytch.market_service.dto.response.BoutiqueResponse;
import com.abdatytch.market_service.dto.response.PageResponse;
import com.abdatytch.market_service.dto.response.ProduitResponse;
import com.abdatytch.market_service.service.IBoutiqueService;
import com.abdatytch.market_service.service.IProduitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * L'espace boutique : le dossier, et les articles qu'on y vend.
 *
 * <h2>{@code /moi} et jamais {@code /{boutiqueId}}</h2>
 *
 * Toutes les operations du vendeur portent sur SA boutique, retrouvee par le
 * sujet du jeton. Accepter un identifiant de boutique en chemin obligerait a
 * verifier la propriete a chaque methode — et c'est l'oubli d'une seule de ces
 * verifications qui produit un acces direct a l'objet d'autrui.
 *
 * <p>La seule methode qui prenne un identifiant est la VITRINE, qui ne rend
 * qu'une boutique approuvee et sans son dossier administratif.
 */
@RestController
@RequestMapping(CheminsDuMarche.BOUTIQUES)
@Tag(name = "Boutique", description = "Le compte professionnel et ses articles")
public class BoutiqueController {

    private final IBoutiqueService boutiques;
    private final IProduitService produits;
    private final Pagination pagination;

    public BoutiqueController(IBoutiqueService boutiques, IProduitService produits,
                              Pagination pagination) {
        this.boutiques = boutiques;
        this.produits = produits;
        this.pagination = pagination;
    }

    // ---------------------------------------------------------------- dossier

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ouvrir une boutique",
            description = "Le pays est lu chez user-service, jamais dans la demande : "
                    + "c'est lui qui determine a qui les articles seront visibles.")
    public Mono<BoutiqueResponse> ouvrir(@AuthenticationPrincipal Jwt jeton,
                                         @Valid @RequestBody CreationBoutiqueRequest demande) {
        return boutiques.ouvrir(jeton.getSubject(), demande);
    }

    @GetMapping("/moi")
    @Operation(summary = "Ma boutique, quel que soit son etat")
    public Mono<BoutiqueResponse> mienne(@AuthenticationPrincipal Jwt jeton) {
        return boutiques.mienne(jeton.getSubject());
    }

    @PutMapping("/moi")
    @Operation(summary = "Modifier ma boutique",
            description = "Changer le RCCM ou le NIF renvoie la boutique en moderation : "
                    + "ce sont les pieces sur lesquelles la validation a porte.")
    public Mono<BoutiqueResponse> modifier(@AuthenticationPrincipal Jwt jeton,
                                           @Valid @RequestBody CreationBoutiqueRequest demande) {
        return boutiques.modifier(jeton.getSubject(), demande);
    }

    @PostMapping("/moi/pieces")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Rattacher une piece justificative",
            description = "Le fichier est depose chez media-service ; seul son "
                    + "identifiant arrive ici.")
    public Mono<Void> rattacher(@AuthenticationPrincipal Jwt jeton,
                                @Valid @RequestBody PieceJustificativeRequest demande) {
        return boutiques.rattacherUnePiece(jeton.getSubject(), demande);
    }

    @GetMapping("/{boutiqueId}")
    @Operation(summary = "La vitrine d'une boutique approuvee",
            description = "Sans RCCM ni NIF : un acheteur n'a pas a lire le dossier "
                    + "administratif d'un commercant.")
    public Mono<BoutiqueResponse> vitrine(@PathVariable UUID boutiqueId) {
        return boutiques.vitrine(boutiqueId);
    }

    // --------------------------------------------------------------- articles

    @GetMapping("/moi/produits")
    @Operation(summary = "Mes articles, quel que soit leur etat de moderation")
    public Mono<PageResponse<ProduitResponse>> mesProduits(
            @AuthenticationPrincipal Jwt jeton,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer taille) {
        return produits.mesProduits(jeton.getSubject(), pagination.page(page),
                pagination.taille(taille));
    }

    @PostMapping("/moi/produits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Creer un article",
            description = "L'article nait EN_ATTENTE : rien n'est publie sans moderation.")
    public Mono<ProduitResponse> creer(@AuthenticationPrincipal Jwt jeton,
                                       @Valid @RequestBody CreationProduitRequest demande) {
        return produits.creer(jeton.getSubject(), demande);
    }

    @PutMapping("/moi/produits/{produitId}")
    @Operation(summary = "Modifier un article",
            description = "Toute modification le renvoie en moderation : sans cela, il "
                    + "suffirait de faire approuver un article anodin puis de le remplacer.")
    public Mono<ProduitResponse> modifierProduit(
            @AuthenticationPrincipal Jwt jeton, @PathVariable UUID produitId,
            @Valid @RequestBody CreationProduitRequest demande) {
        return produits.modifier(jeton.getSubject(), produitId, demande);
    }

    @PutMapping("/moi/produits/{produitId}/photos")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remplacer la galerie",
            description = "Remplacement complet, ordre compris. Les fichiers vivent chez "
                    + "media-service ; seuls leurs identifiants arrivent ici.")
    public Mono<Void> photos(@AuthenticationPrincipal Jwt jeton, @PathVariable UUID produitId,
                             @Valid @RequestBody List<PhotoRequest> photos) {
        return produits.remplacerLesPhotos(jeton.getSubject(), produitId, photos);
    }

    @PutMapping("/moi/produits/{produitId}/variantes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remplacer les declinaisons (couleur, taille, prix, stock)")
    public Mono<Void> variantes(@AuthenticationPrincipal Jwt jeton,
                                @PathVariable UUID produitId,
                                @Valid @RequestBody List<VarianteRequest> variantes) {
        return produits.remplacerLesVariantes(jeton.getSubject(), produitId, variantes);
    }

    @PutMapping("/moi/produits/{produitId}/caracteristiques")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remplacer les caracteristiques libres")
    public Mono<Void> caracteristiques(
            @AuthenticationPrincipal Jwt jeton, @PathVariable UUID produitId,
            @Valid @RequestBody List<CaracteristiqueRequest> caracteristiques) {
        return produits.remplacerLesCaracteristiques(jeton.getSubject(), produitId,
                caracteristiques);
    }

    @PatchMapping("/moi/produits/{produitId}/actif")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer ou remettre un article en vitrine",
            description = "Ne renvoie PAS en moderation : le contenu n'a pas change.")
    public Mono<Void> activation(@AuthenticationPrincipal Jwt jeton,
                                 @PathVariable UUID produitId,
                                 @RequestParam boolean actif) {
        return produits.changerLActivation(jeton.getSubject(), produitId, actif);
    }

    @DeleteMapping("/moi/produits/{produitId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un article")
    public Mono<Void> supprimer(@AuthenticationPrincipal Jwt jeton,
                                @PathVariable UUID produitId) {
        return produits.supprimer(jeton.getSubject(), produitId);
    }
}
