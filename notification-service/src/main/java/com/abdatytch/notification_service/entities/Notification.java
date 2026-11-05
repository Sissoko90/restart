package com.abdatytch.notification_service.entities;

import com.abdatytch.commons.notification.DemandeDeNotification;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une notification demandee, et la boite de reception applicative.
 *
 * <h2>Pourquoi {@link Persistable}</h2>
 *
 * Parce que l'identifiant est genere par NOUS et non par la base. Sans
 * {@code Persistable}, Spring Data R2DBC decide « nouvelle ligne ou mise a
 * jour » en regardant si l'identifiant est nul : un identifiant deja pose le
 * ferait donc choisir un {@code UPDATE} sur une ligne qui n'existe pas, et
 * l'insertion n'aurait jamais lieu — sans erreur, puisque l'{@code UPDATE}
 * reussit en affectant zero ligne.
 *
 * <p>C'est un piege deja rencontre dans ce depot, sur la migration
 * d'auth-service vers R2DBC. Il ne se manifeste pas a la compilation et pas
 * davantage dans un test qui ne relit pas la ligne.
 */
@Table("notification")
public class Notification implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column("destinataire_id")
    private String destinataireId;

    @Column("modele")
    private String modele;

    /**
     * Les variables du gabarit, en JSON.
     *
     * <p>Une chaine et non une {@code Map} : le pilote R2DBC PostgreSQL ne
     * convertit pas une {@code Map} en {@code jsonb} sans convertisseur dedie.
     * La serialisation est donc faite explicitement a la frontiere, ce qui a
     * aussi l'avantage de rendre visible ce qui est ecrit en base.
     */
    @Column("variables")
    private String variables;

    @Column("urgence")
    private String urgence;

    @Column("service_emetteur")
    private String serviceEmetteur;

    @Column("demande_id")
    private UUID demandeId;

    @Column("lue_le")
    private LocalDateTime lueLe;

    @Column("creee_le")
    private LocalDateTime creeeLe;

    /**
     * Vrai tant que la ligne n'a pas ete ecrite.
     *
     * <p>{@code @Transient} : ce drapeau ne va pas en base. Il sert uniquement a
     * dire a Spring Data d'emettre un {@code INSERT}.
     */
    @org.springframework.data.annotation.Transient
    private boolean nouvelle = true;

    protected Notification() {
    }

    public static Notification depuis(DemandeDeNotification demande, String variablesJson) {
        Notification n = new Notification();
        n.id = UUID.randomUUID();
        n.destinataireId = demande.destinataireId();
        n.modele = demande.modele();
        n.variables = variablesJson;
        n.urgence = demande.urgence().name();
        n.serviceEmetteur = demande.serviceEmetteur();
        n.demandeId = demande.demandeId();
        n.creeeLe = LocalDateTime.now();
        n.nouvelle = true;
        return n;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return nouvelle;
    }

    /**
     * A appeler apres lecture depuis la base, pour que le prochain
     * enregistrement soit une mise a jour.
     */
    public Notification dejaEnregistree() {
        this.nouvelle = false;
        return this;
    }

    /**
     * Marque la notification comme lue, et seulement si elle ne l'est pas deja.
     *
     * <p>Ne pas ecraser un horodatage existant : la date du PREMIER acces est
     * l'information utile. La reecrire a chaque ouverture ferait croire que la
     * notification vient d'etre vue.
     */
    public Notification marquerLue() {
        if (this.lueLe == null) {
            this.lueLe = LocalDateTime.now();
        }
        return this;
    }

    public String getDestinataireId() {
        return destinataireId;
    }

    public String getModele() {
        return modele;
    }

    public String getVariables() {
        return variables;
    }

    public String getUrgence() {
        return urgence;
    }

    public String getServiceEmetteur() {
        return serviceEmetteur;
    }

    public UUID getDemandeId() {
        return demandeId;
    }

    public LocalDateTime getLueLe() {
        return lueLe;
    }

    public LocalDateTime getCreeeLe() {
        return creeeLe;
    }

    /**
     * Efface le contenu sans supprimer la ligne (RGPD).
     *
     * <p>Meme regle que partout dans ce depot : effacer n'est pas supprimer la
     * ligne. Supprimer casserait la trace qu'une notification a eu lieu, dont
     * l'audit a besoin ; vider les variables retire la donnee personnelle. Le
     * gabarit reste, parce que son nom ne designe personne.
     */
    public Notification anonymiser() {
        this.destinataireId = "efface";
        this.variables = "{}";
        this.nouvelle = false;
        return this;
    }
}
