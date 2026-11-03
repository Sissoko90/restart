package com.abdatytch.media_service.config;

import com.abdatytch.media_service.entity.MediaAccessLog;
import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.entity.UserDocumentRequirement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@Slf4j
@RequiredArgsConstructor
public class MongoInitializer implements CommandLineRunner {

    /*
     * Les .block() de cette classe sont DÉLIBÉRÉS et sans danger : ce code ne
     * s'exécute qu'au démarrage (CommandLineRunner) ou sur le pool de tâches
     * planifiées, jamais sur un EventLoop Netty. Bloquer y est le comportement
     * attendu — le démarrage doit être séquentiel.
     *
     * Ne pas reprendre ce motif dans du code servant des requêtes.
     */


    private final ReactiveMongoTemplate mongoTemplate;

    @Override
    public void run(String... args) {
        log.info("Initializing MongoDB collections...");
        
        try {
            Flux.concat(
                createCollectionIfNotExists("user_documents", MediaFile.class),
                createCollectionIfNotExists("document_access_logs", MediaAccessLog.class),
                createCollectionIfNotExists("user_document_requirements", UserDocumentRequirement.class)
            )
            .collectList()
            .block();
            
            log.info("MongoDB collections initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize MongoDB collections", e);
        }
    }

    private Flux<String> createCollectionIfNotExists(String collectionName, Class<?> entityClass) {
        return mongoTemplate.collectionExists(collectionName)
            .flatMapMany(exists -> {
                if (!exists) {
                    log.info("Creating collection: {}", collectionName);
                    return mongoTemplate.createCollection(collectionName)
                        .thenMany(Flux.just(collectionName));
                } else {
                    log.debug("Collection already exists: {}", collectionName);
                    return Flux.just(collectionName);
                }
            });
    }
}
