package com.abdatytch.auth_service.constants;

/**

 * Constantes pour les configurations
 */
public final class ConfigConstants {

    private ConfigConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // Dotenv Configuration
    public static final String DOTENV_DIRECTORY = "..";
    public static final String PROPERTY_SOURCE_NAME = "dotenvProperties";

    // Environment Variable Keys - Database
    public static final String ENV_AUTH_DB_URL = "AUTH_DB_URL";
    public static final String ENV_AUTH_DB_USERNAME = "AUTH_DB_USERNAME";
    public static final String ENV_AUTH_DB_PASSWORD = "AUTH_DB_PASSWORD";

    // Environment Variable Keys - Redis
    public static final String ENV_REDIS_HOST = "REDIS_HOST";
    public static final String ENV_REDIS_PORT = "REDIS_PORT";
    public static final String ENV_REDIS_PASSWORD = "REDIS_PASSWORD";

    // Environment Variable Keys - Consul
    public static final String ENV_CONSUL_HOST = "CONSUL_HOST";
    public static final String ENV_CONSUL_PORT = "CONSUL_PORT";

    // Environment Variable Keys - JWT
    public static final String ENV_JWT_EXPIRATION = "JWT_EXPIRATION";
    public static final String ENV_JWT_REFRESH_EXPIRATION = "JWT_REFRESH_EXPIRATION";

    // Environment Variable Keys - Email
    public static final String ENV_EMAIL_HOST = "EMAIL_HOST";
    public static final String ENV_EMAIL_PORT = "EMAIL_PORT";
    public static final String ENV_EMAIL_USERNAME = "EMAIL_USERNAME";
    public static final String ENV_EMAIL_PASSWORD = "EMAIL_PASSWORD";

    // Environment Variable Keys - SMS
    public static final String ENV_TWILIO_ACCOUNT_SID = "TWILIO_ACCOUNT_SID";
    public static final String ENV_TWILIO_AUTH_TOKEN = "TWILIO_AUTH_TOKEN";
    public static final String ENV_TWILIO_PHONE_NUMBER = "TWILIO_PHONE_NUMBER";

    // Environment Variable Keys - Kafka
    public static final String ENV_KAFKA_BOOTSTRAP_SERVERS = "KAFKA_BOOTSTRAP_SERVERS";

    // Spring Property Keys - Database
    public static final String PROP_DATASOURCE_URL = "spring.datasource.url";
    public static final String PROP_DATASOURCE_USERNAME = "spring.datasource.username";
    public static final String PROP_DATASOURCE_PASSWORD = "spring.datasource.password";

    // Spring Property Keys - Redis
    public static final String PROP_REDIS_HOST = "spring.data.redis.host";
    public static final String PROP_REDIS_PORT = "spring.data.redis.port";
    public static final String PROP_REDIS_PASSWORD = "spring.data.redis.password";

    // Spring Property Keys - Consul
    public static final String PROP_CONSUL_HOST = "spring.cloud.consul.host";
    public static final String PROP_CONSUL_PORT = "spring.cloud.consul.port";

    // Spring Property Keys - JWT
    public static final String PROP_JWT_EXPIRATION = "jwt.expiration";
    public static final String PROP_JWT_REFRESH_EXPIRATION = "jwt.refresh-expiration";

    // Spring Property Keys - Email
    public static final String PROP_EMAIL_HOST = "spring.mail.host";
    public static final String PROP_EMAIL_PORT = "spring.mail.port";
    public static final String PROP_EMAIL_USERNAME = "spring.mail.username";
    public static final String PROP_EMAIL_PASSWORD = "spring.mail.password";

    // Spring Property Keys - SMS
    public static final String PROP_TWILIO_ACCOUNT_SID = "twilio.account-sid";
    public static final String PROP_TWILIO_AUTH_TOKEN = "twilio.auth-token";
    public static final String PROP_TWILIO_PHONE_NUMBER = "twilio.phone-number";

    // Spring Property Keys - Kafka
    public static final String PROP_KAFKA_BOOTSTRAP_SERVERS = "spring.kafka.bootstrap-servers";

    // Log Messages - INFO level
    public static final String LOG_INFO_DOTENV_LOADED = " Variables d'environnement chargées depuis .env ({} propriétés)";
    public static final String LOG_INFO_DATA_SEEDER_START = " Démarrage du Data Seeder...";
    public static final String LOG_INFO_ADMIN_SEEDER_START = " Création du compte administrateur par défaut...";
    public static final String LOG_INFO_ADMIN_EXISTS = "ℹ  Compte administrateur déjà existant";
    public static final String LOG_INFO_ADMIN_CREATED = " Compte administrateur créé avec succès";
    public static final String LOG_INFO_TEMPLATES_SEEDER_START = " Initialisation des templates de messages...";
    public static final String LOG_INFO_TEMPLATE_EXISTS = "Template {} déjà existant";
    public static final String LOG_INFO_TEMPLATE_CREATED = " Template {} créé";
    public static final String LOG_INFO_CLEANUP_TASK_START = " Démarrage du nettoyage des codes expirés...";
    public static final String LOG_INFO_CLEANUP_TASK_COMPLETE = " Nettoyage terminé: {} codes expirés supprimés";
    public static final String LOG_INFO_FEIGN_INTERCEPTOR = " Feign Service Token Interceptor - Ajout du token JWT pour: {}";

    // Log Messages - WARN level
    public static final String LOG_WARN_ADMIN_CREATION_FAILED = "Erreur lors de la création du compte administrateur";

    // Log Messages - ERROR level
    public static final String LOG_ERROR_DOTENV_LOAD_FAILED = "Impossible de charger .env: {}";
    public static final String LOG_ERROR_DATA_SEEDER_FAILED = " Erreur lors du Data Seeder";
    public static final String LOG_ERROR_CLEANUP_TASK_FAILED = " Erreur lors du nettoyage des codes expirés";

    // Redis Cache Configuration - Log Messages
    public static final String LOG_INFO_REDIS_CACHE_MANAGER_CONFIG = "Configuring Redis Cache Manager with optimized TTL settings";
    public static final String LOG_WARN_CACHE_GET_ERROR = "Cache GET error for cache '{}' with key '{}': {}. Continuing without cache.";
    public static final String LOG_WARN_CACHE_PUT_ERROR = "Cache PUT error for cache '{}' with key '{}': {}. Continuing without cache.";
    public static final String LOG_WARN_CACHE_EVICT_ERROR = "Cache EVICT error for cache '{}' with key '{}': {}. Continuing without cache.";
    public static final String LOG_WARN_CACHE_CLEAR_ERROR = "Cache CLEAR error for cache '{}': {}. Continuing without cache.";

    // Comments
    public static final String COMMENT_HARMONIZED = "Harmonisé avec user-service pour cohérence";
    public static final String COMMENT_PRODUCTION_ENV = "En production, utiliser des variables d'environnement";
}
