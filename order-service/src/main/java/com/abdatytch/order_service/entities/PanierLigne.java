package com.abdatytch.order_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Une ligne de panier.
 *
 * <p>AUCUN PRIX. Il est relu chez market-service a l'affichage et fige au
 * moment de commander, dans {@link CommandeLigne}. Un prix copie ici serait
 * faux des que la boutique le change, et l'acheteur verrait un montant qui
 * n'est pas celui qu'on lui demandera.
 */
@Table("panier_ligne")
public class PanierLigne extends BaseEntity {

    @Column("panier_id")
    private UUID panierId;

    @Column("produit_id")
    private UUID produitId;

    @Column("variante_id")
    private UUID varianteId;

    private Integer quantite;

    public UUID getPanierId() {
        return panierId;
    }

    public void setPanierId(UUID panierId) {
        this.panierId = panierId;
    }

    public UUID getProduitId() {
        return produitId;
    }

    public void setProduitId(UUID produitId) {
        this.produitId = produitId;
    }

    public UUID getVarianteId() {
        return varianteId;
    }

    public void setVarianteId(UUID varianteId) {
        this.varianteId = varianteId;
    }

    public Integer getQuantite() {
        return quantite;
    }

    public void setQuantite(Integer quantite) {
        this.quantite = quantite;
    }
}
