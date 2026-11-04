package com.abdatytch.auth_service.entities;

import com.abdatytch.auth_service.enums.UserRole;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table("users")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {
    
    @Column("phone_number")
    private String phoneNumber;
    
    @Column("country_code")
    private String countryCode;
    
    @Column("email")
    private String email;
    
    @Column("first_name")
    private String firstName;
    
    @Column("last_name")
    private String lastName;
    
    @Column("password_hash")
    private String passwordHash;
    
    @Column("is_phone_verified")
    @Builder.Default
    private Boolean isPhoneVerified = false;
    
    @Column("is_email_verified")
    @Builder.Default
    private Boolean isEmailVerified = false;
    
    @Column("is_active")
    @Builder.Default
    private Boolean isActive = true;
    
    @Column("is_locked")
    @Builder.Default
    private Boolean isLocked = false;
    
    @Column("failed_login_attempts")
    @Builder.Default
    private Integer failedLoginAttempts = 0;
    
    @Column("locked_until")
    private LocalDateTime lockedUntil;
    
    @Column("last_login")
    private LocalDateTime lastLogin;
    /**
     * Secret du second facteur, encode en Base32.
     *
     * <p>Present uniquement sur les comptes d'administration. Remis une seule
     * fois, a l'enrolement : le reservir rouvrirait la faille corrigee dans
     * interface-service, ou le secret etait recuperable sans authentification.
     */
    @Column("totp_secret")
    private String totpSecret;

    /**
     * Vrai une fois l'enrolement effectue. Le secret n'est alors plus remis.
     *
     * <h3>Pourquoi {@code @Builder.Default} et pas seulement l'affectation</h3>
     *
     * Sans cette annotation, Lombok <b>ignore</b> l'expression d'initialisation
     * dans le builder : {@code User.builder()...build()} laissait ce champ a
     * {@code null}, et non a {@code false}. Les quatre sites qui construisent un
     * compte ainsi — l'inscription (deux chemins), l'inscription OAuth2 et
     * l'attribution de role — ne le posent nulle part.
     *
     * <p>Or la colonne est {@code BOOLEAN NOT NULL DEFAULT FALSE}
     * ({@code V3__admin_totp.sql}). Et PostgreSQL n'applique un DEFAULT que
     * lorsque la colonne est <b>omise</b> de l'INSERT, pas lorsqu'on y envoie
     * {@code NULL} explicitement — ce que fait Spring Data R2DBC, qui ecrit
     * toutes les proprietes mappees.
     *
     * <p>La consequence a l'execution n'a pas pu etre mesuree ici : les tests de
     * ce module n'ouvrent aucune base, et aucune instance ne tourne. La
     * correction est juste independamment de cela — le champ vaut maintenant ce
     * que sa declaration annonce — mais le dire autrement serait affirmer plus
     * que ce qui a ete verifie.
     *
     * <p>Cote lecture, rien ne changeait : les neuf lectures passent par
     * {@code Boolean.TRUE.equals(...)}, qui traite {@code null} comme
     * « desactive ». C'est precisement ce qui rendait l'ecart silencieux.
     */
    @Column("totp_enabled")
    @Builder.Default
    private Boolean totpEnabled = false;

    @Column("role")
    @Builder.Default
    private UserRole role = UserRole.USER;

    /**
     * Prévenir par courriel à chaque connexion aboutie.
     *
     * <h2>Pourquoi celle-ci est une préférence, et les autres alertes non</h2>
     *
     * Une connexion est fréquente et ordinaire. Un courriel par connexion
     * atteint un volume dont le résultat connu est que les gens cessent de les
     * lire, puis les désactivent — une alerte qu'on ignore protège moins qu'une
     * alerte qu'on a choisie.
     *
     * <p>Les alertes de changement de mot de passe et d'adresse sont elles
     * <b>sans condition</b> : rares, toujours notables, et sur le chemin
     * obligatoire de tout détournement de compte durable.
     *
     * <p>Faux par défaut. Son activation exige une adresse vérifiée
     * ({@code VAL_6008}) : un réglage affiché comme actif qui n'enverrait jamais
     * rien ferait croire à la personne qu'elle est surveillée.
     */
    @Column("alerte_nouvelle_connexion")
    @Builder.Default
    private Boolean alerteNouvelleConnexion = false;

    /**
     * Nouveau numéro demandé, <b>pas encore prouvé</b>.
     *
     * <p>Le compte garde son numéro actuel tant que celui-ci n'est pas confirmé
     * par un code envoyé au nouveau numéro. C'est ce qui rend l'opération
     * annulable par l'inaction : une faute de frappe n'enferme personne hors de
     * la récupération par SMS.
     */
    @Column("numero_en_attente")
    private String numeroEnAttente;

    /**
     * Indicatif du numéro en attente, tel que le client l'a envoyé.
     *
     * <p>Conservé plutôt que déduit : {@code PhoneNumbers.prefixeIndicatif}
     * coupe un nombre fixe de caractères, défaut connu de ce dépôt. Le déduire à
     * la confirmation le reproduirait sur chaque compte qui change de numéro.
     */
    @Column("indicatif_en_attente")
    private String indicatifEnAttente;

    /**
     * Échéance de la demande de changement.
     *
     * <p>Une attente sans fin se ferait activer par un code intercepté bien plus
     * tard. La ligne n'est pas effacée à l'expiration : c'est la lecture qui
     * refuse, ce qui évite une tâche de nettoyage à surveiller.
     */
    @Column("numero_en_attente_jusqu_a")
    private LocalDateTime numeroEnAttenteJusquA;

    /** Une demande de changement de numéro est-elle encore recevable ? */
    public boolean aUneDemandeDeNumeroEnCours(LocalDateTime maintenant) {
        return numeroEnAttente != null
                && numeroEnAttenteJusquA != null
                && numeroEnAttenteJusquA.isAfter(maintenant);
    }
}
