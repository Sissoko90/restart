package com.abdatytch.user_service.constants;

/**
 * Messages de validation centralisés pour tous les DTOs
 * Tous les messages sont en français
 */
public final class ValidationMessages {
    
    private ValidationMessages() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }
    
    // ==========================================
    // CHAMPS COMMUNS
    // ==========================================
    
    public static final String AUTH_USER_ID_REQUIRED = "L'ID utilisateur auth est requis";
    
    public static final String EMAIL_INVALID = "Format d'email invalide";
    public static final String EMAIL_REQUIRED = "L'email est obligatoire";
    
    public static final String PHONE_REQUIRED = "Le numéro de téléphone est obligatoire";
    public static final String PHONE_INVALID = "Format de téléphone invalide";
    
    public static final String FIRST_NAME_REQUIRED = "Le prénom est obligatoire";
    public static final String FIRST_NAME_SIZE = "Le prénom doit contenir entre 2 et 100 caractères";
    public static final String FIRST_NAME_SIZE_SHORT = "Le prénom doit contenir entre 2 et 50 caractères";
    
    public static final String LAST_NAME_REQUIRED = "Le nom est obligatoire";
    public static final String LAST_NAME_SIZE = "Le nom doit contenir entre 2 et 100 caractères";
    public static final String LAST_NAME_SIZE_SHORT = "Le nom doit contenir entre 2 et 50 caractères";
    
    public static final String ROLE_REQUIRED = "Le rôle est obligatoire";
    public static final String ROLE_PATTERN = "Le rôle doit être USER, ADMIN ou SUPER_ADMIN";
    
    public static final String PASSWORD_REQUIRED = "Le mot de passe est obligatoire";
    public static final String PASSWORD_MIN_SIZE = "Le mot de passe doit contenir au moins 8 caractères";
    
    public static final String COUNTRY_CODE_REQUIRED = "Le code pays est obligatoire";
    public static final String COUNTRY_CODE_SIZE = "Le code pays doit contenir entre 2 et 5 caractères";
    
    // ==========================================
    // PROFIL UTILISATEUR
    // ==========================================
    
    public static final String DATE_OF_BIRTH_PAST = "La date de naissance doit être dans le passé";
    
    public static final String PREFERENCES_MAX_ENTREES =
            "Les preferences ne peuvent pas depasser 50 entrees";
    public static final String PREFERENCES_HORS_BORNES =
            "Une clef de preference depasse 64 caracteres, ou une valeur 512";

    public static final String BIO_MAX_SIZE = "La bio ne peut pas dépasser 1000 caractères";
    
    public static final String ADDRESS_MAX_SIZE = "L'adresse ne peut pas dépasser 500 caractères";
    
    public static final String CITY_MAX_SIZE = "La ville ne peut pas dépasser 100 caractères";
    
    public static final String COUNTRY_MAX_SIZE = "Le pays ne peut pas dépasser 100 caractères";
    
    public static final String POSTAL_CODE_PATTERN = "Le code postal doit contenir entre 4 et 10 chiffres";
    
    // ==========================================
    // ACTIONS ADMIN
    // ==========================================
    
    public static final String REASON_REQUIRED = "La raison est obligatoire";
    public static final String REASON_MIN_SIZE = "La raison doit contenir au moins 10 caractères";
    public static final String REASON_MAX_SIZE = "La raison ne peut pas dépasser 500 caractères";
    public static final String REASON_MAX_LENGTH = "La raison ne peut pas dépasser 500 caractères";
    
    // ==========================================
    // AUDIT
    // ==========================================
    
    public static final String ADMIN_ID_REQUIRED = "L'ID de l'admin est requis";
    public static final String ACTION_TYPE_REQUIRED = "Le type d'action est requis";
    public static final String ENTITY_TYPE_REQUIRED = "Le type d'entité est requis";
    
    // ==========================================
    // PATTERNS REGEX
    // ==========================================
    
    public static final String REGEX_PHONE = "^\\+?[1-9]\\d{1,14}$";
    public static final String REGEX_POSTAL_CODE = "^[0-9]{4,10}$";
    public static final String REGEX_ROLE = "^(USER|ADMIN|SUPER_ADMIN)$";
}
