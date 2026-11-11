package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.entity.UserDocumentRequirement;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.repository.UserDocumentRequirementRepository;
import com.abdatytch.media_service.service.DocumentValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Le plafond de tentatives, seule règle que ce service garde.
 *
 * <p>Sept méthodes ont été retirées : elles répondaient depuis l'énumération
 * compilée à des questions que {@code DocumentRequirementService} traite déjà
 * depuis le catalogue en base, et aucune n'était appelée.
 */
@Service
@RefreshScope
@Slf4j
@RequiredArgsConstructor
public class DocumentValidationServiceImpl implements DocumentValidationService {

    private final UserDocumentRequirementRepository requirementRepository;

    @Value("${media.upload.max-attempts:10}")
    private int maxUploadAttempts;

    /**
     * Compte les tentatives déjà faites sur ce document.
     *
     * <h3>Deux détails qui comptent</h3>
     *
     * {@code getUploadAttempts()} peut être <b>nul</b> sur une exigence créée
     * avant que ce compteur existe. Un {@code map} dont la fonction rend
     * {@code null} lève une {@code NullPointerException} dans Reactor, elle ne
     * produit pas un {@code Mono} vide — d'où la substitution explicite.
     *
     * <p>Et {@code defaultIfEmpty} est posé sur le {@code Mono<Integer>}, non sur
     * le résultat : c'est le seul endroit où il porte. L'ancienne version
     * construisait pour cela une exigence par défaut complète qu'elle ne
     * sauvegardait jamais — un objet entier pour en lire un zéro.
     */
    @Override
    public Mono<ValidationResult> canUserUploadDocument(String userId, UserRole userRole,
                                                        String documentTypeCode) {

        return requirementRepository.findByUserIdAndDocumentTypeCode(userId, documentTypeCode)
            .map(DocumentValidationServiceImpl::tentatives)
            .defaultIfEmpty(0)
            .map(tentatives -> {
                if (tentatives >= maxUploadAttempts) {
                    log.warn("Plafond de tentatives atteint - utilisateur {}, type {}, {} essais",
                            userId, documentTypeCode, tentatives);
                    return new ValidationResult(false, String.format(
                            "Nombre maximum de tentatives atteint (%d). Contactez le support.",
                            maxUploadAttempts), tentatives, maxUploadAttempts);
                }
                return new ValidationResult(true, "Validation réussie",
                        tentatives, maxUploadAttempts);
            });
    }

    private static int tentatives(UserDocumentRequirement exigence) {
        return exigence.getUploadAttempts() != null ? exigence.getUploadAttempts() : 0;
    }
}
