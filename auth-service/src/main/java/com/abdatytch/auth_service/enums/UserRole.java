package com.abdatytch.auth_service.enums;

import java.util.Set;

/**

 * Rôles de la plateforme.
 *
 * <p>Trois familles s'y mêlent, et les confondre a déjà conduit à des
 * autorisations trop larges :
 *
 * <ul>
 * <li><b>rôles métier</b> — {@link #USER} et les rôles de profession. Ils
 * décrivent ce qu'une personne <i>est</i> sur la plateforme, pas ce
 * qu'elle a le droit d'administrer ;</li>
 * <li><b>rôle d'accès API</b> — {@link #DEVELOPER}, pour un tiers qui consomme
 * l'API. Ce n'est pas un rôle d'administration ;</li>
 * <li><b>rôles d'administration</b> — {@link #ADMIN} et {@link #SUPER_ADMIN},
 * dont la frontière est décrite ci-dessous.</li>
 * </ul>
 *
 * <h2>ADMIN et SUPER_ADMIN</h2>
 *
 * <table border="1">
 * <caption>Répartition des pouvoirs</caption>
 * <tr><th></th><th>ADMIN</th><th>SUPER_ADMIN</th></tr>
 * <tr><td>Attribuer et retirer des rôles</td><td></td><td></td></tr>
 * <tr><td>Débloquer un compte, consulter les journaux</td><td></td><td></td></tr>
 * <tr><td>Mettre le site en maintenance</td><td></td><td></td></tr>
 * <tr><td>Faire tourner les clés de signature JWT</td><td></td><td></td></tr>
 * <tr><td>Déclencher un effacement RGPD administratif</td><td></td><td></td></tr>
 * <tr><td>Modifier les paramètres système</td><td></td><td></td></tr>
 * </table>
 *
 * <p>{@code SUPER_ADMIN} <b>hérite</b> de tout ce que peut {@code ADMIN}.
 * L'héritage est résolu <b>à l'émission du jeton</b> par {@link #expand} : le
 * jeton d'un super administrateur porte les deux rôles.
 *
 * <p>C'est délibéré, et préférable à une {@code RoleHierarchy} déclarée dans
 * chaque service. Huit services consomment ces jetons ; une hiérarchie
 * configurée service par service serait oubliée dans l'un d'eux, et le défaut
 * serait invisible — l'endpoint continuerait de fonctionner pour {@code ADMIN},
 * et refuserait {@code SUPER_ADMIN} sans que rien ne le signale. Résoudre à
 * l'émission met les services d'accord sans qu'ils aient à connaître la
 * hiérarchie.
 *
 * <h2>Pourquoi SUPER_ADMIN n'est pas attribuable par l'API</h2>
 *
 * Il n'existe que par le seeder, au déploiement. Sans cette règle, un
 * {@code ADMIN} compromis pourrait se promouvoir lui-même — le pouvoir de
 * distribuer les rôles inclurait alors celui de s'octroyer le rôle le plus
 * élevé, et la distinction entre les deux ne servirait plus à rien.
 *
 * <p>Voir {@code docs/ROLES.md}.
 */
public enum UserRole {

    // ----- Rôles métier -----
    USER,
    DRIVER,
    DELIVERY,
    MERCHANT,
    PHARMACY,
    RESTAURANT,
    SUPERMARKET,
    TENANT,
    DISPATCHER,

    /**

     * Accès à l'API pour un tiers.
     *
     * <p>N'accorde <b>aucun</b> droit d'administration : c'est un droit de
     * consommation de l'API, soumis aux quotas comme les autres.
     */
    DEVELOPER,

    // ----- Rôles d'administration -----
    /** Gestion des rôles et des comptes. Aucun pouvoir technique. */
    ADMIN,

    /**

     * Responsable technique de la plateforme. Hérite d'{@code ADMIN}.
     *
     * <p>Non attribuable par l'API : uniquement par le seeder au déploiement.
     */
    SUPER_ADMIN;

    /**

     * Rôles qu'aucun appel d'API ne peut attribuer.
     *
     * <p>Le contrôle est fait par {@code UserRoleManagementServiceImpl}, et non
     * seulement par une annotation : une règle de sécurité vérifiée à un seul
     * endroit est une règle qu'on contourne en ajoutant un second point
     * d'entrée.
     */
    private static final Set<UserRole> NON_ATTRIBUABLES = Set.of(SUPER_ADMIN);

    /** Authority Spring Security correspondante. */
    public String getAuthority() {
        return "ROLE_" + this.name();
    }

    /**

     * Développe un rôle en l'ensemble des rôles qu'il confère.
     *
     * <p>Appelé à l'émission du jeton, de sorte que les services aval n'ont
     * aucune hiérarchie à connaître : ils voient directement les rôles
     * effectifs.
     *
     * @return le rôle lui-même, et ceux dont il hérite
     */
    public Set<UserRole> expand() {
        return this == SUPER_ADMIN ? Set.of(SUPER_ADMIN, ADMIN) : Set.of(this);
    }

    /** @return {@code true} si ce rôle ne peut être accordé que par le seeder. */
    public boolean estAttribuableParApi() {
        return !NON_ATTRIBUABLES.contains(this);
    }

    /** @return {@code true} pour {@link #ADMIN} et {@link #SUPER_ADMIN}. */
    public boolean estRoleDAdministration() {
        return this == ADMIN || this == SUPER_ADMIN;
    }

    /** Description lisible, destinée aux interfaces d'administration. */
    public String getDescription() {
        return switch (this) {
            case USER -> "Utilisateur standard";
            case DRIVER -> "Chauffeur/Livreur";
            case DELIVERY -> "Livreur moto";
            case MERCHANT -> "Commerçant";
            case PHARMACY -> "Pharmacie";
            case RESTAURANT -> "Restaurant";
            case SUPERMARKET -> "Supermarché";
            case TENANT -> "Locataire";
            case DISPATCHER -> "Répartiteur";
            case DEVELOPER -> "Développeur tiers — accès à l'API";
            case ADMIN -> "Administrateur — rôles et comptes";
            case SUPER_ADMIN -> "Super administrateur — responsable technique";
        };
    }
}
