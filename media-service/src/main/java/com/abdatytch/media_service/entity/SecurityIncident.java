package com.abdatytch.media_service.entity;

import com.abdatytch.media_service.enums.SecurityThreatType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Entité MongoDB pour les incidents de sécurité détectés
 * Collection: security_incidents
 */
@Document(collection = "security_incidents")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndexes({
    @CompoundIndex(name = "idx_user_severity", def = "{'userId': 1, 'severity': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "idx_resolved", def = "{'resolved': 1, 'timestamp': -1}")
})
public class SecurityIncident extends BaseEntity {

    @Indexed
    private String userId;

    @Indexed
    private String mediaId; // Document concerné

    private SecurityThreatType threatType;
    
    private String severity; // LOW, MEDIUM, HIGH, CRITICAL
    
    private String description;
    private List<String> detectedPatterns; // Patterns malveillants détectés
    
    private String ipAddress;
    private String userAgent;
    private String requestId;
    
    @Indexed
    private LocalDateTime timestamp;
    
    private Boolean resolved;
    private String resolvedBy;
    private LocalDateTime resolvedAt;
    private String resolution; // Action prise
    
    private Boolean userBlocked; // Utilisateur bloqué suite à cet incident
}
