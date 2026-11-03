package com.abdatytch.user_service.constants;

/**
 * Constantes pour la gestion des exceptions (GlobalExceptionHandler)
 */
public final class ExceptionConstants {
    
    private ExceptionConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }
    
    // Error Types
    public static final String ERROR_NOT_FOUND = "Not Found";
    public static final String ERROR_FORBIDDEN = "Forbidden";
    public static final String ERROR_CONFLICT = "Conflict";
    public static final String ERROR_UNAUTHORIZED = "Unauthorized";
    public static final String ERROR_BAD_REQUEST = "Bad Request";
    public static final String ERROR_VALIDATION_FAILED = "Validation Failed";
    public static final String ERROR_MULTIPART = "Multipart Error";
    public static final String ERROR_FILE_STORAGE = "File Storage Error";
    public static final String ERROR_MISSING_PARAMETER = "Missing Parameter";
    public static final String ERROR_METHOD_NOT_ALLOWED = "Method Not Allowed";
    public static final String ERROR_DATA_INTEGRITY = "Data Integrity Violation";
    public static final String ERROR_NOT_IMPLEMENTED = "Not Implemented";
    public static final String ERROR_INTERNAL_SERVER = "Internal Server Error";
    
    // Log Messages - ERROR level
    public static final String LOG_ERROR_RESOURCE_NOT_FOUND = "Resource not found: {}";
    public static final String LOG_ERROR_ACCESS_FORBIDDEN = "Access forbidden: {}";
    public static final String LOG_ERROR_RESOURCE_ALREADY_EXISTS = "Resource already exists: {}";
    public static final String LOG_ERROR_INVALID_TOKEN = "Invalid token: {}";
    public static final String LOG_ERROR_ACCESS_DENIED = "Access denied: {}";
    public static final String LOG_ERROR_VALIDATION = "Validation error: {}";
    public static final String LOG_ERROR_ILLEGAL_ARGUMENT = "Illegal argument: {}";
    public static final String LOG_ERROR_MULTIPART_PARSING = "Multipart parsing error: {}";
    public static final String LOG_ERROR_FILE_STORAGE = "File storage error: {}";
    public static final String LOG_ERROR_MISSING_PARAMETER = "Missing request parameter: {}";
    public static final String LOG_ERROR_METHOD_NOT_SUPPORTED = "Method not supported: {}";
    public static final String LOG_ERROR_DATA_INTEGRITY = "Data integrity violation: {}";
    public static final String LOG_ERROR_UNSUPPORTED_OPERATION = "Unsupported operation: {}";
    public static final String LOG_ERROR_UNEXPECTED = "Unexpected error occurred: {} - {}";
    
    // Error Messages
    public static final String MSG_NO_PERMISSION = "You don't have permission to access this resource";
    public static final String MSG_INVALID_REQUEST_PARAMS = "Invalid request parameters";
    public static final String MSG_MULTIPART_ERROR = "Erreur lors du traitement du fichier: %s";
    public static final String MSG_MISSING_PARAM = "Le paramètre requis '%s' de type '%s' est manquant";
    public static final String MSG_METHOD_NOT_SUPPORTED = "La méthode HTTP '%s' n'est pas supportée pour cette URL. Méthodes supportées: %s";
    public static final String MSG_DATA_INTEGRITY_DEFAULT = "Violation de contrainte d'intégrité des données";
    public static final String MSG_DATA_INTEGRITY_CONSTRAINT = "Cette opération viole une contrainte de base de données (ex: clé unique, clé étrangère)";
    public static final String MSG_UNSUPPORTED_OPERATION_DEFAULT = "Cette opération n'est pas supportée";
    public static final String MSG_UNEXPECTED_ERROR = "Erreur inattendue: %s - %s";
    public static final String MSG_NO_DETAILS = "Aucun détail disponible";
    public static final String MSG_SUPPORTED_METHODS_NA = "N/A";
    
    // Keywords
    public static final String KEYWORD_CONSTRAINT = "constraint";
    
    // Field Separator
    public static final String FIELD_SEPARATOR = ": ";
    public static final String METHOD_SEPARATOR = ", ";
}
