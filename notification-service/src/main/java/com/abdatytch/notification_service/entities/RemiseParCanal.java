package com.abdatytch.notification_service.entities;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.CompteRenduDeRemise;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ce qui est arrive a UN canal, pour une notification.
 *
 * <p>Une ligne par canal essaye : une demande visant quatre canaux en produit
 * quatre. Un statut unique par notification obligerait a choisir lequel des
 * quatre il decrit, et la question « ce code est-il parti par SMS ? » resterait
 * sans reponse.
 *
 * <p>{@link Persistable} pour la meme raison que {@link Notification} :
 * identifiant genere par nous, donc Spring Data choisirait un {@code UPDATE}
 * sur une ligne inexistante.
 */
@Table("remise_par_canal")
public class RemiseParCanal implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("notification_id")
    private UUID notificationId;

    @Column("canal")
    private String canal;

    @Column("etat")
    private String etat;

    /** Motif d'un echec, SANS donnee personnelle : ni adresse, ni numero. */
    @Column("motif")
    private String motif;

    @Column("tentatives")
    private Short tentatives;

    @Column("essayee_le")
    private LocalDateTime essayeeLe;

    @org.springframework.data.annotation.Transient
    private boolean nouvelle = true;

    protected RemiseParCanal() {
    }

    public static RemiseParCanal de(UUID notificationId, CanalDeNotification canal,
                                    CompteRenduDeRemise.Etat etat, String motif) {
        RemiseParCanal r = new RemiseParCanal();
        r.id = UUID.randomUUID();
        r.notificationId = notificationId;
        r.canal = canal.name();
        r.etat = etat.name();
        r.motif = motif;
        r.tentatives = 1;
        r.essayeeLe = LocalDateTime.now();
        r.nouvelle = true;
        return r;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return nouvelle;
    }

    public RemiseParCanal dejaEnregistree() {
        this.nouvelle = false;
        return this;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public CanalDeNotification getCanal() {
        return CanalDeNotification.valueOf(canal);
    }

    public CompteRenduDeRemise.Etat getEtat() {
        return CompteRenduDeRemise.Etat.valueOf(etat);
    }

    public String getMotif() {
        return motif;
    }

    public Short getTentatives() {
        return tentatives;
    }

    public LocalDateTime getEssayeeLe() {
        return essayeeLe;
    }

    /**
     * Enregistre une nouvelle tentative sur la ligne existante.
     *
     * <p>Incrementer plutot que creer une ligne : un canal en panne qui
     * reessaie toutes les minutes ferait grossir la table sans borne. La
     * contrainte d'unicite (notification, canal) l'impose de toute facon.
     */
    public RemiseParCanal nouvelleTentative(CompteRenduDeRemise.Etat etat, String motif) {
        this.etat = etat.name();
        this.motif = motif;
        this.tentatives = (short) (this.tentatives == null ? 1 : this.tentatives + 1);
        this.essayeeLe = LocalDateTime.now();
        this.nouvelle = false;
        return this;
    }

    public CompteRenduDeRemise.RemiseParCanal versCompteRendu() {
        return new CompteRenduDeRemise.RemiseParCanal(getCanal(), getEtat(), motif);
    }
}
