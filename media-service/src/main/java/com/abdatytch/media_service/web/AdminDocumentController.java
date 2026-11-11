package com.abdatytch.media_service.web;

import com.abdatytch.media_service.dto.response.DocumentDetailResponse;
import com.abdatytch.media_service.dto.response.DocumentListResponse;
import com.abdatytch.media_service.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/documents")
@RequiredArgsConstructor
@Slf4j
public class AdminDocumentController {

    private final DocumentService documentService;

    @PostMapping("/{documentId}/approve")
    public Mono<Void> approveDocument(
            @PathVariable String documentId,
            ServerWebExchange exchange) {

        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} approving document {}", adminId, documentId);

        return documentService.approveDocument(documentId, adminId)
            .doOnSuccess(v -> log.info("Document {} approved by admin {}", documentId, adminId))
            .doOnError(error -> log.error("Failed to approve document {}: {}", documentId, error.getMessage()));
    }

    @PostMapping("/{documentId}/reject")
    public Mono<Void> rejectDocument(
            @PathVariable String documentId,
            @RequestParam(required = false) String reason,
            ServerWebExchange exchange) {

        String adminId = exchange.getAttribute("userId");
        String rejectionReason = (reason != null) ? reason : "No reason provided";
        log.info("Admin {} rejecting document {} - reason: {}", adminId, documentId, rejectionReason);

        return documentService.rejectDocument(documentId, adminId, rejectionReason)
            .doOnSuccess(v -> log.info("Document {} rejected by admin {}", documentId, adminId))
            .doOnError(error -> log.error("Failed to reject document {}: {}", documentId, error.getMessage()));
    }

    @GetMapping("/pending")
    public Flux<DocumentListResponse> getPendingDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            ServerWebExchange exchange) {

        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} fetching pending documents - page: {}, size: {}", adminId, page, size);

        return documentService.getPendingDocuments()
            .skip((long) page * size)
            .take(size)
            .doOnComplete(() -> log.info("Returned pending documents for admin {}", adminId))
            .doOnError(error -> log.error("Failed to fetch pending documents: {}", error.getMessage()));
    }

    @GetMapping("/all")
    public Flux<DocumentListResponse> getAllDocuments(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            ServerWebExchange exchange) {

        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} fetching all documents - status: {}, userId: {}", adminId, status, userId);

        return documentService.getAllDocuments(status, userId)
            .skip((long) page * size)
            .take(size)
            .doOnComplete(() -> log.info("Returned all documents for admin {}", adminId))
            .doOnError(error -> log.error("Failed to fetch all documents: {}", error.getMessage()));
    }

    @GetMapping("/stats")
    public Mono<java.util.Map<String, Object>> getDocumentStats(ServerWebExchange exchange) {
        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} fetching document statistics", adminId);

        return documentService.getDocumentStats()
            .doOnSuccess(stats -> log.info("Returned statistics for admin {}", adminId))
            .doOnError(error -> log.error("Failed to fetch statistics: {}", error.getMessage()));
    }

    @GetMapping("/user/{userId}")
    public Flux<DocumentListResponse> getUserDocuments(
            @PathVariable String userId,
            ServerWebExchange exchange) {

        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} fetching documents for user {}", adminId, userId);

        return documentService.getUserDocuments(userId)
            .doOnComplete(() -> log.info("Returned documents for user {} to admin {}", userId, adminId))
            .doOnError(error -> log.error("Failed to fetch user documents: {}", error.getMessage()));
    }

    @GetMapping("/{documentId}/details")
    public Mono<DocumentDetailResponse> getDocumentDetails(
            @PathVariable String documentId,
            ServerWebExchange exchange) {

        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} fetching details for document {}", adminId, documentId);

        return documentService.getDocumentDetailsAdmin(documentId)
            .doOnSuccess(details -> log.info("Returned document details to admin {}", adminId))
            .doOnError(error -> log.error("Failed to fetch document details: {}", error.getMessage()));
    }

    @GetMapping("/{documentId}/download")
    public Mono<Flux<DataBuffer>> downloadDocument(
            @PathVariable String documentId,
            ServerWebExchange exchange) {

        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} downloading document {}", adminId, documentId);

        return documentService.downloadDocumentAdmin(documentId)
            .doOnSuccess(data -> log.info("Document {} downloaded by admin {}", documentId, adminId))
            .doOnError(error -> log.error("Failed to download document {}: {}", documentId, error.getMessage()));
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteDocument(
            @PathVariable String documentId,
            ServerWebExchange exchange) {

        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} deleting document {}", adminId, documentId);

        return documentService.deleteDocumentAdmin(documentId, adminId)
            .doOnSuccess(v -> log.info("Document {} deleted by admin {}", documentId, adminId))
            .doOnError(error -> log.error("Failed to delete document {}: {}", documentId, error.getMessage()));
    }
}
