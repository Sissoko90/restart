package com.abdatytch.order_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Une ligne de commande : UN INSTANTANE, pas une reference.
 *
 * <p>Le nom, la couleur, la taille, le SKU et le prix sont copies au moment de
 * l'achat. La boutique peut ensuite renommer son article, changer son prix, le
 * retirer ou le supprimer : la commande continue de dire ce qui a ete achete et
 * a quel prix.
 *
 * <p>Une ligne qui ne porterait que {@code varianteId} laisserait une facture
 * s'invalider toute seule, et un litige arbitre par Sankadi n'aurait plus
 * d'objet a examiner.
 */
@Table("commande_ligne")
public class CommandeLigne extends BaseEntity {

    @Column("commande_id")
    private UUID commandeId;

    @Column("produit_id")
    private UUID produitId;

    @Column("variante_id")
    private UUID varianteId;

    @Column("nom_produit")
    private String nomProduit;

    private String couleur;
    private String taille;
    private String sku;

    @Column("prix_unitaire")
    private Long prixUnitaire;

    private Integer quantite;

    @Column("montant_ligne")
    private Long montantLigne;

    public UUID getCommandeId() {
        return commandeId;
    }

    public void setCommandeId(UUID commandeId) {
        this.commandeId = commandeId;
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

    public String getNomProduit() {
        return nomProduit;
    }

    public void setNomProduit(String nomProduit) {
        this.nomProduit = nomProduit;
    }

    public String getCouleur() {
        return couleur;
    }

    public void setCouleur(String couleur) {
        this.couleur = couleur;
    }

    public String getTaille() {
        return taille;
    }

    public void setTaille(String taille) {
        this.taille = taille;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Long getPrixUnitaire() {
        return prixUnitaire;
    }

    public void setPrixUnitaire(Long prixUnitaire) {
        this.prixUnitaire = prixUnitaire;
    }

    public Integer getQuantite() {
        return quantite;
    }

    public void setQuantite(Integer quantite) {
        this.quantite = quantite;
    }

    public Long getMontantLigne() {
        return montantLigne;
    }

    public void setMontantLigne(Long montantLigne) {
        this.montantLigne = montantLigne;
    }
}
