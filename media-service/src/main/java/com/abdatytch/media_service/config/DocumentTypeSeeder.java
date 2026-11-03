package com.abdatytch.media_service.config;

import com.abdatytch.media_service.entity.DocumentRoleRequirement;
import com.abdatytch.media_service.entity.DocumentTypeConfig;
import com.abdatytch.media_service.enums.DocumentCategory;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.repository.DocumentRoleRequirementRepository;
import com.abdatytch.media_service.repository.DocumentTypeConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Arrays;
import java.util.List;

/**
 * Initialise les types de documents et leurs affectations aux rôles au démarrage
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentTypeSeeder implements CommandLineRunner {
    
    private final DocumentTypeConfigRepository documentTypeConfigRepository;
    private final DocumentRoleRequirementRepository roleRequirementRepository;
    
    @Override
    public void run(String... args) {
        log.info(" Starting document types seeding...");
        
        seedDocumentTypes()
            .then(seedRoleAssignments())
            .doOnSuccess(v -> log.info(" Document types seeding completed"))
            .doOnError(e -> log.error(" Error seeding document types: {}", e.getMessage()))
            .subscribe();
    }
    
    private Mono<Void> seedDocumentTypes() {
        return Flux.fromIterable(getInitialDocumentTypes())
            .flatMap(docType -> 
                documentTypeConfigRepository.findByCode(docType.getCode())
                    .switchIfEmpty(documentTypeConfigRepository.save(docType)
                        .doOnSuccess(saved -> log.info(" Created document type: {}", saved.getCode())))
                    .then()
            )
            .then();
    }
    
    private Mono<Void> seedRoleAssignments() {
        return Flux.fromIterable(getInitialRoleAssignments())
            .flatMap(assignment -> 
                roleRequirementRepository.existsByDocumentTypeCodeAndUserRole(
                    assignment.getDocumentTypeCode(), 
                    assignment.getUserRole()
                )
                .flatMap(exists -> {
                    if (!exists) {
                        return roleRequirementRepository.save(assignment)
                            .doOnSuccess(saved -> log.info(" Assigned {} to {} (mandatory: {})", 
                                saved.getDocumentTypeCode(), 
                                saved.getUserRole(), 
                                saved.getMandatory()))
                            .then();
                    }
                    return Mono.empty();
                })
            )
            .then();
    }
    
    private List<DocumentTypeConfig> getInitialDocumentTypes() {
        return Arrays.asList(
            // Documents d'identité
            createDocType("NATIONAL_ID", "Pièce d'identité nationale", DocumentCategory.IDENTITY, 
                true, true, false, null, null, 5),
            
            createDocType("CRIMINAL_RECORD", "Casier judiciaire", DocumentCategory.JUDICIAL, 
                false, false, false, null, null, 0),
            
            createDocType("RESIDENCE_CERTIFICATE", "Certificat de résidence", DocumentCategory.RESIDENCE, 
                false, false, false, null, null, 1),
            
            // Documents pour conducteurs
            createDocType("DRIVER_LICENSE", "Permis de conduire", DocumentCategory.VEHICLE, 
                true, true, true, 
                Arrays.asList("A", "B", "C", "D", "E", "F"),
                Arrays.asList("A", "B"), 5),
            
            // Documents professionnels - Pharmacy
            createDocType("PHARMACY_LICENSE", "Licence d'exploitation pharmacie", DocumentCategory.PROFESSIONAL, 
                true, true, false, null, null, 5),
            
            createDocType("PHARMACIST_DIPLOMA", "Diplôme de pharmacien", DocumentCategory.PROFESSIONAL, 
                false, false, false, null, null, 0),
            
            createDocType("PHARMACY_INSURANCE", "Assurance professionnelle pharmacie", DocumentCategory.PROFESSIONAL, 
                false, true, false, null, null, 1),
            
            createDocType("PHARMACY_APPROVAL", "Agrément pharmacie", DocumentCategory.PROFESSIONAL, 
                false, true, false, null, null, 5),
            
            // Documents business
            createDocType("RCCM", "RCCM (Registre de Commerce)", DocumentCategory.BUSINESS, 
                true, false, false, null, null, 0),
            
            createDocType("NIF", "NIF (Numéro d'Identification Fiscale)", DocumentCategory.BUSINESS, 
                true, false, false, null, null, 0),
            
            // Documents restaurant
            createDocType("RESTAURANT_HEALTH_LICENSE", "Licence sanitaire restaurant", DocumentCategory.PROFESSIONAL, 
                false, true, false, null, null, 1),
            
            createDocType("FIRE_SAFETY_CERTIFICATE", "Certificat sécurité incendie", DocumentCategory.PROFESSIONAL, 
                false, true, false, null, null, 1),
            
            createDocType("FOOD_HANDLING_CERTIFICATE", "Certificat manipulation aliments", DocumentCategory.PROFESSIONAL, 
                false, true, false, null, null, 2),
            
            // Documents supermarché
            createDocType("SUPERMARKET_LICENSE", "Licence commerce supermarché", DocumentCategory.BUSINESS, 
                false, true, false, null, null, 5),
            
            createDocType("HEALTH_PERMIT", "Permis sanitaire", DocumentCategory.PROFESSIONAL, 
                false, true, false, null, null, 1),
            
            // Documents généraux
            createDocType("CV", "Curriculum Vitae", DocumentCategory.OTHER,
                false, false, false, null, null, 0),

            createDocType("OTHER_DOCUMENT", "Autre document", DocumentCategory.OTHER,
                false, false, false, null, null, 0),

            // ================================================================
            // Types versés depuis l'ancienne énumération `DocumentType`
            // ================================================================
            // Les deux catalogues ne contenaient PAS les mêmes types : 25 dans
            // l'énumération, 17 ici, et seulement 5 codes communs. Comme un
            // envoi exigeait le code dans les deux, 5 types étaient
            // téléversables — un supermarché n'en avait aucun.
            //
            // Ces 12 entrées sont celles que seule l'énumération portait. Leurs
            // doublons renommés ne sont PAS repris : `NATIONAL_ID_CARD`,
            // `MERCHANT_RCCM`, `MERCHANT_NIF`, `RESTAURANT_FIRE_SAFETY`,
            // `FOOD_HANDLER_CERTIFICATE`, `SUPERMARKET_COMMERCE_LICENSE`,
            // `SUPERMARKET_HEALTH_PERMIT` et `OTHER` désignent les mêmes
            // documents que `NATIONAL_ID`, `RCCM`, `NIF`,
            // `FIRE_SAFETY_CERTIFICATE`, `FOOD_HANDLING_CERTIFICATE`,
            // `SUPERMARKET_LICENSE`, `HEALTH_PERMIT` et `OTHER_DOCUMENT`
            // ci-dessus. Les codes d'ici gagnent, parce que ce sont eux que
            // référencent les affectations de rôles.
            //
            // Libellés et durées de validité viennent de l'énumération. Les
            // trois indicateurs `requires*` sont posés à `false` : l'énumération
            // n'en disait rien, et exiger un champ de plus bloquerait un envoi.
            // L'API d'administration permet de les resserrer — c'est son objet.

            createDocType("PASSPORT", "Passeport", DocumentCategory.IDENTITY,
                false, false, false, null, null, 10),

            createDocType("BIRTH_CERTIFICATE", "Acte de naissance", DocumentCategory.IDENTITY,
                false, false, false, null, null, 0),

            createDocType("VEHICLE_INSURANCE", "Assurance véhicule", DocumentCategory.VEHICLE,
                false, false, false, null, null, 1),

            createDocType("VEHICLE_REGISTRATION", "Carte grise", DocumentCategory.VEHICLE,
                false, false, false, null, null, 5),

            createDocType("DRIVER_MEDICAL_CERTIFICATE", "Certificat médical conducteur",
                DocumentCategory.PROFESSIONAL, false, false, false, null, null, 1),

            createDocType("MERCHANT_PATENT", "Patente commerciale", DocumentCategory.BUSINESS,
                false, false, false, null, null, 1),

            createDocType("MERCHANT_BUSINESS_PLAN", "Plan d'affaires", DocumentCategory.BUSINESS,
                false, false, false, null, null, 0),

            createDocType("DISPATCHER_EMPLOYMENT_CONTRACT", "Contrat de travail répartiteur",
                DocumentCategory.PROFESSIONAL, false, false, false, null, null, 0),

            createDocType("DISPATCHER_BACKGROUND_CHECK", "Vérification antécédents",
                DocumentCategory.JUDICIAL, false, false, false, null, null, 0),

            createDocType("PROOF_OF_ADDRESS", "Justificatif de domicile",
                DocumentCategory.RESIDENCE, false, false, false, null, null, 0),

            createDocType("BANK_STATEMENT", "Relevé bancaire", DocumentCategory.BUSINESS,
                false, false, false, null, null, 0),

            createDocType("TAX_CLEARANCE", "Quitus fiscal", DocumentCategory.BUSINESS,
                false, false, false, null, null, 0)
        );
    }
    
    private List<DocumentRoleRequirement> getInitialRoleAssignments() {
        return Arrays.asList(
            // DRIVER - Documents obligatoires
            createAssignment("NATIONAL_ID", UserRole.DRIVER, true, 1),
            createAssignment("DRIVER_LICENSE", UserRole.DRIVER, true, 2),
            
            // DRIVER - Documents optionnels
            createAssignment("CRIMINAL_RECORD", UserRole.DRIVER, false, 3),
            createAssignment("RESIDENCE_CERTIFICATE", UserRole.DRIVER, false, 4),
            
            // DELIVERY - Documents obligatoires
            createAssignment("NATIONAL_ID", UserRole.DELIVERY, true, 1),
            
            // DELIVERY - Documents optionnels
            createAssignment("CRIMINAL_RECORD", UserRole.DELIVERY, false, 2),
            createAssignment("DRIVER_LICENSE", UserRole.DELIVERY, false, 3),
            createAssignment("RESIDENCE_CERTIFICATE", UserRole.DELIVERY, false, 4),
            
            // PHARMACY - Tous optionnels
            createAssignment("PHARMACY_LICENSE", UserRole.PHARMACY, false, 1),
            createAssignment("PHARMACIST_DIPLOMA", UserRole.PHARMACY, false, 2),
            createAssignment("PHARMACY_INSURANCE", UserRole.PHARMACY, false, 3),
            createAssignment("RCCM", UserRole.PHARMACY, false, 4),
            createAssignment("NIF", UserRole.PHARMACY, false, 5),
            createAssignment("PHARMACY_APPROVAL", UserRole.PHARMACY, false, 6),
            
            // RESTAURANT - Tous optionnels
            createAssignment("RESTAURANT_HEALTH_LICENSE", UserRole.RESTAURANT, false, 1),
            createAssignment("FIRE_SAFETY_CERTIFICATE", UserRole.RESTAURANT, false, 2),
            createAssignment("FOOD_HANDLING_CERTIFICATE", UserRole.RESTAURANT, false, 3),
            createAssignment("RCCM", UserRole.RESTAURANT, false, 4),
            createAssignment("NIF", UserRole.RESTAURANT, false, 5),
            
            // SUPERMARKET - Tous optionnels
            createAssignment("SUPERMARKET_LICENSE", UserRole.SUPERMARKET, false, 1),
            createAssignment("HEALTH_PERMIT", UserRole.SUPERMARKET, false, 2),
            createAssignment("RCCM", UserRole.SUPERMARKET, false, 3),
            createAssignment("NIF", UserRole.SUPERMARKET, false, 4),

            // ================================================================
            // Affectations des types versés depuis l'énumération
            // ================================================================
            // Le PERIMETRE de rôle est repris de l'énumération — qui pouvait
            // fournir quoi — mais PAS son caractère obligatoire. Tout est posé
            // à `false`, délibérément.
            //
            // L'énumération marquait assurance, carte grise, patente et contrat
            // de travail comme obligatoires. Les importer tels quels ajouterait
            // d'un coup des documents exigés qui ne l'étaient pas en base, et
            // bloquerait en pleine inscription des personnes qui n'en ont jamais
            // entendu parler. Rendre un document obligatoire est une décision
            // metier ; elle se prend maintenant par
            // PUT /admin/document-types/{code}/roles/{role}.
            //
            // MERCHANT et DISPATCHER n'avaient AUCUNE affectation : ces deux
            // rôles s'entendaient dire qu'ils n'avaient aucun document à
            // fournir. Ils en voient desormais la liste, sans contrainte.

            // DRIVER et DELIVERY - véhicule
            createAssignment("VEHICLE_INSURANCE", UserRole.DRIVER, false, 5),
            createAssignment("VEHICLE_REGISTRATION", UserRole.DRIVER, false, 6),
            createAssignment("DRIVER_MEDICAL_CERTIFICATE", UserRole.DRIVER, false, 7),
            createAssignment("VEHICLE_INSURANCE", UserRole.DELIVERY, false, 5),
            createAssignment("VEHICLE_REGISTRATION", UserRole.DELIVERY, false, 6),
            createAssignment("DRIVER_MEDICAL_CERTIFICATE", UserRole.DELIVERY, false, 7),

            // MERCHANT - premier jeu d'affectations de ce rôle
            createAssignment("RCCM", UserRole.MERCHANT, false, 1),
            createAssignment("NIF", UserRole.MERCHANT, false, 2),
            createAssignment("MERCHANT_PATENT", UserRole.MERCHANT, false, 3),
            createAssignment("MERCHANT_BUSINESS_PLAN", UserRole.MERCHANT, false, 4),

            // RESTAURANT - la patente, que l'énumération lui rattachait aussi
            createAssignment("MERCHANT_PATENT", UserRole.RESTAURANT, false, 6),

            // DISPATCHER - premier jeu d'affectations de ce rôle
            createAssignment("DISPATCHER_EMPLOYMENT_CONTRACT", UserRole.DISPATCHER, false, 1),
            createAssignment("DISPATCHER_BACKGROUND_CHECK", UserRole.DISPATCHER, false, 2)
        );
    }
    
    private DocumentTypeConfig createDocType(String code, String displayName, DocumentCategory category,
                                            boolean requiresNumber, boolean requiresExpiration, 
                                            boolean requiresCategory, List<String> availableCategories,
                                            List<String> categoriesWithoutExpiration, int validityYears) {
        
        DocumentTypeConfig config = DocumentTypeConfig.builder()
            .code(code)
            .displayName(displayName)
            .category(category)
            .requiresNumber(requiresNumber)
            .requiresExpirationDate(requiresExpiration)
            .requiresCategory(requiresCategory)
            .availableCategories(availableCategories)
            .categoriesWithoutExpiration(categoriesWithoutExpiration)
            .defaultValidityYears(validityYears)
            .acceptedMimeTypes(Arrays.asList("image/jpeg", "image/png", "image/jpg", "application/pdf"))
            .maxFileSizeMB(10)
            .isActive(true)
            .build();
        
        config.setCreatedBy("SYSTEM");
        return config;
    }
    
    private DocumentRoleRequirement createAssignment(String documentTypeCode, UserRole userRole, 
                                                     boolean mandatory, int displayOrder) {
        DocumentRoleRequirement assignment = DocumentRoleRequirement.builder()
            .documentTypeCode(documentTypeCode)
            .userRole(userRole)
            .mandatory(mandatory)
            .displayOrder(displayOrder)
            .isActive(true)
            .build();
        
        assignment.setCreatedBy("SYSTEM");
        return assignment;
    }
}
