package com.abdatytch.user_service.constants;

/**

 * Constantes pour les listeners Kafka
 */
public final class ListenerConstants {

    private ListenerConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // Kafka Topics
    public static final String TOPIC_USER_EVENTS = "user-events";

    // Kafka Group IDs
    public static final String GROUP_ID_USER_SERVICE = "user-service-group-dev";

    // Log Messages - INFO level
    public static final String LOG_INFO_EVENT_RECEIVED = " Réception d'un événement Kafka";
    public static final String LOG_INFO_USER_CREATED_EVENT = " Réception de UserCreatedEvent pour authUserId: {}";
    public static final String LOG_INFO_USER_ALREADY_EXISTS = "Utilisateur déjà existant, skip création: {}";
    public static final String LOG_INFO_PROFILE_CREATED = " Profil utilisateur créé avec succès pour authUserId: {}";

    // Log Messages - DEBUG level
    public static final String LOG_DEBUG_USER_LOGGED_IN_EVENT = " Réception de UserLoggedInEvent pour authUserId: {}";
    public static final String LOG_DEBUG_LAST_LOGIN_UPDATED = " LastLogin mis à jour pour authUserId: {}";

    // Log Messages - ERROR level
    public static final String LOG_ERROR_EVENT_PROCESSING = "Erreur lors du traitement de l'événement: {}";
    public static final String LOG_ERROR_PROFILE_CREATION = " Erreur lors de la création du profil utilisateur: {}";
    public static final String LOG_ERROR_LAST_LOGIN_UPDATE = " Erreur lors de la mise à jour de lastLogin: {}";

    // Comments
    public static final String COMMENT_LISTEN_AUTH_SERVICE = "Écoute les événements publiés par auth-service";
    public static final String COMMENT_AUTO_CREATE_PROFILE = "Crée automatiquement le profil utilisateur dans user-service";
    public static final String COMMENT_AUTH_USER_ID_ONLY = "Les autres données (email, phone, nom, role) restent dans auth-service";
    public static final String COMMENT_MANUAL_ACK = "Acknowledge manuel après traitement réussi";
    public static final String COMMENT_NO_ACK_ON_ERROR = "Ne pas acknowledge en cas d'erreur pour retry";
    public static final String COMMENT_NOT_CRITICAL = "On ne rethrow pas car ce n'est pas critique";
    public static final String COMMENT_RETHROW_FOR_RETRY = "Rethrow pour retry Kafka";
}
