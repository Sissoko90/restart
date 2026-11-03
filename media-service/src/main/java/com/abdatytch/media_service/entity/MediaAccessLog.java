package com.abdatytch.media_service.entity;

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

/**
 * Entité MongoDB pour les logs d'accès et audit de sécurité
 * Collection: document_access_logs
 */
@Document(collection = "document_access_logs")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndexes({
    @CompoundIndex(name = "idx_media_timestamp", def = "{'mediaId': 1, 'timestamp': -1}"),
    @CompoundIndex(name = "idx_user_action", def = "{'userId': 1, 'action': 1, 'timestamp': -1}")
})
public class MediaAccessLog extends BaseEntity {

    @Indexed
    private String mediaId;

    @Indexed
    private String userId;

    private String action; // UPLOAD, DOWNLOAD, DELETE, APPROVE, REJECT, FLAG_FRAUD

    private String ipAddress;
    private String userAgent;
    private String requestId; // X-Gateway-Request-Id pour traçabilité

    @Indexed
    private LocalDateTime timestamp;
    
    // Détails de sécurité
    private Boolean suspicious; // Activité suspecte détectée
    private String suspicionReason;
    private String geolocation; // Pays/Ville si disponible
}
