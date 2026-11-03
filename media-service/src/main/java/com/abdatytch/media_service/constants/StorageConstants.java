package com.abdatytch.media_service.constants;

import java.util.Set;

/**
 * Constantes pour le stockage MinIO/S3
 */
public final class StorageConstants {

    private StorageConstants() {}

    // Types MIME autorisés
    public static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp"
    );
    
    public static final Set<String> ALLOWED_VIDEO_TYPES = Set.of(
        "video/mp4",
        "video/webm",
        "video/quicktime"
    );
    
    public static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );
    
    // Extensions
    public static final Set<String> IMAGE_EXTENSIONS = Set.of(
        "jpg", "jpeg", "png", "gif", "webp"
    );
    
    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
        "mp4", "webm", "mov"
    );
    
    public static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
        "pdf", "doc", "docx"
    );
    
    // Tailles
    public static final long MAX_FILE_SIZE = 104857600L; // 100 MB
    public static final long MAX_IMAGE_SIZE = 10485760L; // 10 MB
    public static final long MAX_VIDEO_SIZE = 104857600L; // 100 MB
    public static final long MAX_DOCUMENT_SIZE = 20971520L; // 20 MB
    
    // Chemins
    public static final String PATH_USERS = "users/";
    public static final String PATH_PHOTOS = "photos/";
    public static final String PATH_VIDEOS = "videos/";
    public static final String PATH_DOCUMENTS = "documents/";
    public static final String PATH_THUMBNAILS = "thumbnails/";
}
