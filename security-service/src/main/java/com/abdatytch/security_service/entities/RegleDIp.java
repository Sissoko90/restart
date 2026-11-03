package com.abdatytch.security_service.entities;

import com.abdatytch.security_service.enums.MotifDeRegleIp;
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
 * Une adresse bloquee, avec son motif et son echeance.
 *
 * <h2>Ce que remplace cette table</h2>
 *
 * {@code IpBlacklistWebFilter} tenait une liste dans Redis : une adresse, une
 * duree de vie d'un jour codee en dur, et rien d'autre. Ni motif, ni auteur, ni
 * possibilite de bloquer durablement, ni moyen de consulter ou de lever un
 * blocage. Une adresse bloquee par erreur se debloquait d'elle-meme au bout
 * d'un jour — ce qui est le seul point positif de cette forme.
 *
 * <p>Le filtre reste l'<b>applicateur</b> : il continue de lire Redis, sans
 * appeler ce service. Ce qui change est que Redis est desormais <b>alimente</b>
 * par cette table, qui porte la decision et sa trace.
 *
 * <h2>Pourquoi la base ET Redis</h2>
 *
 * Ce n'est pas deux magasins pour une meme decision, c'est une decision et son
 * cache. La base est la source : elle porte le motif, l'auteur, l'historique.
 * Redis ne porte que la reponse a « cette adresse est-elle bloquee », parce que
 * le gateway doit y repondre a chaque requete sans toucher la base.
 *
 * <p>La distinction tient a une regle : <b>rien ne modifie Redis sans passer
 * par la base</b>. Le defaut qu'a connu ce depot venait de l'inverse — deux
 * magasins ecrits independamment, dont l'un n'etait jamais lu.
 */
@Table("regle_ip")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegleDIp implements Persistable<String> {

    /**
     * Identifiant de la regle.
     *
     * <p>{@link Persistable} est implemente parce que la cle est assignee par
     * l'application : sans cela, Spring Data R2DBC emet un UPDATE pour une ligne
     * jamais inseree, et le {@code save()} est silencieusement sans effet. Le
     * piege est deja documente sur {@code SystemSetting}.
     */
    @Id
    private String id;

    /**
     * Adresse IP litterale, IPv4 ou IPv6.
     *
     * <p><b>Pas un prefixe CIDR</b>, contrairement a ce que cette javadoc
     * annoncait. L'applicateur — {@code IpBlacklistWebFilter} — repond par un
     * {@code hasKey} sur {@code security:ip:blacklist:<adresse>}, donc par une
     * correspondance de cle <b>exacte</b> : une ligne portant
     * {@code 41.223.0.0/16} serait enregistree, publiee dans Redis, et ne
     * correspondrait a aucune requete. Le blocage aurait ete confirme a son
     * auteur sans jamais s'appliquer.
     *
     * <p>{@code RegleDIpServiceImpl} refuse donc le prefixe, avec un message qui
     * dit pourquoi plutot que de laisser croire a une faute de frappe. Porter le
     * CIDR demanderait de changer l'applicateur, qui est sur le chemin de chaque
     * requete : evaluer N prefixes par requete, ou tenir un arbre de prefixes
     * dans Redis. C'est une decision de conception, pas un oubli.
     *
     * <p>Stockee sous sa forme <b>canonique</b>, celle que produit
     * {@code AdresseIpLitterale.canonique} — donc exactement celle que le
     * gateway forme depuis l'adresse de la socket. Sans cette normalisation,
     * {@code 41.223.10.5} et {@code 041.223.010.005} designent la meme machine
     * et produisent deux lignes dont une seule est consultee.
     */
    @Column("adresse")
    private String adresse;

    @Column("motif")
    private MotifDeRegleIp motif;

    /**
     * Precision libre, facultative.
     *
     * <p>Complete le motif enumere sans le remplacer : le motif sert aux
     * metriques et aux decisions, ce champ sert a la personne qui lira la regle
     * dans six mois.
     */
    @Column("commentaire")
    private String commentaire;

    /**
     * Echeance, ou {@code null} pour une regle permanente.
     *
     * <p>Une regle permanente n'est acceptee que pour un motif qui n'est pas une
     * detection automatique : voir
     * {@link MotifDeRegleIp#estUneDetectionAutomatique()}. Un faux positif
     * permanent enferme une adresse pour toujours, et la victime ne peut meme
     * plus se connecter pour le signaler.
     */
    @Column("expire_le")
    private Instant expireLe;

    /**
     * Qui a pose la regle.
     *
     * <p>L'identifiant de l'administrateur, ou le nom du filtre pour une
     * detection automatique. Sans cette colonne, « qui a bloque ce client »
     * reste sans reponse.
     */
    @Column("pose_par")
    private String posePar;

    @Column("cree_le")
    private Instant creeLe;

    /**
     * Levee de la regle, sans suppression de la ligne.
     *
     * <p>« Effacer n'est pas supprimer la ligne » vaut aussi ici : savoir qu'une
     * adresse a ete bloquee puis debloquee, par qui et quand, est exactement ce
     * qu'un journal d'audit doit pouvoir montrer. Supprimer la ligne effacerait
     * la decision avec elle.
     */
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

    /** Marque l'entite comme chargee depuis la base. */
    public RegleDIp dejaEnBase() {
        this.nouvelle = false;
        return this;
    }

    /** Vrai si la regle bloque effectivement a cet instant. */
    public boolean estActive(Instant maintenant) {
        if (leveeLe != null) {
            return false;
        }
        return expireLe == null || expireLe.isAfter(maintenant);
    }
}
