package com.abdatytch.order_service.entities;

import com.abdatytch.order_service.enums.StatutCommande;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une commande : un acheteur, UNE boutique.
 *
 * <h2>Les montants sont figes, y compris le taux de commission</h2>
 *
 * Le taux de Sankadi changera. Une commande passee sous l'ancien taux doit
 * garder SES conditions : recalculer la commission depuis le taux courant
 * reecrirait le passe, et un versement a la boutique ne correspondrait plus a
 * ce qui lui avait ete annonce.
 *
 * <h2>Les dates viennent d'ailleurs</h2>
 *
 * {@code payeeLe} sera constatee par payment-service, {@code expedieeLe} et
 * {@code livreeLe} par logistics-service. Aucun des deux n'existe encore :
 * l'administration Sankadi les pose. La commande ne connait pas l'appelant.
 *
 * <h2>La fenetre de retour est STOCKEE</h2>
 *
 * Et non recalculee depuis {@code livreeLe} plus un delai configure. Le delai
 * changera, et une commande deja livree doit garder la fenetre annoncee a son
 * acheteur. Un message qui annonce une duree differente de l'echeance reelle
 * est un defaut que ce depot a deja rencontre.
 */
@Table("commande")
public class Commande extends BaseEntity {

    private String numero;

    @Column("auth_user_id")
    private String authUserId;

    @Column("boutique_id")
    private UUID boutiqueId;

    private String pays;

    private StatutCommande statut = StatutCommande.A_PAYER;

    @Column("montant_articles")
    private Long montantArticles;

    @Column("commission_taux_pourcent")
    private BigDecimal commissionTauxPourcent;

    @Column("commission_montant")
    private Long commissionMontant;

    @Column("montant_du_a_la_boutique")
    private Long montantDuALaBoutique;

    private String devise;

    @Column("a_payer_jusqu_au")
    private LocalDateTime aPayerJusquAu;

    @Column("payee_le")
    private LocalDateTime payeeLe;

    @Column("expediee_le")
    private LocalDateTime expedieeLe;

    @Column("livree_le")
    private LocalDateTime livreeLe;

    @Column("retour_possible_jusqu_au")
    private LocalDateTime retourPossibleJusquAu;

    @Column("cloturee_le")
    private LocalDateTime clotureeLe;

    @Column("motif_annulation")
    private String motifAnnulation;

    public String getNumero() {
        return numero;
    }

    public void setNumero(String numero) {
        this.numero = numero;
    }

    public String getAuthUserId() {
        return authUserId;
    }

    public void setAuthUserId(String authUserId) {
        this.authUserId = authUserId;
    }

    public UUID getBoutiqueId() {
        return boutiqueId;
    }

    public void setBoutiqueId(UUID boutiqueId) {
        this.boutiqueId = boutiqueId;
    }

    public String getPays() {
        return pays;
    }

    public void setPays(String pays) {
        this.pays = pays;
    }

    public StatutCommande getStatut() {
        return statut;
    }

    public void setStatut(StatutCommande statut) {
        this.statut = statut;
    }

    public Long getMontantArticles() {
        return montantArticles;
    }

    public void setMontantArticles(Long montantArticles) {
        this.montantArticles = montantArticles;
    }

    public BigDecimal getCommissionTauxPourcent() {
        return commissionTauxPourcent;
    }

    public void setCommissionTauxPourcent(BigDecimal commissionTauxPourcent) {
        this.commissionTauxPourcent = commissionTauxPourcent;
    }

    public Long getCommissionMontant() {
        return commissionMontant;
    }

    public void setCommissionMontant(Long commissionMontant) {
        this.commissionMontant = commissionMontant;
    }

    public Long getMontantDuALaBoutique() {
        return montantDuALaBoutique;
    }

    public void setMontantDuALaBoutique(Long montantDuALaBoutique) {
        this.montantDuALaBoutique = montantDuALaBoutique;
    }

    public String getDevise() {
        return devise;
    }

    public void setDevise(String devise) {
        this.devise = devise;
    }

    public LocalDateTime getAPayerJusquAu() {
        return aPayerJusquAu;
    }

    public void setAPayerJusquAu(LocalDateTime aPayerJusquAu) {
        this.aPayerJusquAu = aPayerJusquAu;
    }

    public LocalDateTime getPayeeLe() {
        return payeeLe;
    }

    public void setPayeeLe(LocalDateTime payeeLe) {
        this.payeeLe = payeeLe;
    }

    public LocalDateTime getExpedieeLe() {
        return expedieeLe;
    }

    public void setExpedieeLe(LocalDateTime expedieeLe) {
        this.expedieeLe = expedieeLe;
    }

    public LocalDateTime getLivreeLe() {
        return livreeLe;
    }

    public void setLivreeLe(LocalDateTime livreeLe) {
        this.livreeLe = livreeLe;
    }

    public LocalDateTime getRetourPossibleJusquAu() {
        return retourPossibleJusquAu;
    }

    public void setRetourPossibleJusquAu(LocalDateTime retourPossibleJusquAu) {
        this.retourPossibleJusquAu = retourPossibleJusquAu;
    }

    public LocalDateTime getClotureeLe() {
        return clotureeLe;
    }

    public void setClotureeLe(LocalDateTime clotureeLe) {
        this.clotureeLe = clotureeLe;
    }

    public String getMotifAnnulation() {
        return motifAnnulation;
    }

    public void setMotifAnnulation(String motifAnnulation) {
        this.motifAnnulation = motifAnnulation;
    }
}
