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
 * Un code à usage unique, remplaçant le second facteur.
 *
 * <h2>Pourquoi ils sont devenus nécessaires</h2>
 *
 * Le second facteur était réservé aux comptes d'administration, et sa seule
 * sortie était {@code retirer()} par un {@code SUPER_ADMIN}. Tenable pour une
 * poignée d'administrateurs qu'on connaît.
 *
 * <p>Depuis que l'enrôlement est accessible en libre-service, un client peut
 * l'activer — et perdre son téléphone. La sortie par {@code SUPER_ADMIN}
 * devient alors intenable : une intervention humaine par client bloqué, et
 * surtout un support obligé de juger de l'identité de quelqu'un qui ne peut
 * plus la prouver. C'est le chemin que prennent les attaques par ingénierie
 * sociale.
 *
 * <p>Les codes de secours déplacent cette preuve <b>avant</b> l'incident : ils
 * sont remis à l'enrôlement, quand la personne est encore en mesure de prouver
 * qui elle est.
 *
 * <h2>Pourquoi BCrypt ici, et SHA-256 pour le jeton d'appareil</h2>
 *
 * La différence est délibérée. Un jeton d'appareil fait 32 octets aléatoires :
 * aucun dictionnaire à ralentir, donc SHA-256 suffit et évite 241 ms par
 * connexion.
 *
 * <p>Un code de secours est <b>court et lisible</b> — il doit pouvoir être
 * recopié depuis un papier. Son espace est minuscule devant 32 octets, et un
 * SHA-256 se casse par force brute en quelques secondes. BCrypt est donc
 * nécessaire, et son coût acceptable : un code de secours sert quelques fois
 * dans la vie d'un compte, pas à chaque connexion.
 */
@Table("code_de_secours")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeDeSecours extends BaseEntity {

    @Column("user_id")
    private String userId;

    /** Empreinte BCrypt. Jamais le code. */
    @Column("empreinte")
    private String empreinte;

    /**
     * Quatre premiers caractères du code, en clair.
     *
     * <p>Ce n'est pas une fuite : quatre caractères sur dix laissent un espace
     * encore trop grand pour être devinné, et BCrypt protège le reste. En
     * échange, la personne peut cocher sur son papier les codes déjà utilisés —
     * sans cet indice, « code consommé le 3 mars » ne lui apprend rien
     * d'exploitable.
     */
    @Column("debut")
    private String debut;

    /**
     * Consommation. La ligne n'est pas supprimée.
     *
     * <p>Montrer à la personne <b>quels</b> codes ont servi, quand et depuis
     * où, est ce qui lui permet de repérer un usage qu'elle ne reconnaît pas.
     * Une suppression effacerait précisément cet indice.
     */
    @Column("consomme_le")
    private LocalDateTime consommeLe;

    @Column("consomme_ip")
    private String consommeIp;

    /**
     * Invalidation par régénération.
     *
     * <p><b>Distinct</b> de la consommation : un code invalidé n'a jamais servi,
     * il a été remplacé. Confondre les deux ferait croire à la personne qu'un
     * code a été utilisé alors qu'elle en a simplement demandé de nouveaux — et
     * lui ferait soupçonner une intrusion inexistante.
     */
    @Column("invalide_le")
    private LocalDateTime invalideLe;

    /** Ce code peut-il encore servir ? */
    public boolean estUtilisable() {
        return consommeLe == null && invalideLe == null;
    }
}
