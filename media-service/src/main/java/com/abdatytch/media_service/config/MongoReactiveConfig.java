package com.abdatytch.media_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.config.EnableReactiveMongoAuditing;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

/**
 * Configuration MongoDB Reactive
 * Utilise Spring Data Reactive MongoDB pour accès non-bloquant
 */
@Configuration
@EnableReactiveMongoRepositories(basePackages = "com.abdatytch.media_service.repository")
@EnableReactiveMongoAuditing
public class MongoReactiveConfig {

    /**
     * Template MongoDB réactif pour opérations custom
     */
    @Bean
    public ReactiveMongoTemplate reactiveMongoTemplate(
            ReactiveMongoDatabaseFactory factory,
            MappingMongoConverter converter) {
        
        // Désactiver l'ajout automatique du champ _class dans MongoDB
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
        
        return new ReactiveMongoTemplate(factory, converter);
    }
}
