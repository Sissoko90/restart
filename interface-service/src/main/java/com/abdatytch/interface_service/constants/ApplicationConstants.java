package com.abdatytch.interface_service.constants;

/**
 * Constantes globales de l'application
 */
public final class ApplicationConstants {

    private ApplicationConstants() {
        // Classe utilitaire - constructeur privé
    }

    // Cache Keys
    public static final String CACHE_USER_PREFIX = "user:";
    public static final String CACHE_PROJECT_PREFIX = "project:";
    public static final String CACHE_USER_PROJECTS_PREFIX = "user_projects:";

    // Cache TTL (en secondes)
    public static final long CACHE_TTL_USER = 3600; // 1 heure
    public static final long CACHE_TTL_PROJECT = 1800; // 30 minutes
    public static final long CACHE_TTL_USER_PROJECTS = 900; // 15 minutes

    // Kafka Topics
    public static final String TOPIC_PROJECT_CREATED = "project.created";
    public static final String TOPIC_PROJECT_UPDATED = "project.updated";
    public static final String TOPIC_PROJECT_DELETED = "project.deleted";

    // API Endpoints (gateway ajoute automatiquement /api/v1)
    public static final String USERS_ENDPOINT = "/users";
    public static final String PROJECTS_ENDPOINT = "/projects";
    public static final String AUTH_ENDPOINT = "/auth";

    // Validation
    public static final int MIN_PROJECT_NAME_LENGTH = 3;
    public static final int MAX_PROJECT_NAME_LENGTH = 50;
    public static final int MAX_PROJECT_DESCRIPTION_LENGTH = 500;
    
    // Limites utilisateur
    public static final int MAX_PROJECTS_PER_USER = 10;
    
    // Email Templates
    public static final String EMAIL_VERIFICATION_TEMPLATE = "email-verification";
    public static final String PASSWORD_RESET_TEMPLATE = "password-reset";

    // Pagination
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    // Rate Limiting
    public static final int MAX_REQUESTS_PER_MINUTE = 100;
}
