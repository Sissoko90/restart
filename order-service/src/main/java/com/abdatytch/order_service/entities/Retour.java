package com.abdatytch.order_service.entities;

import com.abdatytch.order_service.enums.MotifRetour;
import com.abdatytch.order_service.enums.StatutRetour;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un retour arbitre par Sankadi.
 *
 * <p>L'acheteur demande, Sankadi decide, la boutique subit la decision. C'est
 * tout l'interet de l'intermediaire : un retour ne depend pas du bon vouloir du
 * vendeur.
 *
 * <p>{@code recuLe} precede necessairement {@code rembourseLe}, et la contrainte
 * de base le garantit. Rembourser un colis qui n'est jamais revenu est l'erreur
 * la plus couteuse d'une place de marche, parce qu'elle est industrialisable :
 * on demande, on obtient l'accord, on n'envoie rien.
 *
 * <p>Aucun argent ne circule ici. {@code montantRembourse} enregistre ce que
 * Sankadi DOIT a l'acheteur ; payment-service le versera.
 */
@Table("retour")
public class Retour extends BaseEntity {

    @Column("commande_id")
    private UUID commandeId;

    @Column("demande_par")
    private String demandePar;

    private MotifRetour motif;

    private String commentaire;

    private StatutRetour statut = StatutRetour.DEMANDE;

    @Column("decide_par")
    private String decidePar;

    @Column("decide_le")
    private LocalDateTime decideLe;

    @Column("motif_decision")
    private String motifDecision;

    @Column("recu_le")
    private LocalDateTime recuLe;

    @Column("montant_rembourse")
    private Long montantRembourse;

    @Column("rembourse_le")
    private LocalDateTime rembourseLe;

    public UUID getCommandeId() {
        return commandeId;
    }

    public void setCommandeId(UUID commandeId) {
        this.commandeId = commandeId;
    }

    public String getDemandePar() {
        return demandePar;
    }

    public void setDemandePar(String demandePar) {
        this.demandePar = demandePar;
    }

    public MotifRetour getMotif() {
        return motif;
    }

    public void setMotif(MotifRetour motif) {
        this.motif = motif;
    }

    public String getCommentaire() {
        return commentaire;
    }

    public void setCommentaire(String commentaire) {
        this.commentaire = commentaire;
    }

    public StatutRetour getStatut() {
        return statut;
    }

    public void setStatut(StatutRetour statut) {
        this.statut = statut;
    }

    public String getDecidePar() {
        return decidePar;
    }

    public void setDecidePar(String decidePar) {
        this.decidePar = decidePar;
    }

    public LocalDateTime getDecideLe() {
        return decideLe;
    }

    public void setDecideLe(LocalDateTime decideLe) {
        this.decideLe = decideLe;
    }

    public String getMotifDecision() {
        return motifDecision;
    }

    public void setMotifDecision(String motifDecision) {
        this.motifDecision = motifDecision;
    }

    public LocalDateTime getRecuLe() {
        return recuLe;
    }

    public void setRecuLe(LocalDateTime recuLe) {
        this.recuLe = recuLe;
    }

    public Long getMontantRembourse() {
        return montantRembourse;
    }

    public void setMontantRembourse(Long montantRembourse) {
        this.montantRembourse = montantRembourse;
    }

    public LocalDateTime getRembourseLe() {
        return rembourseLe;
    }

    public void setRembourseLe(LocalDateTime rembourseLe) {
        this.rembourseLe = rembourseLe;
    }
}
