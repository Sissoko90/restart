package com.abdatytch.media_service.service;

import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.event.DocumentEvent;
import reactor.core.publisher.Mono;

/**
 * Service de publication d'événements Kafka pour notifications
 */
public interface DocumentEventPublisher {

    /**
     * Publie un événement d'upload de document
     */
    Mono<Void> publishDocumentUploaded(MediaFile document, String ipAddress, String userAgent);

    /**
     * Publie un événement d'approbation de document
     */
    Mono<Void> publishDocumentApproved(MediaFile document, String reviewedBy);

    /**
     * Publie un événement de rejet de document
     */
    Mono<Void> publishDocumentRejected(MediaFile document, String reviewedBy, String reason);

    /**
     * Publie un événement de suppression de document
     */
    Mono<Void> publishDocumentDeleted(MediaFile document, String userId);

    /**
     * Publie un événement d'expiration de document
     */
    Mono<Void> publishDocumentExpired(MediaFile document);

    /**
     * Publie un événement générique
     */
    Mono<Void> publishEvent(DocumentEvent event);
}
