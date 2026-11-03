package com.abdatytch.user_service.constants;

/**
 * Libellés de la surface d'administration.
 *
 * <p>La liste a fondu avec la réunion des deux services d'administration : les
 * trente-quatre libellés qui décrivaient huit variantes d'une même opération
 * ({@code UNBLOCKED}, {@code UNSUSPENDED}, {@code UNBANNED}, et un message
 * « introuvable » par décision) tenaient à la duplication, pas au besoin.
 */
public final class AdminConstants {

    private AdminConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // ------------------------------------------------------------------
    // Les cinq décisions
    // ------------------------------------------------------------------
    // Valeurs de l'étiquette `decision` de la métrique
    // `user_service.users.admin_decisions`, et libellé porté par le journal
    // d'audit. Des constantes plutôt que des chaînes posées sur place : une
    // faute de frappe créerait une série Prometheus fantôme, qu'aucun panneau
    // n'afficherait et dont rien ne signalerait l'existence.

    public static final String DECISION_BLOCAGE = "blocage";
    public static final String DECISION_BANNISSEMENT = "bannissement";
    public static final String DECISION_SUSPENSION = "suspension";
    public static final String DECISION_REVOCATION = "revocation";
    public static final String DECISION_RETABLISSEMENT = "retablissement";
    public static final String DECISION_FERMETURE = "fermeture";

    // ------------------------------------------------------------------
    // Journalisation des décisions
    // ------------------------------------------------------------------
    // Un seul gabarit pour les cinq : la décision est un paramètre, et l'état
    // précédent y figure parce que c'est lui qui rend la décision contestable.

    public static final String LOG_DECISION_PRISE =
            "DECISION ADMIN : compte {} (authUserId {}) -> {} | etat precedent : {} | motif : {}";
    public static final String LOG_DECISION_DEMANDEE =
            "Decision d'administration demandee sur le compte {} : {}";
    public static final String LOG_ERROR_COMPTE_INTROUVABLE =
            "Compte introuvable pour la decision {} : {}";
    public static final String LOG_WARN_BANNISSEMENT_LEVE =
            "Un bannissement, decision definitive, a ete leve sur le compte {}";

    // ------------------------------------------------------------------
    // Modification de profil par un administrateur
    // ------------------------------------------------------------------

    public static final String LOG_ADMIN_UPDATING_USER = "Admin updating user: {}";
    public static final String LOG_USER_PROFILE_UPDATED = "User profile updated successfully: {}";
    public static final String LOG_NOTE_AUTH_SERVICE =
            "Note : courriel, nom, prenom, role et etat d'activation se modifient via auth-service";
    public static final String LOG_WARN_ADMIN_PROFILE_UPDATED =
            "DECISION ADMIN : profil du compte {} (authUserId {}) modifie | {}";

    public static final String FORMAT_CHANGES_PREFIX = "Changements : ";
    public static final String FORMAT_CHANGE_DATE_OF_BIRTH = "dateOfBirth=%s, ";
    public static final String FORMAT_CHANGE_BIO = "bio modifiee, ";
    public static final String FORMAT_CHANGE_ADDRESS = "address=%s, ";
    public static final String FORMAT_CHANGE_CITY = "city=%s, ";
    public static final String FORMAT_CHANGE_COUNTRY = "country=%s, ";
    public static final String FORMAT_CHANGE_POSTAL_CODE = "postalCode=%s, ";

    // ------------------------------------------------------------------
    // Fermeture de compte (propagation à auth-service + audit)
    // ------------------------------------------------------------------

    public static final String LOG_FERMETURE_DEMANDEE = "Fermeture du compte {} demandee";
    public static final String LOG_FERMETURE_PROPAGEE =
            "DECISION ADMIN : compte {} (authUserId {}) ferme dans auth-service";
    public static final String LOG_ERROR_FERMETURE_ECHOUEE =
            "Propagation de la fermeture du compte {} a auth-service en echec : {}";

    public static final String FORMAT_USER_DETAILS = "User: %s, AuthUserId: %s";
    public static final String EX_CASCADE_DELETE_FAILED =
            "Echec de la propagation de la fermeture a auth-service : %s";
    public static final String EX_DELETED_VALUE = "DELETED";
    public static final String DETAIL_FERMETURE_ECHOUEE = "Fermeture de compte en echec";

    // ------------------------------------------------------------------
    // Erreurs
    // ------------------------------------------------------------------

    public static final String ERROR_USER_NOT_FOUND = "User not found with id: ";

    // ------------------------------------------------------------------
    // Libellés des endpoints d'administration
    // ------------------------------------------------------------------
    // Préfixe unique /admin/users : /admin/management/users n'existe plus.

    public static final String LOG_GET_ALL_USERS =
            "GET /admin/users - liste des comptes (page {}, taille {})";
    public static final String LOG_SEARCH_USERS =
            "GET /admin/users/search - recherche : {}";
    public static final String LOG_GET_USER_BY_ID =
            "GET /admin/users/{} - consultation d'un compte";
    public static final String LOG_UPDATE_USER =
            "PUT /admin/users/{} - modification d'un compte";
    public static final String LOG_DELETE_USER =
            "DELETE /admin/users/{} - fermeture d'un compte";
    public static final String LOG_GET_USERS_BY_STATUS =
            "GET /admin/users/status/{} - liste par statut";
    public static final String ERROR_STATUT_INCONNU = "Statut inconnu : ";
    public static final String ERROR_ACTION_INCONNUE = "Nature de decision inconnue : ";

    // ------------------------------------------------------------------
    // Journal d'audit
    // ------------------------------------------------------------------
    // Il etait ecrit et illisible : aucun endpoint ne l'exposait.

    public static final String LOG_GET_AUDIT_LOGS =
            "GET /admin/users/audit-logs - journal (admin={}, nature={})";
    public static final String LOG_GET_AUDIT_LOGS_OF_USER =
            "GET /admin/users/{}/audit-logs - journal du compte";
}
