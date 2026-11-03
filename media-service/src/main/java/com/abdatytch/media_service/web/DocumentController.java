package com.abdatytch.media_service.web;

import com.abdatytch.media_service.dto.request.DocumentUploadRequest;
import com.abdatytch.media_service.dto.response.DocumentDetailResponse;
import com.abdatytch.media_service.dto.response.DocumentListResponse;
import com.abdatytch.media_service.dto.response.DocumentUploadResponse;
import com.abdatytch.media_service.dto.response.UserDocumentStatusResponse;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.service.DocumentRequirementService;
import com.abdatytch.media_service.service.DocumentService;
import com.abdatytch.media_service.service.StreamingUploadService;
import com.abdatytch.media_service.service.DocumentTypeValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
@Slf4j
@RestController
@RequestMapping("/media")
public class DocumentController {

    private final DocumentService documentService;
    private final StreamingUploadService streamingUploadService;
    private final DocumentRequirementService documentRequirementService;
    private final DocumentTypeValidationService documentTypeValidationService;

    /**
     * Le rôle porté par le jeton, sans jamais lever.
     *
     * <h3>Le défaut que ce repli ferme</h3>
     *
     * Le code était {@code UserRole.valueOf(roleStr.replace("ROLE_", ""))}, nu.
     * Or la copie de {@code UserRole} de ce module ne connaissait que neuf
     * valeurs sur les douze qu'émet auth-service : un <b>super administrateur
     * qui envoyait un document levait une {@code IllegalArgumentException}</b>,
     * et un locataire ou un développeur tiers de même.
     *
     * <p>La parité est rétablie, donc le cas ne devrait plus se produire. Ce
     * repli reste comme garde-fou : les deux listes sont tenues à la main, et
     * celle d'auth-service peut gagner une valeur avant celle-ci.
     *
     * <p>Le repli va vers {@code USER}, c'est-à-dire vers <b>moins</b> de
     * privilèges. Un rôle non reconnu ne doit jamais ouvrir plus de portes que
     * prévu — au pire il en ferme, ce qui se constate et se corrige.
     */
    private UserRole roleDuJeton(String role) {
        if (role == null || role.isBlank()) {
            return UserRole.USER;
        }
        try {
            return UserRole.valueOf(role.replace("ROLE_", ""));
        } catch (IllegalArgumentException inconnu) {
            log.warn(" Role inconnu dans le jeton : {} — traite comme USER", role);
            return UserRole.USER;
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Upload un document",
        description = "Upload un document avec validation, détection malware, analyse qualité, chiffrement AES-256-GCM et stockage MinIO. Limite: 10 requêtes/minute")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Document uploadé avec succès"),
        @ApiResponse(responseCode = "400", description = "Type de fichier invalide ou document de mauvaise qualité"),
        @ApiResponse(responseCode = "403", description = "Malware détecté ou accès non autorisé"),
        @ApiResponse(responseCode = "429", description = "Rate limit dépassé")
    })
    public Mono<DocumentUploadResponse> uploadDocument(
            @RequestPart("file") FilePart filePart,
            @RequestPart(value = "documentTypeCode") String documentTypeCode,
            @RequestPart(value = "documentNumber", required = false) String documentNumber,
            @RequestPart(value = "documentCategory", required = false) String documentCategory,
            @RequestPart(value = "expiryDate", required = false) String expiryDate,
            @RequestPart(value = "description", required = false) String description,
            ServerWebExchange exchange) {

        log.info(" Upload request - file: {}, type: {}, number: {}, category: {}",
            filePart.filename(), documentTypeCode, documentNumber, documentCategory);

        String userId = exchange.getAttribute("userId");
        if (userId == null) {
            log.error(" No userId found in exchange attributes");
            return Mono.error(new IllegalStateException("User ID not found"));
        }

        String roleStr = exchange.getAttribute("userRole");
        UserRole userRole = roleDuJeton(roleStr);

        String ipAddress = exchange.getRequest().getRemoteAddress() != null
            ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
            : "unknown";
        String userAgent = exchange.getRequest().getHeaders().getFirst("User-Agent");

        // Le `DocumentType.valueOf(documentTypeCode)` qui se trouvait ici a ete
        // RETIRE. C'etait le verrou de toute cette migration : il confrontait le
        // code a l'enumeration COMPILEE, si bien qu'un type cree par
        // POST /admin/document-types s'ecrivait bien en base et rendait 400 au
        // premier envoi. Seuls 5 codes sur 25 et 17 figuraient dans les deux
        // catalogues — un chauffeur ne pouvait pas fournir sa piece d'identite,
        // un supermarche aucun de ses quatre documents.
        //
        // La resolution appartient desormais a
        // DocumentTypeValidationService.resoudre, qui lit le catalogue et
        // confronte le type au role de l'appelant.

        DocumentUploadRequest request = DocumentUploadRequest.builder()
            .documentTypeCode(documentTypeCode)
            .userRole(userRole)
            .documentNumber(documentNumber)
            .documentCategory(documentCategory)
            .expiryDate(expiryDate != null ? java.time.LocalDate.parse(expiryDate) : null)
            .description(description)
            .build();

        // Resoudre le type contre le catalogue ET le role, puis valider les
        // champs obligatoires que sa configuration exige.
        return documentTypeValidationService.resoudre(documentTypeCode, userRole)
            .flatMap(type -> documentTypeValidationService
                .validateRequiredFields(documentTypeCode, request)
                .then(documentService.uploadDocument(
                    userId, userRole, type, filePart, request, ipAddress, userAgent)))
            .doOnSuccess(response -> log.info(" Document uploaded - ID: {}", response.getDocumentId()))
            .doOnError(error -> log.error(" Upload failed: {}", error.getMessage()));
    }

    @GetMapping("/download/{documentId}")
    public Mono<Flux<DataBuffer>> downloadDocument(
            @PathVariable String documentId,
            ServerWebExchange exchange) {

        String userId = exchange.getAttribute("userId");
        return documentService.downloadDocument(documentId, userId);
    }

    @GetMapping("/{documentId}")
    public Mono<DocumentDetailResponse> getDocumentDetails(
            @PathVariable String documentId,
            ServerWebExchange exchange) {

        String userId = exchange.getAttribute("userId");
        return documentService.getDocumentDetails(documentId, userId);
    }

    @GetMapping("/user/documents")
    public Flux<DocumentListResponse> getUserDocuments(ServerWebExchange exchange) {
        String userId = exchange.getAttribute("userId");
        return documentService.getUserDocuments(userId);
    }

    @GetMapping("/user/status")
    public Mono<UserDocumentStatusResponse> getUserDocumentStatus(
            @RequestParam(required = false) UserRole userRole,
            ServerWebExchange exchange) {

        String userId = exchange.getAttribute("userId");

        // Si userRole n'est pas fourni, l'extraire du JWT
        if (userRole == null) {
            String roleStr = exchange.getAttribute("userRole");
            userRole = roleStr != null ? UserRole.valueOf(roleStr.replace("ROLE_", "")) : UserRole.USER;
        }

        log.info(" User {} fetching missing documents for role {}", userId, userRole);

        return documentRequirementService.getMissingDocuments(userId, userRole)
            .doOnSuccess(status -> log.info(" Missing documents returned: {} pending", status.getPending()))
            .doOnError(error -> log.error(" Failed to fetch missing documents: {}", error.getMessage()));
    }

    @GetMapping("/requirements")
    public Mono<UserDocumentStatusResponse> getDocumentRequirements(
            @RequestParam(required = false) UserRole userRole,
            ServerWebExchange exchange) {

        String userId = exchange.getAttribute("userId");

        // Si userRole n'est pas fourni, l'extraire du JWT
        if (userRole == null) {
            String roleStr = exchange.getAttribute("userRole");
            userRole = roleStr != null ? UserRole.valueOf(roleStr.replace("ROLE_", "")) : UserRole.USER;
        }

        log.info(" User {} fetching document requirements for role {}", userId, userRole);

        return documentRequirementService.getUserDocumentStatus(userId, userRole)
            .doOnSuccess(status -> log.info(" Document requirements returned for user {}", userId))
            .doOnError(error -> log.error(" Failed to fetch requirements: {}", error.getMessage()));
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteDocument(
            @PathVariable String documentId,
            ServerWebExchange exchange) {

        String userId = exchange.getAttribute("userId");
        return documentService.deleteDocument(documentId, userId);
    }
}
