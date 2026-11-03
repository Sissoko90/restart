package com.abdatytch.auth_service.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Un appareil dispensé du second facteur.
 *
 * <h2>Pourquoi cette entité vit dans auth-service et non dans security-service</h2>
 *
 * Elle est consultée à <b>chaque connexion</b> : la décision « faut-il demander
 * un code » en dépend. La placer dans security-service imposerait un appel
 * réseau sur le chemin de connexion, et une panne de ce service empêcherait
 * toute connexion.
 *
 * <p>Le cache Redis, qui résout ce problème pour la politique, ne s'applique pas
 * ici : la politique tient en quinze valeurs partagées, l'état par compte se
 * compte en millions de lignes. security-service garde la politique qui
 * gouverne ces appareils — second facteur obligatoire ou non.
 *
 * <h2>Le jeton n'est jamais stocké en clair</h2>
 *
 * Seule son empreinte SHA-256 l'est. Un appareil de confiance dispense du
 * second facteur : qui lit la base pourrait sinon se connecter sans code
 * pendant toute la validité du jeton.
 *
 * <p>SHA-256 et non BCrypt, délibérément : le jeton fait 32 octets aléatoires,
 * donc il n'y a aucune attaque par dictionnaire à ralentir. BCrypt coûterait les
 * 241 ms mesurés dans ce dépôt, à chaque connexion, pour aucune protection
 * supplémentaire.
 */
@Table("appareil_de_confiance")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppareilDeConfiance extends BaseEntity {

    @Column("user_id")
    private String userId;

    /** SHA-256 du jeton, en hexadécimal. Jamais le jeton lui-même. */
    @Column("empreinte_jeton")
    private String empreinteJeton;

    /**
     * Nom lisible.
     *
     * <p>Fourni par la personne, ou déduit de l'User-Agent à défaut. Sans nom,
     * une liste d'appareils ne permet pas de reconnaître celui qu'on veut
     * révoquer : « appareil 3 » ne désigne rien.
     */
    @Column("nom")
    private String nom;

    // ------------------------------------------------------------------
    // Circonstances de la confiance, FIGÉES
    // ------------------------------------------------------------------
    // Elles ne sont pas rafraîchies à chaque usage, et c'est voulu : la question
    // posée à la personne est « reconnaissez-vous CETTE confiance », donc les
    // circonstances dans lesquelles elle a été accordée. Les rafraîchir
    // effacerait l'indice qui permet de repérer une confiance accordée par
    // quelqu'un d'autre.

    @Column("navigateur")
    private String navigateur;

    @Column("systeme")
    private String systeme;

    @Column("type_appareil")
    private String typeAppareil;

    @Column("ip_accordee")
    private String ipAccordee;

    @Column("pays_accorde")
    private String paysAccorde;

    @Column("accorde_le")
    private LocalDateTime accordeLe;

    /** Dernier usage : distingue un appareil utilisé d'un appareil oublié. */
    @Column("utilise_le")
    private LocalDateTime utiliseLe;

    /**
     * Échéance, <b>obligatoire</b>.
     *
     * <p>Une confiance perpétuelle transforme un vol d'appareil en accès
     * définitif sans second facteur, et personne ne pense à faire le ménage
     * dans une liste qui n'expire pas.
     */
    @Column("expire_le")
    private LocalDateTime expireLe;

    @Column("revoque_le")
    private LocalDateTime revoqueLe;

    /** Qui a révoqué : la personne, un administrateur, ou l'expiration. */
    @Column("revoque_par")
    private String revoquePar;

    /**
     * La confiance s'applique-t-elle à cet instant ?
     *
     * <p>Calculée et non stockée : un appareil cesse d'être de confiance au
     * passage de son échéance, sans que personne écrive quoi que ce soit. Une
     * colonne « actif » demanderait une tâche qui la met à jour, donc un second
     * mécanisme susceptible de divergence.
     */
    public boolean estDeConfiance(LocalDateTime maintenant) {
        if (revoqueLe != null) {
            return false;
        }
        return expireLe != null && expireLe.isAfter(maintenant);
    }
}
