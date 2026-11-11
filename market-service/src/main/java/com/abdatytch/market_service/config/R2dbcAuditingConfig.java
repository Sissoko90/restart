package com.abdatytch.market_service.config;

import com.abdatytch.market_service.entities.BaseEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback;
import reactor.core.publisher.Mono;

/**
 * Pose les horodatages d'audit avant chaque ecriture.
 *
 * <h2>Un rappel et non {@code @EnableR2dbcAuditing}</h2>
 *
 * {@code @CreatedDate} et {@code @LastModifiedDate} exigent le contexte de
 * mapping R2DBC, donc l'auto-configuration complete — que les profils de test
 * excluent. Le contexte ne demarrait alors plus, et un service sans test de
 * contexte ne s'en apercevait pas.
 */
@Configuration
public class R2dbcAuditingConfig {

    @Bean
    public BeforeConvertCallback<BaseEntity> horodatagesDaudit() {
        return (entite, table) -> {
            entite.preparerAvantEcriture();
            return Mono.just(entite);
        };
    }
}
