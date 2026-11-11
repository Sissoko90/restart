package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.event.DocumentEvent;
import com.abdatytch.media_service.service.DocumentEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Implémentation du service de publication d'événements Kafka
 * Envoie des notifications pour chaque changement de statut de document
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentEventPublisherImpl implements DocumentEventPublisher {

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.topics.document-events:document-events}")
    private String documentEventsTopic;

    @Value("${spring.kafka.enabled:true}")
    private boolean kafkaEnabled;

    @Override
    public Mono<Void> publishDocumentUploaded(MediaFile document, String ipAddress, String userAgent) {
        DocumentEvent event = DocumentEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("DOCUMENT_UPLOADED")
            .documentId(document.getId())
            .userId(document.getUserId())
            .documentTypeCode(document.getDocumentTypeCode())
            .status(document.getStatus())
            .fileName(document.getFileName())
            .timestamp(LocalDateTime.now())
            .qualityScore(document.getQualityScore())
            .securityCheckPassed(document.getSecurityCheckPassed())
            .ipAddress(ipAddress)
            .userAgent(userAgent)
            .build();

        return publishEvent(event);
    }

    @Override
    public Mono<Void> publishDocumentApproved(MediaFile document, String reviewedBy) {
        DocumentEvent event = DocumentEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("DOCUMENT_APPROVED")
            .documentId(document.getId())
            .userId(document.getUserId())
            .documentTypeCode(document.getDocumentTypeCode())
            .status(document.getStatus())
            .fileName(document.getFileName())
            .timestamp(LocalDateTime.now())
            .reviewedBy(reviewedBy)
            .build();

        return publishEvent(event);
    }

    @Override
    public Mono<Void> publishDocumentRejected(MediaFile document, String reviewedBy, String reason) {
        DocumentEvent event = DocumentEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("DOCUMENT_REJECTED")
            .documentId(document.getId())
            .userId(document.getUserId())
            .documentTypeCode(document.getDocumentTypeCode())
            .status(document.getStatus())
            .fileName(document.getFileName())
            .timestamp(LocalDateTime.now())
            .reviewedBy(reviewedBy)
            .rejectionReason(reason)
            .build();

        return publishEvent(event);
    }

    @Override
    public Mono<Void> publishDocumentDeleted(MediaFile document, String userId) {
        DocumentEvent event = DocumentEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("DOCUMENT_DELETED")
            .documentId(document.getId())
            .userId(userId)
            .documentTypeCode(document.getDocumentTypeCode())
            .status(document.getStatus())
            .fileName(document.getFileName())
            .timestamp(LocalDateTime.now())
            .build();

        return publishEvent(event);
    }

    @Override
    public Mono<Void> publishDocumentExpired(MediaFile document) {
        DocumentEvent event = DocumentEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("DOCUMENT_EXPIRED")
            .documentId(document.getId())
            .userId(document.getUserId())
            .documentTypeCode(document.getDocumentTypeCode())
            .status(document.getStatus())
            .fileName(document.getFileName())
            .timestamp(LocalDateTime.now())
            .build();

        return publishEvent(event);
    }

    @Override
    public Mono<Void> publishEvent(DocumentEvent event) {
        if (!kafkaEnabled) {
            log.debug("Kafka disabled, skipping event publication: {}", event.getEventType());
            return Mono.empty();
        }

        return Mono.fromCallable(() -> objectMapper.writeValueAsString(event))
            .flatMap(json -> {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    documentEventsTopic, 
                    event.getDocumentId(), 
                    json
                );
                return kafkaSender.send(Mono.just(SenderRecord.create(record, event.getEventId())))
                    .next();
            })
            .doOnSuccess(result -> log.info("Event published - type: {}, documentId: {}, userId: {}",
                event.getEventType(), event.getDocumentId(), event.getUserId()))
            .doOnError(error -> log.error("Failed to publish event - type: {}, documentId: {}",
                event.getEventType(), event.getDocumentId(), error))
            .then()
            .onErrorResume(error -> Mono.empty()); // Ne pas bloquer si Kafka est down
    }
}
