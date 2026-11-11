package com.abdatytch.market_service.web;

import com.abdatytch.market_service.exception.RefusExplique;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Borne les parametres de pagination.
 *
 * <h2>Un plafond, et un refus EXPLIQUE au-dela</h2>
 *
 * Sans plafond, un appelant demandant dix mille articles ferait ramener dix
 * mille lignes et autant de photos a signer. Tronquer en silence serait pire
 * que refuser : l'appelant croirait avoir tout recu.
 */
@Component
public class Pagination {

    private final int maximum;
    private final int defaut;

    public Pagination(@Value("${market.catalogue.taille-page-maximale:100}") int maximum,
                      @Value("${market.catalogue.taille-page-defaut:20}") int defaut) {
        this.maximum = maximum;
        this.defaut = defaut;
    }

    public int page(Integer demandee) {
        if (demandee == null) {
            return 0;
        }
        if (demandee < 0) {
            throw RefusExplique.demandeInvalide("Le numero de page part de 0.");
        }
        return demandee;
    }

    public int taille(Integer demandee) {
        if (demandee == null) {
            return defaut;
        }
        if (demandee < 1) {
            throw RefusExplique.demandeInvalide("La taille de page vaut au moins 1.");
        }
        if (demandee > maximum) {
            throw RefusExplique.demandeInvalide(
                    "La taille de page est plafonnee a " + maximum + ".");
        }
        return demandee;
    }
}
