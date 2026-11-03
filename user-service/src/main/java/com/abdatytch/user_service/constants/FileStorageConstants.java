package com.abdatytch.user_service.constants;

import java.util.Arrays;
import java.util.List;

/**
 * Constantes pour le service de stockage de fichiers
 */
public final class FileStorageConstants {
    
    private FileStorageConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }
    
    // File Configuration
    public static final List<String> ALLOWED_EXTENSIONS = Arrays.asList("jpg", "jpeg", "png", "gif", "webp");
    public static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    
    // Log Messages - INFO level
    public static final String LOG_STORING_PROFILE_PICTURE = "Storing profile picture for user: {}";
    public static final String LOG_PROFILE_PICTURE_STORED = "Profile picture stored successfully: {}";
    /** URL dont on ne peut pas deduire un chemin : la suppression est impossible. */
    public static final String LOG_ERROR_FAILED_TO_DELETE_PATH =
            "Chemin indeduisible de l'URL, photographie NON supprimee : {}";

    /** Fichier deja absent. Journalise, pour ne plus passer en silence. */
    public static final String LOG_WARN_FILE_ABSENT =
            "Photographie deja absente du disque : {}";

    public static final String LOG_PROFILE_PICTURE_DELETED = "Profile picture deleted: {}";
    
    // Log Messages - WARN level
    public static final String LOG_WARN_FILE_NULL_OR_EMPTY = "File is null or empty";
    public static final String LOG_WARN_FILE_SIZE_EXCEEDS = "File size exceeds limit: {} bytes";
    public static final String LOG_WARN_FILENAME_NULL = "Original filename is null";
    public static final String LOG_WARN_INVALID_EXTENSION = "Invalid file extension: {}";
    public static final String LOG_WARN_INVALID_CONTENT_TYPE = "Invalid content type: {}";
    
    // Log Messages - ERROR level
    public static final String LOG_ERROR_FAILED_TO_STORE = "Failed to store profile picture for user: {}";
    public static final String LOG_ERROR_FAILED_TO_DELETE = "Failed to delete profile picture: {}";
    
    // Exception Messages
    public static final String EX_INVALID_FILE = "Fichier invalide. Types acceptés: jpg, jpeg, png, gif, webp. Taille max: 5MB";
    public static final String EX_STORAGE_ERROR = "Erreur lors du stockage du fichier: %s";
    
    // Default Values
    public static final String DEFAULT_EXTENSION = ".jpg";
}
