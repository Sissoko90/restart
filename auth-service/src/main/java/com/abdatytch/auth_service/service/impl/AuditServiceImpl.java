package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.service.IAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Implémentation du service d'audit
 * Publie les événements d'audit vers Kafka pour traçabilité et SIEM
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditServiceImpl implements IAuditService {
    
    private final KafkaSender<String, Object> reactiveKafkaSender;
    
    private static final String AUDIT_TOPIC = "audit-events";
    
    @Override
    public Mono<Void> auditLoginAttempt(String userId, String identifier, String ipAddress,
                                         String userAgent, boolean success, String failureReason) {
        Map<String, Object> event = createBaseEvent("LOGIN_ATTEMPT");
        event.put("userId", userId);
        event.put("identifier", identifier);
        event.put("ipAddress", ipAddress);
        event.put("userAgent", userAgent);
        event.put("success", success);
        event.put("failureReason", failureReason);
        
        return publishAuditEvent(event, identifier);
    }
    
    @Override
    public Mono<Void> auditRoleChange(String adminUserId, String targetUserId,
                                       String action, String roleId) {
        Map<String, Object> event = createBaseEvent("ROLE_CHANGE");
        event.put("adminUserId", adminUserId);
        event.put("targetUserId", targetUserId);
        event.put("action", action);
        event.put("roleId", roleId);
        
        return publishAuditEvent(event, adminUserId);
    }
    
    @Override
    public Mono<Void> auditTokenRevocation(String userId, String tokenId, String reason) {
        Map<String, Object> event = createBaseEvent("TOKEN_REVOCATION");
        event.put("userId", userId);
        event.put("tokenId", tokenId);
        event.put("reason", reason);
        
        return publishAuditEvent(event, userId);
    }
    
    @Override
    public Mono<Void> auditPasswordChange(String userId, String ipAddress, boolean isReset) {
        Map<String, Object> event = createBaseEvent("PASSWORD_CHANGE");
        event.put("userId", userId);
        event.put("ipAddress", ipAddress);
        event.put("isReset", isReset);
        
        return publishAuditEvent(event, userId);
    }
    
    @Override
    public Mono<Void> auditAccountLockout(String identifier, String reason, int failedAttempts) {
        Map<String, Object> event = createBaseEvent("ACCOUNT_LOCKOUT");
        event.put("identifier", identifier);
        event.put("reason", reason);
        event.put("failedAttempts", failedAttempts);
        
        return publishAuditEvent(event, identifier);
    }
    
    @Override
    public Mono<Void> auditAdminAction(String adminUserId, String action, String targetResource,
                                        String details) {
        Map<String, Object> event = createBaseEvent("ADMIN_ACTION");
        event.put("adminUserId", adminUserId);
        event.put("action", action);
        event.put("targetResource", targetResource);
        event.put("details", details);
        
        return publishAuditEvent(event, adminUserId);
    }
    
    private Map<String, Object> createBaseEvent(String eventType) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("timestamp", Instant.now().toString());
        event.put("service", "auth-service");
        return event;
    }
    
    private Mono<Void> publishAuditEvent(Map<String, Object> event, String key) {
        String eventId = (String) event.get("eventId");
        
        ProducerRecord<String, Object> record = new ProducerRecord<>(AUDIT_TOPIC, key, event);
        
        return reactiveKafkaSender.send(Mono.just(SenderRecord.create(record, eventId)))
            .doOnNext(result -> log.debug("Événement d'audit publié: {} - {}", 
                event.get("eventType"), eventId))
            .doOnError(ex -> log.error("Erreur lors de la publication événement d'audit: {}", 
                event.get("eventType"), ex))
            .then();
    }
}
