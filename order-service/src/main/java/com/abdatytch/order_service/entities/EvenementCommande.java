package com.abdatytch.order_service.entities;

import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Une transition de commande, conservee.
 *
 * <h2>Un intermediaire qui ne peut rien prouver n'arbitre rien</h2>
 *
 * Sankadi se tient entre un acheteur et une boutique qui n'ont pas de relation
 * directe. Quand l'un affirme avoir expedie et l'autre n'avoir rien recu, le
 * seul recours est l'historique : qui a declare quoi, quand, et sous quel role.
 *
 * <p>Ce n'est pas un journal applicatif. Un journal se purge, se perd, et ne
 * repond pas a une requete SQL. C'est une table, et elle est en APPEND SEUL :
 * aucun service ne la modifie ni ne la supprime. {@code RegistreEnAppendSeulTest}
 * refuse qu'un {@code update} ou un {@code delete} y apparaisse.
 */
@Table("evenement_commande")
public class EvenementCommande extends BaseEntity {

    @Column("commande_id")
    private UUID commandeId;

    /** Nul pour l'evenement de creation : il n'y a pas d'etat precedent. */
    @Column("de_statut")
    private StatutCommande deStatut;

    @Column("vers_statut")
    private StatutCommande versStatut;

    @Column("par_qui")
    private String parQui;

    private RoleDeTransition role;

    private String motif;

    public UUID getCommandeId() {
        return commandeId;
    }

    public void setCommandeId(UUID commandeId) {
        this.commandeId = commandeId;
    }

    public StatutCommande getDeStatut() {
        return deStatut;
    }

    public void setDeStatut(StatutCommande deStatut) {
        this.deStatut = deStatut;
    }

    public StatutCommande getVersStatut() {
        return versStatut;
    }

    public void setVersStatut(StatutCommande versStatut) {
        this.versStatut = versStatut;
    }

    public String getParQui() {
        return parQui;
    }

    public void setParQui(String parQui) {
        this.parQui = parQui;
    }

    public RoleDeTransition getRole() {
        return role;
    }

    public void setRole(RoleDeTransition role) {
        this.role = role;
    }

    public String getMotif() {
        return motif;
    }

    public void setMotif(String motif) {
        this.motif = motif;
    }
}
