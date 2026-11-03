package com.abdatytch.user_service.config;

import com.abdatytch.user_service.constants.ConfigConstants;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**

 * Charge les variables d'environnement depuis .env et les mappe vers les propriétés Spring
 * Harmonisé avec auth-service pour cohérence
 */
@Slf4j
public class DotenvConfig implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(ConfigConstants.DOTENV_DIRECTORY)
                    .ignoreIfMissing()
                    .load();

            Map<String, Object> dotenvMap = new HashMap<>();

            dotenv.entries().forEach(entry -> {
                String key = entry.getKey();
                String value = entry.getValue();

                // Ajouter la variable telle quelle
                dotenvMap.put(key, value);

                // Mapper les variables d'environnement vers les propriétés Spring

                // Database
                if (ConfigConstants.ENV_USER_DB_URL.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_DATASOURCE_URL, value);
                }
                if (ConfigConstants.ENV_USER_DB_USERNAME.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_DATASOURCE_USERNAME, value);
                }
                if (ConfigConstants.ENV_USER_DB_PASSWORD.equals(key)) {
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

                // JWT (obsolète - utilise JWKS maintenant)
                // if (ConfigConstants.ENV_JWT_PUBLIC_KEY_PATH.equals(key)) {
                // dotenvMap.put(ConfigConstants.PROP_JWT_PUBLIC_KEY_PATH, value);
                // }

                // Gateway Security
                if (ConfigConstants.ENV_GATEWAY_SECRET_KEY.equals(key)) {
                    dotenvMap.put(ConfigConstants.PROP_GATEWAY_SECRET_KEY, value);
                }
            });

            environment.getPropertySources().addFirst(new MapPropertySource(ConfigConstants.PROPERTY_SOURCE_NAME, dotenvMap));
            System.out.println(" Dotenv loaded: " + dotenvMap.size() + " properties");

        } catch (Exception e) {
            System.err.println(" Failed to load .env: " + e.getMessage());
        }
    }
}
