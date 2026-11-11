package com.abdatytch.media_service.scheduler;

import com.abdatytch.media_service.enums.DocumentStatus;
import com.abdatytch.media_service.repository.MediaFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**

 * Scheduler pour marquer automatiquement les documents expirés
 * S'exécute tous les jours à 2h du matin
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DocumentExpirationScheduler {

    private final MediaFileRepository mediaFileRepository;

    @Scheduled(cron = "${scheduler.expiration.cron:0 0 2 * * *}")
    public void markExpiredDocuments() {
        log.info("Starting document expiration check...");

        LocalDate today = LocalDate.now();

        mediaFileRepository.findByExpiryDateBeforeAndStatus(today, DocumentStatus.APPROVED)
            .flatMap(document -> {
                log.info("Marking document as EXPIRED - documentId: {}, userId: {}, expiryDate: {}",
                    document.getId(), document.getUserId(), document.getExpiryDate());

                document.setStatus(DocumentStatus.EXPIRED);
                return mediaFileRepository.save(document);
            })
            .count()
            .doOnSuccess(count -> {
                if (count > 0) {
                    log.info("Marked {} document(s) as EXPIRED", count);
                } else {
                    log.debug("No expired documents found");
                }
            })
            .doOnError(error -> log.error("Failed to mark expired documents", error))
            .subscribe();
    }

    @Scheduled(cron = "0 0 3 * * *") // 3h du matin tous les jours
    public void cleanupSoftDeletedDocuments() {
        log.info("Starting soft-deleted documents cleanup...");

        LocalDate cutoffDate = LocalDate.now().minusDays(90); // 90 jours de rétention

        mediaFileRepository.findAll()
            .filter(doc -> doc.getDeletedAt() != null)
            .filter(doc -> doc.getDeletedAt().toLocalDate().isBefore(cutoffDate))
            .flatMap(document -> {
                log.info("Permanently deleting document - documentId: {}, deletedAt: {}",
                    document.getId(), document.getDeletedAt());

                return mediaFileRepository.delete(document);
            })
            .count()
            .doOnSuccess(count -> {
                if (count > 0) {
                    log.info("Permanently deleted {} document(s)", count);
                } else {
                    log.debug("No documents to permanently delete");
                }
            })
            .doOnError(error -> log.error("Failed to cleanup soft-deleted documents", error))
            .subscribe();
    }
}
