package com.abdatytch.market_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Une declinaison : couleur, taille, prix et stock propres.
 *
 * <p>Un prix complet et non un supplement : une taille XXL a souvent son
 * propre prix, et un supplement negatif se lit mal.
 */
@Table("produit_variante")
public class ProduitVariante extends BaseEntity {

    @Column("produit_id")
    private UUID produitId;

    @Column("sku")
    private String sku;

    @Column("couleur")
    private String couleur;

    @Column("taille")
    private String taille;

    @Column("prix")
    private Long prix;

    @Column("stock")
    private Integer stock;

    @Column("actif")
    private Boolean actif;

    public UUID getProduitId() { return produitId; }
    public void setProduitId(UUID produitId) { this.produitId = produitId; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getCouleur() { return couleur; }
    public void setCouleur(String couleur) { this.couleur = couleur; }

    public String getTaille() { return taille; }
    public void setTaille(String taille) { this.taille = taille; }

    public Long getPrix() { return prix; }
    public void setPrix(Long prix) { this.prix = prix; }

    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    public Boolean getActif() { return actif; }
    public void setActif(Boolean actif) { this.actif = actif; }
}
