package com.abdatytch.market_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Un attribut libre : « marque », « matiere », « garantie ».
 *
 * <p>Une table plutot qu'un JSONB, parce que ces valeurs sont destinees a etre
 * FILTREES et qu'un JSONB rend ce filtrage possible mais illisible.
 */
@Table("produit_caracteristique")
public class ProduitCaracteristique extends BaseEntity {

    @Column("produit_id")
    private UUID produitId;

    @Column("cle")
    private String cle;

    @Column("valeur")
    private String valeur;

    @Column("position")
    private Integer position;

    public UUID getProduitId() { return produitId; }
    public void setProduitId(UUID produitId) { this.produitId = produitId; }

    public String getCle() { return cle; }
    public void setCle(String cle) { this.cle = cle; }

    public String getValeur() { return valeur; }
    public void setValeur(String valeur) { this.valeur = valeur; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
}
