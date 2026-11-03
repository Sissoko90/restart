package com.abdatytch.media_service.entity;

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

/**
 * Entité MongoDB pour suivre les exigences documentaires par utilisateur
 * Permet de savoir quels documents sont requis et leur statut de complétion
 * Collection: user_document_requirements
 */
@Document(collection = "user_document_requirements")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@CompoundIndexes({
    @CompoundIndex(name = "idx_user_doctype_code", def = "{'userId': 1, 'documentTypeCode': 1}", unique = true),
    @CompoundIndex(name = "idx_user_completed", def = "{'userId': 1, 'completed': 1}")
})
public class UserDocumentRequirement extends BaseEntity {

    @Indexed
    private String userId;

    @Indexed
    private UserRole userRole;

    private String documentTypeCode;
    
    private Boolean mandatory;
    
    private Boolean completed;
    
    private String currentDocumentId;
    
    private String documentNumber;
    
    private String documentCategory;
    
    private Integer uploadAttempts;
    private Integer rejectionCount;
}
