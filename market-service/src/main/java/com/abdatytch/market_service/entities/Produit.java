package com.abdatytch.market_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.enums.Visibilite;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un article du catalogue.
 *
 * <h2>Quatre champs decident de qui le voit</h2>
 *
 * {@code statutModeration}, {@code actif}, {@code visibilite} et {@code pays}.
 * Aucun code ne doit les comparer a la main : {@code PorteeDuCatalogue} est le
 * seul detenteur de la regle, et les requetes la recoivent en parametre.
 *
 * <h2>Le prix est un entier</h2>
 *
 * En plus petite unite monetaire. Un {@code BigDecimal} aurait convenu, un
 * {@code double} jamais : l'arithmetique decimale approchee sur de l'argent
 * finit toujours par couter un centime a quelqu'un, et personne ne sait lequel.
 *
 * <h2>{@code noteMoyenne} et {@code nombreAvis} sont des AGREGATS</h2>
 *
 * Tenus a jour a l'ecriture d'un avis. Les recalculer a la lecture ferait une
 * agregation par produit affiche — le defaut N+1 sur l'ecran le plus consulte.
 * Ils peuvent deriver ; une tache planifiee les reconcilie.
 */
@Table("produit")
public class Produit extends BaseEntity {

    @Column("boutique_id")
    private UUID boutiqueId;

    @Column("categorie_id")
    private UUID categorieId;

    @Column("nom")
    private String nom;

    @Column("description")
    private String description;

    @Column("prix")
    private Long prix;

    @Column("devise")
    private String devise;

    @Column("pays")
    private String pays;

    @Column("visibilite")
    private Visibilite visibilite;

    @Column("statut_moderation")
    private StatutModeration statutModeration;

    @Column("motif_decision")
    private String motifDecision;

    @Column("decide_par")
    private String decidePar;

    @Column("decide_le")
    private LocalDateTime decideLe;

    @Column("actif")
    private Boolean actif;

    @Column("note_moyenne")
    private BigDecimal noteMoyenne;

    @Column("nombre_avis")
    private Integer nombreAvis;

    /** Figure-t-il dans une vitrine, pour qui que ce soit. */
    public boolean estPublie() {
        return Boolean.TRUE.equals(actif)
                && statutModeration != null && statutModeration.estPublie();
    }

    public UUID getBoutiqueId() { return boutiqueId; }
    public void setBoutiqueId(UUID boutiqueId) { this.boutiqueId = boutiqueId; }

    public UUID getCategorieId() { return categorieId; }
    public void setCategorieId(UUID categorieId) { this.categorieId = categorieId; }

    public String getNom() { return nom; }
    public void setNom(String nom) { this.nom = nom; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getPrix() { return prix; }
    public void setPrix(Long prix) { this.prix = prix; }

    public String getDevise() { return devise; }
    public void setDevise(String devise) { this.devise = devise; }

    public String getPays() { return pays; }
    public void setPays(String pays) { this.pays = pays; }

    public Visibilite getVisibilite() { return visibilite; }
    public void setVisibilite(Visibilite visibilite) { this.visibilite = visibilite; }

    public StatutModeration getStatutModeration() { return statutModeration; }
    public void setStatutModeration(StatutModeration statutModeration) { this.statutModeration = statutModeration; }

    public String getMotifDecision() { return motifDecision; }
    public void setMotifDecision(String motifDecision) { this.motifDecision = motifDecision; }

    public String getDecidePar() { return decidePar; }
    public void setDecidePar(String decidePar) { this.decidePar = decidePar; }

    public LocalDateTime getDecideLe() { return decideLe; }
    public void setDecideLe(LocalDateTime decideLe) { this.decideLe = decideLe; }

    public Boolean getActif() { return actif; }
    public void setActif(Boolean actif) { this.actif = actif; }

    public BigDecimal getNoteMoyenne() { return noteMoyenne; }
    public void setNoteMoyenne(BigDecimal noteMoyenne) { this.noteMoyenne = noteMoyenne; }

    public Integer getNombreAvis() { return nombreAvis; }
    public void setNombreAvis(Integer nombreAvis) { this.nombreAvis = nombreAvis; }
}
