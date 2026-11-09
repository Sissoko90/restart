package com.abdatytch.notification_service.entities;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un appareil joignable par notification push.
 *
 * <h2>Un APPAREIL, et non une personne</h2>
 *
 * Une meme personne en a autant qu'elle possede de telephones, de tablettes et
 * de navigateurs. Le canal push lit donc TOUS les jetons d'un destinataire et
 * pousse vers chacun ; il n'y a pas de « jeton principal ».
 *
 * <h2>Pas de {@code Persistable}</h2>
 *
 * Meme raison que {@link SurchargeDeModele} : cette entite n'est jamais ecrite
 * par {@code save()}. L'enregistrement passe par un
 * {@code INSERT ... ON CONFLICT DO UPDATE} explicite, qui tranche en une seule
 * instruction atomique au lieu de dependre d'un drapeau porte par l'objet.
 */
@Table("jeton_d_appareil")
public class JetonDAppareil {

    @Id
    @Column("id")
    private UUID id;

    /** Le {@code sub} du porteur. */
    @Column("destinataire_id")
    private String destinataireId;

    @Column("jeton")
    private String jeton;

    @Column("plateforme")
    private String plateforme;

    @Column("enregistre_le")
    private LocalDateTime enregistreLe;

    @Column("vu_le")
    private LocalDateTime vuLe;

    protected JetonDAppareil() {
    }

    /**
     * Construit un jeton en memoire.
     *
     * <p>Comme pour {@link SurchargeDeModele}, le chemin de production n'en a
     * pas besoin — l'ecriture passe par {@code enregistrer} et la lecture est
     * remplie par Spring Data. Elle existe pour que les tests fabriquent les
     * lignes que le depot rendrait, sans ouvrir les champs a tout le monde.
     */
    public static JetonDAppareil de(String destinataireId, String jeton, String plateforme) {
        JetonDAppareil appareil = new JetonDAppareil();
        appareil.id = UUID.randomUUID();
        appareil.destinataireId = destinataireId;
        appareil.jeton = jeton;
        appareil.plateforme = plateforme;
        appareil.enregistreLe = LocalDateTime.now();
        appareil.vuLe = appareil.enregistreLe;
        return appareil;
    }

    public UUID getId() {
        return id;
    }

    public String getDestinataireId() {
        return destinataireId;
    }

    public String getJeton() {
        return jeton;
    }

    public String getPlateforme() {
        return plateforme;
    }

    public LocalDateTime getEnregistreLe() {
        return enregistreLe;
    }

    public LocalDateTime getVuLe() {
        return vuLe;
    }
}
