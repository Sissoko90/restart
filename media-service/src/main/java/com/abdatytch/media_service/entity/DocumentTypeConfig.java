package com.abdatytch.media_service.entity;

import com.abdatytch.media_service.enums.DocumentCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Configuration dynamique des types de documents
 * Permet à l'admin de créer et gérer les types de documents
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "document_type_configs")
public class DocumentTypeConfig extends BaseEntity {
    
    /**
     * Code unique du document (ex: NATIONAL_ID, DRIVER_LICENSE)
     */
    @Indexed(unique = true)
    @NotBlank(message = "Code is required")
    @Size(max = 50, message = "Code must not exceed 50 characters")
    private String code;
    
    /**
     * Nom d'affichage (ex: "Pièce d'identité nationale")
     */
    private String displayName;
    
    /**
     * Description détaillée
     */
    private String description;
    
    /**
     * Catégorie du document (IDENTITY, PROFESSIONAL, VEHICLE, BUSINESS, OTHER)
     */
    private DocumentCategory category;
    
    /**
     * Le document nécessite-t-il un numéro ?
     */
    @Builder.Default
    private Boolean requiresNumber = false;
    
    /**
     * Le document nécessite-t-il une date d'expiration ?
     */
    @Builder.Default
    private Boolean requiresExpirationDate = false;
    
    /**
     * Le document nécessite-t-il une catégorie (ex: permis A, B, C) ?
     */
    @Builder.Default
    private Boolean requiresCategory = false;
    
    /**
     * Catégories disponibles si requiresCategory = true
     * Ex: ["A", "B", "C", "D", "E", "F"] pour permis de conduire
     */
    private List<String> availableCategories;
    
    /**
     * Catégories qui n'ont PAS de date d'expiration
     * Ex: ["A", "B"] pour permis moto et voiture
     */
    private List<String> categoriesWithoutExpiration;
    
    /**
     * Durée de validité par défaut en années (0 = pas d'expiration)
     */
    @Builder.Default
    private Integer defaultValidityYears = 0;
    
    /**
     * Types MIME acceptés (ex: ["image/jpeg", "image/png", "application/pdf"])
     */
    private List<String> acceptedMimeTypes;
    
    /**
     * Taille maximale du fichier en MB
     */
    @Builder.Default
    private Integer maxFileSizeMB = 50;
    
    /**
     * Le type de document est-il actif ?
     */
    @Builder.Default
    private Boolean isActive = true;
}
