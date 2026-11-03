package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.constants.ConfigConstants;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Charge les variables d'environnement depuis .env et les mappe vers les propriétés Spring
 */
public class DotenvConfig implements EnvironmentPostProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(DotenvConfig.class);
    
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        log.debug("DotenvConfig.postProcessEnvironment() appelé");
        log.debug("Working directory: {}", System.getProperty("user.dir"));
        
        try {
            // Essayer plusieurs chemins pour trouver le fichier .env
            String[] possiblePaths = {
                "..",  // Répertoire parent (le plus probable)
                "../..",
                ".",
                System.getProperty("user.dir") + "/..",
                // Dernier recours : la racine du projet, si elle est déclarée.
                // Un chemin absolu figé était écrit ici — celui du poste d'un
                // seul développeur, donc inutile partout ailleurs, et révélant
                // son nom d'utilisateur.
                System.getenv().getOrDefault("PROJECT_ROOT", ".")
            };
            
            Dotenv dotenv = null;
            String foundPath = null;
            
            for (String path : possiblePaths) {
                try {
                    log.debug("Tentative de chargement .env depuis: {}", path);
                    dotenv = Dotenv.configure()
                            .directory(path)
                            .ignoreIfMissing()
                            .load();
                    
                    // Vérifier si EMAIL_HOST existe
                    if (dotenv != null && dotenv.get("EMAIL_HOST") != null) {
                        foundPath = path;
                        log.info("Fichier .env trouvé dans: {} avec {} variables", path, dotenv.entries().size());
                        break;
                    }
                } catch (Exception e) {
                    log.debug("Tentative échouée pour le chemin: {} - {}", path, e.getMessage());
                }
            }
            
            if (dotenv == null || dotenv.get("EMAIL_HOST") == null) {
                log.error("Fichier .env introuvable ou variable EMAIL_HOST manquante");
                return;
            }
            
            Map<String, Object> dotenvMap = new HashMap<>();
            
            dotenv.entries().forEach(entry -> {
                String key = entry.getKey();
                String value = entry.getValue();
                
                // Ajouter la variable telle quelle
                dotenvMap.put(key, value);
                
                // Mapper les variables d'environnement vers les propriétés Spring
                
                // Database
                if (ConfigConstants.ENV_AUTH_DB_URL.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_DATASOURCE_URL, value);
                }
                if (ConfigConstants.ENV_AUTH_DB_USERNAME.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_DATASOURCE_USERNAME, value);
                }
                if (ConfigConstants.ENV_AUTH_DB_PASSWORD.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_DATASOURCE_PASSWORD, value);
                }
                
                // Redis
                if (ConfigConstants.ENV_REDIS_HOST.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_REDIS_HOST, value);
                }
                if (ConfigConstants.ENV_REDIS_PORT.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_REDIS_PORT, value);
                }
                if (ConfigConstants.ENV_REDIS_PASSWORD.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_REDIS_PASSWORD, value);
                }
                
                // Consul
                if (ConfigConstants.ENV_CONSUL_HOST.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_CONSUL_HOST, value);
                }
                if (ConfigConstants.ENV_CONSUL_PORT.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_CONSUL_PORT, value);
                }
                
                // JWT
                if (ConfigConstants.ENV_JWT_EXPIRATION.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_JWT_EXPIRATION, value);
                }
                if (ConfigConstants.ENV_JWT_REFRESH_EXPIRATION.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_JWT_REFRESH_EXPIRATION, value);
                }
                
                // Gateway Security
                if ("GATEWAY_SECRET_KEY".equals(key)) {
                    dotenvMap.put("gateway.secret.key", value);
                }
                
                // Email
                if (ConfigConstants.ENV_EMAIL_HOST.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_EMAIL_HOST, value);
                }
                if (ConfigConstants.ENV_EMAIL_PORT.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_EMAIL_PORT, value);
                }
                if (ConfigConstants.ENV_EMAIL_USERNAME.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_EMAIL_USERNAME, value);
                }
                if (ConfigConstants.ENV_EMAIL_PASSWORD.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_EMAIL_PASSWORD, value);
                }
                
                // SMS (Twilio)
                if (ConfigConstants.ENV_TWILIO_ACCOUNT_SID.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_TWILIO_ACCOUNT_SID, value);
                }
                if (ConfigConstants.ENV_TWILIO_AUTH_TOKEN.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_TWILIO_AUTH_TOKEN, value);
                }
                if (ConfigConstants.ENV_TWILIO_PHONE_NUMBER.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_TWILIO_PHONE_NUMBER, value);
                }
            });
            
            environment.getPropertySources().addFirst(new MapPropertySource(ConfigConstants.PROPERTY_SOURCE_NAME, dotenvMap));
            log.info(ConfigConstants.LOG_INFO_DOTENV_LOADED, dotenvMap.size());
            
        } catch (Exception e) {
            log.error(ConfigConstants.LOG_ERROR_DOTENV_LOAD_FAILED, e.getMessage());
        }
    }
}
