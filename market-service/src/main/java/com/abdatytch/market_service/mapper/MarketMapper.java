package com.abdatytch.market_service.mapper;

import com.abdatytch.market_service.dto.response.AvisResponse;
import com.abdatytch.market_service.dto.response.BoutiqueResponse;
import com.abdatytch.market_service.dto.response.CaracteristiqueResponse;
import com.abdatytch.market_service.dto.response.CategorieResponse;
import com.abdatytch.market_service.dto.response.ProduitResponse;
import com.abdatytch.market_service.dto.response.VarianteResponse;
import com.abdatytch.market_service.entities.Avis;
import com.abdatytch.market_service.entities.Boutique;
import com.abdatytch.market_service.entities.Categorie;
import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.entities.ProduitCaracteristique;
import com.abdatytch.market_service.entities.ProduitVariante;

import java.util.List;

/**
 * Entites vers reponses.
 *
 * <h2>Des fonctions PURES, et aucun appel</h2>
 *
 * Pas de lecture en base, pas d'appel sortant, pas de journal. Ce depot a deja
 * paye un mapper qui bloquait sur chaque reponse : appele une fois par element
 * d'une page, il transformait un ecran de liste en autant d'allers-retours.
 *
 * <p>Ce qui demande un appel — les URL signees des photos — est resolu par le
 * service AVANT, en un seul lot pour toute la page, et passe ici en parametre.
 */
public final class MarketMapper {

    private MarketMapper() {
    }

    public static BoutiqueResponse versReponse(Boutique boutique) {
        return new BoutiqueResponse(
                boutique.getId(), boutique.getNom(), boutique.getDescription(),
                boutique.getRccm(), boutique.getNif(), boutique.getPays(),
                boutique.getVille(), boutique.getAdresse(), boutique.getTelephone(),
                boutique.getLogoDocumentId(), boutique.getCouvertureDocumentId(),
                boutique.getStatut(), boutique.getMotifDecision(), boutique.getCreatedAt());
    }

    public static ProduitResponse versReponse(Produit produit, String nomDeLaBoutique,
                                              List<String> photos,
                                              List<VarianteResponse> variantes,
                                              List<CaracteristiqueResponse> caracteristiques) {
        return new ProduitResponse(
                produit.getId(), produit.getBoutiqueId(), nomDeLaBoutique,
                produit.getCategorieId(), produit.getNom(), produit.getDescription(),
                produit.getPrix(), produit.getDevise(), produit.getPays(),
                produit.getVisibilite(), produit.getStatutModeration(),
                produit.getMotifDecision(), produit.getActif(),
                produit.getNoteMoyenne(), produit.getNombreAvis(),
                photos == null ? List.of() : photos,
                variantes == null ? List.of() : variantes,
                caracteristiques == null ? List.of() : caracteristiques,
                produit.getCreatedAt());
    }

    public static VarianteResponse versReponse(ProduitVariante variante) {
        return new VarianteResponse(variante.getId(), variante.getSku(),
                variante.getCouleur(), variante.getTaille(), variante.getPrix(),
                variante.getStock(), variante.getActif());
    }

    public static CaracteristiqueResponse versReponse(ProduitCaracteristique c) {
        return new CaracteristiqueResponse(c.getCle(), c.getValeur(), c.getPosition());
    }

    public static CategorieResponse versReponse(Categorie categorie) {
        return new CategorieResponse(categorie.getId(), categorie.getParentId(),
                categorie.getNom(), categorie.getSlug(), categorie.getPosition(),
                categorie.getActif());
    }

    public static AvisResponse versReponse(Avis avis) {
        return new AvisResponse(avis.getId(), avis.getProduitId(),
                libelleDAuteur(avis.getAuthUserId()), avis.getNote(),
                avis.getCommentaire(), avis.getStatutModeration(),
                avis.getMotifDecision(), avis.getCreatedAt());
    }

    /**
     * Un libelle stable, jamais l'identifiant.
     *
     * <h3>Pourquoi pas le {@code sub} du porteur</h3>
     *
     * Il relierait chaque avis au compte qui l'a ecrit, sur une page publique.
     * Deux avis du meme auteur restent reconnaissables entre eux — ce qui est
     * utile pour reperer une campagne — sans que personne puisse remonter au
     * compte.
     *
     * <p>Tronque aux extremites et non au debut : deux identifiants partageant
     * leurs premiers caracteres, ce qui arrive avec des UUID engendres en
     * sequence, donneraient sinon le meme libelle.
     */
    static String libelleDAuteur(String authUserId) {
        if (authUserId == null || authUserId.isBlank()) {
            return "anonyme";
        }
        String propre = authUserId.trim();
        if (propre.length() <= 6) {
            return propre;
        }
        return propre.substring(0, 2) + "…" + propre.substring(propre.length() - 2);
    }
}
