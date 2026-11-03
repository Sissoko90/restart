package com.abdatytch.media_service.repository;

import com.abdatytch.media_service.entity.DocumentTypeConfig;
import com.abdatytch.media_service.enums.DocumentCategory;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface DocumentTypeConfigRepository extends ReactiveMongoRepository<DocumentTypeConfig, String> {
    
    Mono<DocumentTypeConfig> findByCode(String code);
    
    Flux<DocumentTypeConfig> findByIsActiveTrue();
    
    Flux<DocumentTypeConfig> findByCategory(DocumentCategory category);
    
    Flux<DocumentTypeConfig> findByCategoryAndIsActiveTrue(DocumentCategory category, Boolean isActive);
    
    Mono<Boolean> existsByCode(String code);
}
