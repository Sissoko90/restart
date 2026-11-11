package com.abdatytch.market_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Une photo de produit, par reference.
 *
 * <p>{@code documentId} designe un document de media-service. Une seule photo
 * par produit porte {@code principale}, et c'est un index unique PARTIEL qui le
 * garantit — une contrainte ordinaire interdirait deux photos secondaires.
 */
@Table("produit_photo")
public class ProduitPhoto extends BaseEntity {

    @Column("produit_id")
    private UUID produitId;

    @Column("document_id")
    private String documentId;

    @Column("position")
    private Integer position;

    @Column("principale")
    private Boolean principale;

    public UUID getProduitId() { return produitId; }
    public void setProduitId(UUID produitId) { this.produitId = produitId; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public Boolean getPrincipale() { return principale; }
    public void setPrincipale(Boolean principale) { this.principale = principale; }
}
