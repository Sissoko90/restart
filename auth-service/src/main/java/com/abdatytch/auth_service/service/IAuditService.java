package com.abdatytch.auth_service.service;

import reactor.core.publisher.Mono;

/**
 * Service d'audit pour tracer les actions sensibles
 * Publie les événements vers Kafka pour SIEM/Logging centralisé
 */
public interface IAuditService {
    
    /**
     * Audit d'une tentative de connexion
     */
    Mono<Void> auditLoginAttempt(String userId, String identifier, String ipAddress, 
                                   String userAgent, boolean success, String failureReason);
    
    /**
     * Audit d'une modification de rôle
     */
    Mono<Void> auditRoleChange(String adminUserId, String targetUserId, 
                                String action, String roleId);
    
    /**
     * Audit d'une révocation de token
     */
    Mono<Void> auditTokenRevocation(String userId, String tokenId, String reason);
    
    /**
     * Audit d'un changement de mot de passe
     */
    Mono<Void> auditPasswordChange(String userId, String ipAddress, boolean isReset);
    
    /**
     * Audit d'un verrouillage de compte
     */
    Mono<Void> auditAccountLockout(String identifier, String reason, int failedAttempts);
    
    /**
     * Audit d'une action administrative sensible
     */
    Mono<Void> auditAdminAction(String adminUserId, String action, String targetResource, 
                                 String details);
}
