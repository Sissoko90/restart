package com.abdatytch.media_service.constants;

/**
 * Constantes métier pour le service média
 */
public final class MediaConstants {

    private MediaConstants() {}

    // Topics Kafka
    public static final String TOPIC_MEDIA_EVENTS = "media-events";
    
    // Types d'événements
    public static final String EVENT_MEDIA_UPLOADED = "MEDIA_UPLOADED";
    public static final String EVENT_MEDIA_DELETED = "MEDIA_DELETED";
    public static final String EVENT_MEDIA_SHARED = "MEDIA_SHARED";
    
    // Status des médias
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DELETED = "DELETED";
    public static final String STATUS_FAILED = "FAILED";
    
    // Visibilité
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_SHARED = "SHARED";
    
    // Actions d'audit
    public static final String ACTION_UPLOAD = "UPLOAD";
    public static final String ACTION_DOWNLOAD = "DOWNLOAD";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_SHARE = "SHARE";
    public static final String ACTION_UPDATE = "UPDATE";
    
    // Préfixes Redis
    public static final String REDIS_PREFIX_MEDIA = "media:";
    public static final String REDIS_PREFIX_METADATA = "media:metadata:";
    public static final String REDIS_PREFIX_THUMBNAIL = "media:thumbnail:";
    
    // Messages
    public static final String MSG_UPLOAD_SUCCESS = "Fichier uploadé avec succès";
    public static final String MSG_DOWNLOAD_SUCCESS = "Fichier téléchargé avec succès";
    public static final String MSG_DELETE_SUCCESS = "Fichier supprimé avec succès";
    public static final String MSG_MEDIA_NOT_FOUND = "Média introuvable";
    public static final String MSG_UNAUTHORIZED_ACCESS = "Accès non autorisé à ce média";
    public static final String MSG_INVALID_FILE_TYPE = "Type de fichier non autorisé";
    public static final String MSG_FILE_TOO_LARGE = "Fichier trop volumineux";
}
