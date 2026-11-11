package com.abdatytch.market_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import com.abdatytch.market_service.enums.StatutModeration;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * La note et le commentaire d'un client sur un produit.
 *
 * <p>Un seul avis par personne et par produit, garanti en base : sans cela, la
 * note moyenne se manipule en publiant cent fois.
 *
 * <p>Modere comme un produit : un commentaire est du texte libre publie sous le
 * nom d'une boutique qui ne l'a pas ecrit.
 */
@Table("avis")
public class Avis extends BaseEntity {

    @Column("produit_id")
    private UUID produitId;

    @Column("auth_user_id")
    private String authUserId;

    @Column("note")
    private Short note;

    @Column("commentaire")
    private String commentaire;

    @Column("statut_moderation")
    private StatutModeration statutModeration;

    @Column("motif_decision")
    private String motifDecision;

    @Column("decide_par")
    private String decidePar;

    @Column("decide_le")
    private LocalDateTime decideLe;

    public UUID getProduitId() { return produitId; }
    public void setProduitId(UUID produitId) { this.produitId = produitId; }

    public String getAuthUserId() { return authUserId; }
    public void setAuthUserId(String authUserId) { this.authUserId = authUserId; }

    public Short getNote() { return note; }
    public void setNote(Short note) { this.note = note; }

    public String getCommentaire() { return commentaire; }
    public void setCommentaire(String commentaire) { this.commentaire = commentaire; }

    public StatutModeration getStatutModeration() { return statutModeration; }
    public void setStatutModeration(StatutModeration statutModeration) { this.statutModeration = statutModeration; }

    public String getMotifDecision() { return motifDecision; }
    public void setMotifDecision(String motifDecision) { this.motifDecision = motifDecision; }

    public String getDecidePar() { return decidePar; }
    public void setDecidePar(String decidePar) { this.decidePar = decidePar; }

    public LocalDateTime getDecideLe() { return decideLe; }
    public void setDecideLe(LocalDateTime decideLe) { this.decideLe = decideLe; }
}
