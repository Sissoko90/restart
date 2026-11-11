package com.abdatytch.market_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import com.abdatytch.market_service.enums.StatutBoutique;

import java.time.LocalDateTime;

/**
 * Le compte professionnel : une boutique et son dossier de validation.
 *
 * <h2>Une personne, une boutique</h2>
 *
 * {@code authUserId} est UNIQUE en base. La resolution de portee du catalogue
 * suppose une boutique au plus par compte ; lever cette regle demanderait de la
 * revoir, pas seulement de retirer la contrainte.
 *
 * <h2>{@code pays} ne vient JAMAIS du formulaire</h2>
 *
 * Il est recopie depuis user-service a la creation. C'est lui qui decide de la
 * visibilite des produits : le laisser saisir permettrait a quiconque
 * d'elargir sa portee en se declarant d'ailleurs.
 */
@Table("boutique")
public class Boutique extends BaseEntity {

    @Column("auth_user_id")
    private String authUserId;

    @Column("nom")
    private String nom;

    @Column("description")
    private String description;

    @Column("rccm")
    private String rccm;

    @Column("nif")
    private String nif;

    @Column("pays")
    private String pays;

    @Column("ville")
    private String ville;

    @Column("adresse")
    private String adresse;

    @Column("telephone")
    private String telephone;

    @Column("logo_document_id")
    private String logoDocumentId;

    @Column("couverture_document_id")
    private String couvertureDocumentId;

    @Column("statut")
    private StatutBoutique statut;

    @Column("motif_decision")
    private String motifDecision;

    @Column("decide_par")
    private String decidePar;

    @Column("decide_le")
    private LocalDateTime decideLe;

    /** Le proprietaire est-il professionnel au sens de ce service. */
    public boolean estProfessionnelle() {
        return statut != null && statut.rendProfessionnel();
    }

    public String getAuthUserId() { return authUserId; }
    public void setAuthUserId(String authUserId) { this.authUserId = authUserId; }

    public String getNom() { return nom; }
    public void setNom(String nom) { this.nom = nom; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRccm() { return rccm; }
    public void setRccm(String rccm) { this.rccm = rccm; }

    public String getNif() { return nif; }
    public void setNif(String nif) { this.nif = nif; }

    public String getPays() { return pays; }
    public void setPays(String pays) { this.pays = pays; }

    public String getVille() { return ville; }
    public void setVille(String ville) { this.ville = ville; }

    public String getAdresse() { return adresse; }
    public void setAdresse(String adresse) { this.adresse = adresse; }

    public String getTelephone() { return telephone; }
    public void setTelephone(String telephone) { this.telephone = telephone; }

    public String getLogoDocumentId() { return logoDocumentId; }
    public void setLogoDocumentId(String logoDocumentId) { this.logoDocumentId = logoDocumentId; }

    public String getCouvertureDocumentId() { return couvertureDocumentId; }
    public void setCouvertureDocumentId(String couvertureDocumentId) { this.couvertureDocumentId = couvertureDocumentId; }

    public StatutBoutique getStatut() { return statut; }
    public void setStatut(StatutBoutique statut) { this.statut = statut; }

    public String getMotifDecision() { return motifDecision; }
    public void setMotifDecision(String motifDecision) { this.motifDecision = motifDecision; }

    public String getDecidePar() { return decidePar; }
    public void setDecidePar(String decidePar) { this.decidePar = decidePar; }

    public LocalDateTime getDecideLe() { return decideLe; }
    public void setDecideLe(LocalDateTime decideLe) { this.decideLe = decideLe; }
}
