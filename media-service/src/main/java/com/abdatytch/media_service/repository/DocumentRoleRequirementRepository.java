package com.abdatytch.media_service.repository;

import com.abdatytch.media_service.entity.DocumentRoleRequirement;
import com.abdatytch.media_service.enums.UserRole;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface DocumentRoleRequirementRepository extends ReactiveMongoRepository<DocumentRoleRequirement, String> {
    
    Flux<DocumentRoleRequirement> findByUserRole(UserRole userRole);
    
    Flux<DocumentRoleRequirement> findByUserRoleAndIsActiveTrue(UserRole userRole, Boolean isActive);
    
    Flux<DocumentRoleRequirement> findByUserRoleAndMandatoryAndIsActiveTrue(UserRole userRole, Boolean mandatory, Boolean isActive);
    
    Flux<DocumentRoleRequirement> findByDocumentTypeCode(String documentTypeCode);
    
    Mono<DocumentRoleRequirement> findByDocumentTypeCodeAndUserRole(String documentTypeCode, UserRole userRole);
    
    Mono<Void> deleteByDocumentTypeCodeAndUserRole(String documentTypeCode, UserRole userRole);
    
    Mono<Boolean> existsByDocumentTypeCodeAndUserRole(String documentTypeCode, UserRole userRole);
}
