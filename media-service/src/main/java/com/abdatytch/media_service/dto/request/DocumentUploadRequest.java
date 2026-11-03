package com.abdatytch.media_service.dto.request;

import com.abdatytch.media_service.enums.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * DTO pour la requête d'upload de document.
 *
 * <p>Le champ {@code DocumentType documentType}, annoté {@code @Deprecated}, a
 * été retiré : il désignait l'énumération compilée, dont la disparition est tout
 * l'objet de cette migration. {@code documentTypeCode} était déjà présent à
 * côté et devient la seule désignation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadRequest {

    private String documentTypeCode;
    
    @NotNull(message = "Le rôle utilisateur est obligatoire")
    private UserRole userRole;
    
    private String documentNumber;
    
    private String documentCategory;
    
    private LocalDate issueDate;
    
    private LocalDate expiryDate;
    
    private String description;
}
