package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.dto.request.DocumentUploadRequest;
import com.abdatytch.media_service.entity.DocumentRoleRequirement;
import com.abdatytch.media_service.entity.DocumentTypeConfig;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.repository.DocumentRoleRequirementRepository;
import com.abdatytch.media_service.repository.DocumentTypeConfigRepository;
import com.abdatytch.media_service.service.DocumentTypeValidationService;
import com.abdatytch.media_service.service.TypeDeDocumentResolu;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentTypeValidationServiceImpl implements DocumentTypeValidationService {

    private final DocumentTypeConfigRepository documentTypeConfigRepository;
    private final DocumentRoleRequirementRepository roleRequirementRepository;

    /**
     * Résout un code contre le catalogue, puis contre le rôle.
     *
     * <h3>Un type sans affectation active est UNIVERSEL</h3>
     *
     * C'est la décision la plus lourde de cette migration, et elle préserve le
     * comportement d'avant. L'énumération exprimait la même chose par
     * {@code requiredForRoles.isEmpty()} : une pièce d'identité n'était
     * rattachée à aucun rôle et restait donc fournissable par tous.
     *
     * <p>Appliquer l'inverse — « aucune affectation, donc personne » — rendrait
     * d'un coup inutilisables tous les types que le catalogue n'assigne pas, et
     * le seeder n'assigne {@code NATIONAL_ID} qu'à deux rôles sur neuf.
     *
     * <p>La règle porte sur les affectations <b>actives</b> : un type est
     * restreint aux rôles qui en ont une, et ouvert à tous quand il n'en a
     * aucune. Désactiver la dernière affectation rouvre donc le type au lieu de
     * le fermer — c'est cohérent avec « restreindre est un acte explicite », et
     * cela évite qu'une désactivation bloque silencieusement un dépôt.
     */
    @Override
    public Mono<TypeDeDocumentResolu> resoudre(String documentTypeCode, UserRole userRole) {
        if (documentTypeCode == null || documentTypeCode.isBlank()) {
            return Mono.error(new IllegalArgumentException("Aucun type de document fourni"));
        }

        return documentTypeConfigRepository.findByCode(documentTypeCode)
            .switchIfEmpty(Mono.error(new IllegalArgumentException(String.format(
                "Type de document inconnu : '%s'", documentTypeCode))))
            .flatMap(config -> {
                if (!Boolean.TRUE.equals(config.getIsActive())) {
                    return Mono.error(new IllegalArgumentException(String.format(
                        "Le type de document '%s' n'est plus accepté", config.getDisplayName())));
                }

                return roleRequirementRepository.findByDocumentTypeCode(documentTypeCode)
                    .filter(affectation -> Boolean.TRUE.equals(affectation.getIsActive()))
                    .collectList()
                    // La CONFIG entiere est passee, et non ses deux champs de
                    // libelle : elle porte aussi les limites de type MIME et de
                    // taille, que le pipeline doit faire respecter et qui
                    // n'etaient lues par personne.
                    .flatMap(affectations -> resoudreContreLeRole(config, userRole, affectations));
            });
    }

    private Mono<TypeDeDocumentResolu> resoudreContreLeRole(
            DocumentTypeConfig config, UserRole userRole,
            List<DocumentRoleRequirement> affectations) {

        String code = config.getCode();
        String libelle = config.getDisplayName();

        if (affectations.isEmpty()) {
            // Type universel : aucune affectation ne le restreint.
            return Mono.just(resolu(config, false));
        }

        return affectations.stream()
            .filter(affectation -> affectation.getUserRole() == userRole)
            .findFirst()
            .map(affectation -> Mono.just(
                resolu(config, Boolean.TRUE.equals(affectation.getMandatory()))))
            .orElseGet(() -> Mono.error(new IllegalArgumentException(String.format(
                "Le document '%s' n'est pas applicable au rôle %s", libelle, userRole))));
    }

    /**
     * Construit le type résolu, limites comprises.
     *
     * <p>Un seul endroit pour les deux chemins — universel et affecté. Les
     * construire separement etait l'occasion d'oublier une limite dans l'un des
     * deux, ce qui l'aurait rendue inoperante pour les types universels sans que
     * rien ne le signale.
     */
    private TypeDeDocumentResolu resolu(DocumentTypeConfig config, boolean obligatoire) {
        return new TypeDeDocumentResolu(
                config.getCode(),
                config.getDisplayName(),
                obligatoire,
                config.getAcceptedMimeTypes(),
                config.getMaxFileSizeMB());
    }

    @Override
    public Mono<Void> validateRequiredFields(String documentTypeCode, DocumentUploadRequest request) {
        return documentTypeConfigRepository.findByCode(documentTypeCode)
            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                String.format("Type de document '%s' non trouvé dans la configuration", documentTypeCode))))
            .flatMap(config -> {
                List<String> missingFields = new ArrayList<>();
                
                // Vérifier si le document est actif
                if (!config.getIsActive()) {
                    return Mono.error(new IllegalArgumentException(
                        String.format("Le type de document '%s' n'est plus accepté", config.getDisplayName())));
                }
                
                // Vérifier le numéro de document si requis
                if (Boolean.TRUE.equals(config.getRequiresNumber()) && 
                    (request.getDocumentNumber() == null || request.getDocumentNumber().trim().isEmpty())) {
                    missingFields.add("documentNumber (numéro du document)");
                }
                
                // Vérifier la catégorie si requise
                if (Boolean.TRUE.equals(config.getRequiresCategory())) {
                    if (request.getDocumentCategory() == null || request.getDocumentCategory().trim().isEmpty()) {
                        missingFields.add("documentCategory (catégorie du document)");
                    } else if (config.getAvailableCategories() != null && 
                               !config.getAvailableCategories().contains(request.getDocumentCategory())) {
                        return Mono.error(new IllegalArgumentException(
                            String.format("Catégorie invalide '%s'. Catégories autorisées: %s", 
                                request.getDocumentCategory(), 
                                String.join(", ", config.getAvailableCategories()))));
                    }
                }
                
                // Vérifier la date d'expiration
                if (Boolean.TRUE.equals(config.getRequiresExpirationDate())) {
                    // Vérifier si la catégorie est exemptée d'expiration
                    boolean categoryExempted = false;
                    if (config.getCategoriesWithoutExpiration() != null && 
                        request.getDocumentCategory() != null) {
                        categoryExempted = config.getCategoriesWithoutExpiration()
                            .contains(request.getDocumentCategory());
                    }
                    
                    if (!categoryExempted && request.getExpiryDate() == null) {
                        if (request.getDocumentCategory() != null && 
                            config.getCategoriesWithoutExpiration() != null) {
                            missingFields.add(String.format(
                                "expiryDate (date d'expiration - requise pour la catégorie '%s'. Catégories sans expiration: %s)",
                                request.getDocumentCategory(),
                                String.join(", ", config.getCategoriesWithoutExpiration())));
                        } else {
                            missingFields.add("expiryDate (date d'expiration)");
                        }
                    }
                }
                
                // Si des champs manquent, retourner une erreur détaillée
                if (!missingFields.isEmpty()) {
                    String errorMessage = String.format(
                        "Champs obligatoires manquants pour '%s': %s", 
                        config.getDisplayName(),
                        String.join(", ", missingFields));
                    log.warn(" Validation failed: {}", errorMessage);
                    return Mono.error(new IllegalArgumentException(errorMessage));
                }
                
                log.debug(" Validation passed for document type: {}", documentTypeCode);
                return Mono.empty();
            });
    }
    
    @Override
    public Mono<Boolean> isDocumentTypeActive(String documentTypeCode) {
        return documentTypeConfigRepository.findByCode(documentTypeCode)
            .map(config -> Boolean.TRUE.equals(config.getIsActive()))
            .defaultIfEmpty(false);
    }
}
