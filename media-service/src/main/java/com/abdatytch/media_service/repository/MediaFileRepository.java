package com.abdatytch.media_service.repository;

import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.enums.DocumentStatus;
import com.abdatytch.media_service.enums.UserRole;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * Repository réactif pour les documents utilisateur
 */
@Repository
public interface MediaFileRepository extends ReactiveMongoRepository<MediaFile, String> {

    // Recherche par utilisateur
    Flux<MediaFile> findByUserId(String userId);
    
    Flux<MediaFile> findByUserIdOrderByCreatedAtDesc(String userId);

    // Les quatre requetes typees sur l'enumeration DocumentType ont ete
    // RETIREES : elles n'avaient aucun appelant depuis que le catalogue vit en
    // base. Les reecrire sur `documentTypeCode` aurait ete du code mort neuf.
    // Spring Data derive une requete de son nom : le jour ou l'une redevient
    // necessaire, il suffira de declarer
    // `findByUserIdAndDocumentTypeCode(String, String)`.

    // Recherche par statut
    Flux<MediaFile> findByStatus(DocumentStatus status);
    
    Flux<MediaFile> findByUserIdAndStatus(String userId, DocumentStatus status);

    // Recherche par rôle
    Flux<MediaFile> findByUserRole(UserRole userRole);
    
    Flux<MediaFile> findByUserIdAndUserRole(String userId, UserRole userRole);

    // Documents en attente de validation
    Flux<MediaFile> findByStatusOrderByCreatedAtAsc(DocumentStatus status);

    // Documents expirés
    Flux<MediaFile> findByExpiryDateBeforeAndStatus(LocalDate date, DocumentStatus status);

    // Documents avec problèmes de sécurité
    Flux<MediaFile> findBySecurityCheckPassedFalse();
    
    Flux<MediaFile> findByUserIdAndSecurityCheckPassedFalse(String userId);

    // Documents par hash (détection duplicatas)
    Mono<MediaFile> findByFileHash(String fileHash);
    
    Flux<MediaFile> findByFileHashAndUserIdNot(String fileHash, String userId);

    // Vérifications d'existence
    Mono<Boolean> existsByFileHash(String fileHash);

    // Comptage
    Mono<Long> countByUserId(String userId);

    Mono<Long> countByUserIdAndStatus(String userId, DocumentStatus status);

    /**
     * Comptage global par statut, pour la jauge de la file de validation.
     *
     * <p>Voir {@code JaugeFileDeValidation}, qui appelle ce comptage avec
     * {@code UPLOADED} — <b>le meme statut</b> que
     * {@code findByStatusOrderByCreatedAtAsc} derriere l'ecran
     * {@code /admin/documents/pending}. Les deux definitions doivent rester
     * identiques : une jauge qui compterait autre chose que ce que voit
     * l'administrateur serait pire qu'une absence de jauge.
     */
    Mono<Long> countByStatus(DocumentStatus status);
}
