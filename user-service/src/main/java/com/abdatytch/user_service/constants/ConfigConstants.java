package com.abdatytch.user_service.constants;

/**

 * Constantes pour les configurations (DotenvConfig, etc.)
 */
public final class ConfigConstants {

    private ConfigConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // Dotenv Configuration
    public static final String DOTENV_DIRECTORY = "..";
    public static final String PROPERTY_SOURCE_NAME = "dotenvProperties";

    // Environment Variable Keys - Database
    public static final String ENV_USER_DB_URL = "USER_DB_URL";
    public static final String ENV_USER_DB_USERNAME = "USER_DB_USERNAME";
    public static final String ENV_USER_DB_PASSWORD = "USER_DB_PASSWORD";

    // Environment Variable Keys - Redis
    public static final String ENV_REDIS_HOST = "REDIS_HOST";
    public static final String ENV_REDIS_PORT = "REDIS_PORT";
    public static final String ENV_REDIS_PASSWORD = "REDIS_PASSWORD";

    // Environment Variable Keys - Consul
    public static final String ENV_CONSUL_HOST = "CONSUL_HOST";
    public static final String ENV_CONSUL_PORT = "CONSUL_PORT";

    // Environment Variable Keys - JWT (obsolète - utilise JWKS maintenant)
    // public static final String ENV_JWT_PUBLIC_KEY_PATH = "JWT_PUBLIC_KEY_PATH";

    // Environment Variable Keys - Gateway
    public static final String ENV_GATEWAY_SECRET_KEY = "GATEWAY_SECRET_KEY";

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

    // Spring Property Keys - JWT (obsolète - utilise JWKS maintenant)
    // public static final String PROP_JWT_PUBLIC_KEY_PATH = "jwt.public-key-path";

    // Spring Property Keys - Gateway
    public static final String PROP_GATEWAY_SECRET_KEY = "gateway.secret.key";

    // Log Messages - INFO level
    public static final String LOG_INFO_DOTENV_LOADED = " Variables d'environnement chargées depuis .env ({} propriétés)";

    // Log Messages - ERROR level
    public static final String LOG_ERROR_DOTENV_LOAD_FAILED = "Impossible de charger .env: {}";

    // Redis Cache Configuration - Log Messages
    public static final String LOG_INFO_REDIS_CACHE_MANAGER_CONFIG = "Configuring Redis Cache Manager with optimized TTL settings for user-service";
    public static final String LOG_WARN_CACHE_GET_ERROR = "Cache GET error for cache '{}' with key '{}': {}. Continuing without cache.";
    public static final String LOG_WARN_CACHE_PUT_ERROR = "Cache PUT error for cache '{}' with key '{}': {}. Continuing without cache.";
    public static final String LOG_WARN_CACHE_EVICT_ERROR = "Cache EVICT error for cache '{}' with key '{}': {}. Continuing without cache.";
    public static final String LOG_WARN_CACHE_CLEAR_ERROR = "Cache CLEAR error for cache '{}': {}. Continuing without cache.";

    // Comments
    public static final String COMMENT_HARMONIZED_WITH_AUTH = "Harmonisé avec auth-service pour cohérence";
}
