package com.abdatytch.order_service.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Le panier tel qu'il est rendu a l'acheteur.
 *
 * <h2>Le total est CALCULE a l'affichage, jamais stocke</h2>
 *
 * Les prix sont relus chez market-service a chaque consultation. Un total
 * stocke serait faux des que la boutique change un prix, et l'acheteur verrait
 * un montant qui n'est pas celui qu'on lui demandera.
 *
 * <p>La contrepartie est assumee : un panier peut changer de total entre deux
 * visites. C'est la verite, et la cacher serait pire.
 *
 * @param lignesIndisponibles les lignes dont l'article n'est plus visible,
 *                            plus actif, ou en rupture. Rendues a part et NON
 *                            supprimees : un article qui disparait du panier
 *                            sans explication se lit comme une perte de
 *                            donnees, et l'acheteur cherche ce qu'il avait mis
 */
public record PanierResponse(
        UUID panierId,
        List<LignePanierResponse> lignes,
        List<LignePanierResponse> lignesIndisponibles,
        long totalDisponible,
        String devise) {
}
