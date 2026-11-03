package com.abdatytch.setting_service.config;

import com.abdatytch.setting_service.entities.SystemSetting;
import com.abdatytch.setting_service.entities.UserSetting;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.mapping.event.AfterConvertCallback;
import reactor.core.publisher.Mono;

/**
 * Marque comme « déjà persistée » toute entité relue depuis la base.
 *
 * <p>Les clés primaires sont assignées par l'application : sans ce rappel, Spring
 * Data R2DBC considérerait chaque entité relue comme neuve et tenterait un INSERT
 * en double. C'est le piège classique de R2DBC, sans message d'erreur explicite.
 */
@Configuration
public class R2dbcAuditingConfig {

    @Bean
    public AfterConvertCallback<SystemSetting> systemSettingNotNew() {
        return (entity, table) -> {
            entity.markNotNew();
            return Mono.just(entity);
        };
    }

    @Bean
    public AfterConvertCallback<UserSetting> userSettingNotNew() {
        return (entity, table) -> {
            entity.markNotNew();
            return Mono.just(entity);
        };
    }
}
