package com.abdatytch.order_service.entities;

import com.abdatytch.order_service.enums.StatutPanier;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Le panier courant d'un acheteur. Un seul ouvert a la fois, par index unique partiel. */
@Table("panier")
public class Panier extends BaseEntity {

    @Column("auth_user_id")
    private String authUserId;

    private StatutPanier statut = StatutPanier.OUVERT;

    public String getAuthUserId() {
        return authUserId;
    }

    public void setAuthUserId(String authUserId) {
        this.authUserId = authUserId;
    }

    public StatutPanier getStatut() {
        return statut;
    }

    public void setStatut(StatutPanier statut) {
        this.statut = statut;
    }
}
