package com.abdatytch.media_service.repository;

import com.abdatytch.media_service.entity.UserDocumentRequirement;
import com.abdatytch.media_service.enums.UserRole;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository pour les exigences documentaires par utilisateur
 */
@Repository
public interface UserDocumentRequirementRepository extends ReactiveMongoRepository<UserDocumentRequirement, String> {
    
    // `findByUserIdAndDocumentType(String, DocumentType)` a ete RETIREE : plus
    // aucun appelant depuis que le code du catalogue a remplace l'enumeration.
    // Son equivalent, `findByUserIdAndDocumentTypeCode`, existe plus bas.

    Flux<UserDocumentRequirement> findByUserId(String userId);
    
    Flux<UserDocumentRequirement> findByUserIdAndUserRole(String userId, UserRole userRole);
    
    Flux<UserDocumentRequirement> findByUserIdAndMandatoryTrue(String userId);
    
    Flux<UserDocumentRequirement> findByUserIdAndCompletedFalse(String userId);
    
    Mono<Long> countByUserIdAndMandatoryTrueAndCompletedTrue(String userId);
    
    Mono<UserDocumentRequirement> findByUserIdAndDocumentTypeCode(String userId, String documentTypeCode);
}
