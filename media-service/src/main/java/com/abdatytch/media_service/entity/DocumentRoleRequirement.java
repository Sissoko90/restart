package com.abdatytch.media_service.entity;

import com.abdatytch.media_service.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;

/**
 * Association entre un type de document et un rôle utilisateur
 * Définit si le document est obligatoire ou optionnel pour ce rôle
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "document_role_requirements")
@CompoundIndexes({
    @CompoundIndex(name = "doc_role_idx", def = "{'documentTypeCode': 1, 'userRole': 1}", unique = true)
})
public class DocumentRoleRequirement extends BaseEntity {
    
    /**
     * Code du type de document (référence à DocumentTypeConfig.code)
     */
    private String documentTypeCode;
    
    /**
     * Rôle utilisateur concerné
     */
    private UserRole userRole;
    
    /**
     * Le document est-il obligatoire pour ce rôle ?
     */
    @Builder.Default
    private Boolean mandatory = false;
    
    /**
     * L'exigence est-elle active ?
     */
    @Builder.Default
    private Boolean isActive = true;
    
    /**
     * Ordre d'affichage (pour trier les documents)
     */
    @Builder.Default
    private Integer displayOrder = 0;
}
