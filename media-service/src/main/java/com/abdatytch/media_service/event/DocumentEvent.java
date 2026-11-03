package com.abdatytch.media_service.event;

import com.abdatytch.media_service.enums.DocumentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Event Kafka pour notifications de changement de statut de document
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEvent {

    private String eventId;
    private String eventType; // UPLOADED, APPROVED, REJECTED, EXPIRED, DELETED
    private String documentId;
    private String userId;
    /**
     * Code du type de document, tel qu'il figure au catalogue.
     *
     * <p>Était une valeur d'énumération compilée. Le changement est libre : le
     * topic {@code document-events} est publié et <b>aucun service ne le
     * consomme</b> — vérifié sur l'ensemble du dépôt. Un consommateur futur lira
     * donc directement le code du catalogue, qui est la seule désignation
     * stable d'un type.
     */
    private String documentTypeCode;
    private DocumentStatus status;
    private String fileName;
    private LocalDateTime timestamp;
    
    // Métadonnées additionnelles
    private String reviewedBy;
    private String rejectionReason;
    private Double qualityScore;
    private Boolean securityCheckPassed;
    
    // Informations de contexte
    private String ipAddress;
    private String userAgent;
}
