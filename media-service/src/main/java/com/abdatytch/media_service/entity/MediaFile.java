package com.abdatytch.media_service.entity;

import com.abdatytch.media_service.enums.DocumentStatus;
import com.abdatytch.media_service.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Entité MongoDB pour les documents utilisateur
 * Gestion des documents obligatoires/optionnels par rôle avec validation de sécurité
 * Collection: user_documents
 */
@Document(collection = "user_documents")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndexes({
    // Nom CHANGE avec la definition : MongoDB refuse de creer un index de meme
    // nom avec une definition differente, et laisserait donc l'ancien en place
    // sans rien dire. L'index porte desormais sur le code du catalogue.
    @CompoundIndex(name = "idx_user_doctype_code", def = "{'userId': 1, 'documentTypeCode': 1}"),
    @CompoundIndex(name = "idx_user_role_status", def = "{'userId': 1, 'userRole': 1, 'status': 1}"),
    @CompoundIndex(name = "idx_status_expiry", def = "{'status': 1, 'expiryDate': 1}")
})
public class MediaFile extends BaseEntity {

    @NotBlank(message = "User ID is required")
    @Indexed
    private String userId;

    @Indexed
    private UserRole userRole; // Rôle de l'utilisateur au moment de l'upload

    /**
     * Code du type de document, tel qu'il figure au catalogue.
     *
     * <p>Le champ {@code DocumentType documentType} qui l'accompagnait a été
     * retiré : le catalogue vit en base, et l'énumération ne gouverne plus rien.
     * Ses deux annotations, {@code @NotBlank} et {@code @Size(max = 255)},
     * partent avec lui — elles ne s'appliquent qu'aux chaînes et étaient donc
     * <b>inertes</b> sur un type énuméré, en plus d'annoncer « File name is
     * required » sur un champ qui n'est pas un nom de fichier.
     */
    private String documentTypeCode;
    private String documentNumber; // Numéro du document (ex: numéro permis, RCCM, etc.)
    private String documentCategory; // Catégorie du document (ex: A, B, C pour permis)

    private String fileName;
    private String originalFileName;
    private String mimeType;
    private Long fileSize;
    private String fileHash; // SHA-256 pour détection de duplicatas

    private String storageProvider; // MINIO, S3
    private String storagePath;
    private String bucketName;
    private String thumbnailPath;

    @Indexed
    private DocumentStatus status;

    // Métadonnées du document
    private Map<String, Object> metadata; // width, height, pages, etc.
    private Map<String, Object> sanitizedMetadata; // Métadonnées nettoyées (sans scripts)

    // Validation et sécurité
    private Boolean isMandatory; // Document obligatoire pour ce rôle
    private LocalDate expiryDate; // Date d'expiration du document
    private LocalDate issueDate; // Date d'émission
    
    private String rejectionReason; // Raison du rejet si status = REJECTED
    private String reviewedBy; // ID de l'admin qui a validé/rejeté
    private LocalDateTime reviewedAt;

    // Sécurité et fraude
    private Boolean securityCheckPassed;
    private List<String> securityFlags; // Liste des menaces détectées
    private Double qualityScore; // Score de qualité (0-100)
    private Boolean isReadable; // Document lisible par OCR
    
    // Audit trail
    private String uploadedFromIp;
    private String userAgent;
    private Integer uploadAttempts; // Nombre de tentatives d'upload
    
    // Versioning
    private Integer currentVersion; // Version actuelle du document
    private Boolean hasVersionHistory; // Indique si des versions précédentes existent
    
    // Soft delete
    private LocalDateTime deletedAt;
    private String deletedBy; // ID de l'utilisateur qui a supprimé
}
