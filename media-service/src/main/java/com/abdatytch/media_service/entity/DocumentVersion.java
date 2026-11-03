package com.abdatytch.media_service.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Entité pour le versioning des documents
 * Conserve l'historique des modifications
 */
@Document(collection = "document_versions")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVersion extends BaseEntity {

    private String documentId; // ID du document parent
    private String userId;
    private Integer documentVersion; // Numéro de version
    
    private String fileName;
    private String storagePath;
    private String bucketName;
    private Long fileSize;
    private String fileHash;
    
    private String replacedBy; // ID de l'utilisateur qui a remplacé
    private String replacementReason;
}
