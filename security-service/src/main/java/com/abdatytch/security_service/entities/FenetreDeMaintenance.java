package com.abdatytch.security_service.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Une fenetre de maintenance : toute la plateforme, ou un seul service.
 *
 * <h2>Le defaut que cette table corrige</h2>
 *
 * {@code setting-service} declarait {@code system.maintenance-mode}, le
 * journalisait en {@code WARN} avec un message dedie, et <b>rien ne le lisait</b>.
 * Verifie : la cle n'apparait que dans {@code setting-service} lui-meme, et le
 * gateway ne l'interroge jamais. Un administrateur pouvait donc « mettre le site
 * en maintenance », voir la confirmation et les journaux, et le trafic
 * continuait de passer.
 *
 * <p>C'est le motif le plus frequent de ce depot : une protection ecrite,
 * confirmee a son auteur, et jamais atteinte.
 *
 * <h2>Pourquoi une table et non un booleen</h2>
 *
 * Un booleen ne permet ni de couper un seul service, ni d'annoncer une duree, ni
 * de savoir qui a coupe. Vos trois demandes — message, duree, service
 * specifique sans interrompre les autres — exigent des lignes.
 *
 * <p>Plusieurs fenetres peuvent coexister : une globale et une par service. La
 * plus restrictive s'applique, ce qui evite qu'activer une maintenance
 * ciblee leve par accident une maintenance globale.
 */
@Table("fenetre_maintenance")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FenetreDeMaintenance implements Persistable<String> {

    /**
     * Valeur de {@link #service} designant la plateforme entiere.
     *
     * <p>Une valeur reservee plutot qu'un {@code null} : un {@code null} en base
     * se compare mal, et {@code service = NULL} dans une requete est une source
     * classique de ligne jamais trouvee.
     */
    public static final String TOUTE_LA_PLATEFORME = "*";

    @Id
    private String id;

    /**
     * Nom du service coupe, ou {@link #TOUTE_LA_PLATEFORME}.
     *
     * <p>Le nom tel que Consul le connait — {@code auth-service} — parce que
     * c'est celui que le gateway utilise pour router. Un nom libre obligerait a
     * une table de correspondance, donc a un endroit de plus ou se tromper.
     */
    @Column("service")
    private String service;

    /**
     * Message affiche a l'appelant.
     *
     * <p>Obligatoire. Une maintenance sans message rend un refus que personne ne
     * sait interpreter : le client ne distingue pas une coupure voulue d'une
     * panne, et appelle le support pour le dire.
     */
    @Column("message")
    private String message;

    @Column("debute_le")
    private Instant debuteLe;

    /**
     * Fin annoncee, ou {@code null} pour une coupure sans terme.
     *
     * <p>Une fin {@code null} est permise mais signalee a l'appelant : « revenez
     * plus tard » est moins utile que « revenez a 14h », et une maintenance sans
     * terme est le genre de chose qu'on oublie d'arreter.
     */
    @Column("finit_le")
    private Instant finitLe;

    @Column("activee_par")
    private String activeePar;

    @Column("cree_le")
    private Instant creeLe;

    /** Levee anticipee, sans suppression de la ligne : voir {@link RegleDIp}. */
    @Column("levee_le")
    private Instant leveeLe;

    @Column("levee_par")
    private String leveePar;

    @Transient
    @Builder.Default
    private boolean nouvelle = true;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return nouvelle;
    }

    public FenetreDeMaintenance dejaEnBase() {
        this.nouvelle = false;
        return this;
    }

    /**
     * Vrai si la fenetre coupe effectivement a cet instant.
     *
     * <p>Une fenetre dont le debut est dans le futur ne coupe pas encore : c'est
     * ce qui permet d'annoncer une maintenance a l'avance sans la declencher.
     */
    public boolean estActive(Instant maintenant) {
        if (leveeLe != null) {
            return false;
        }
        if (debuteLe != null && debuteLe.isAfter(maintenant)) {
            return false;
        }
        return finitLe == null || finitLe.isAfter(maintenant);
    }

    public boolean concerneToutePlateforme() {
        return TOUTE_LA_PLATEFORME.equals(service);
    }
}
