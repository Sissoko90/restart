package com.abdatytch.media_service.enums;

/**
 * Rôles utilisateur synchronisés avec auth-service et user-service.
 *
 * <h2>Les trois valeurs qui manquaient</h2>
 *
 * Ce module n'en connaissait que <b>neuf</b>, alors qu'auth-service — qui émet
 * les jetons — en déclare <b>douze</b> : {@code TENANT}, {@code DEVELOPER} et
 * {@code SUPER_ADMIN} étaient absents.
 *
 * <p>Ce n'était pas théorique. {@code DocumentController} lit le rôle du jeton
 * par {@code UserRole.valueOf(role.replace("ROLE_", ""))} : un
 * <b>super administrateur qui envoyait un document levait une
 * {@code IllegalArgumentException}</b>. Même effet pour un locataire ou un
 * développeur tiers.
 *
 * <p>La liste est tenue en parallèle de celle d'auth-service, délibérément :
 * les rôles restent du code parce que c'est là qu'ils servent —
 * {@code @PreAuthorize}, {@code hasRole}, les {@code pathMatchers}. Toute valeur
 * ajoutée là-bas doit donc être reportée ici. L'ordre et les noms sont alignés
 * sur {@code auth_service.enums.UserRole} pour que la comparaison soit
 * immédiate.
 */
public enum UserRole {
    USER,           // Utilisateur standard
    DRIVER,         // Chauffeur/Livreur - Documents: Permis, Assurance, Carte grise
    DELIVERY,       // Livreur moto - Documents: Permis moto, Assurance moto, Carte grise moto
    MERCHANT,       // Commerçant - Documents: RCCM, NIF, Patente
    PHARMACY,       // Pharmacie - Documents: Licence pharmacie, Diplôme pharmacien
    RESTAURANT,     // Restaurant - Documents: Licence sanitaire, Patente
    SUPERMARKET,    // Supermarché - Documents: RCCM, NIF, Licence commerce
    TENANT,         // Locataire - Aucune exigence documentaire propre a ce module
    DISPATCHER,     // Répartiteur - Documents: Contrat de travail
    DEVELOPER,      // Developpeur tiers - acces API, aucun document requis
    ADMIN,          // Administrateur - Aucun document requis
    SUPER_ADMIN;    // Responsable technique - herite d'ADMIN a l'emission du jeton

    /**
     * Retourne l'authority Spring Security pour ce rôle
     */
    public String getAuthority() {
        return "ROLE_" + this.name();
    }

    /**
     * Retourne une description lisible du rôle
     */
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
            case ADMIN -> "Administrateur";
            case SUPER_ADMIN -> "Super administrateur — responsable technique";
        };
    }
}
