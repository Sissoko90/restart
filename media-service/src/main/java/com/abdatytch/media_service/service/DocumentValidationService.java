package com.abdatytch.media_service.service;

import com.abdatytch.media_service.enums.UserRole;
import reactor.core.publisher.Mono;

/**
 * Le plafond de tentatives d'envoi, et rien d'autre.
 *
 * <h2>Ce que ce service portait en trop</h2>
 *
 * Il déclarait huit méthodes. <b>Une seule</b> était appelée —
 * {@code canUserUploadDocument}, depuis {@code DocumentServiceImpl}. Les sept
 * autres répondaient à « quels documents ce rôle doit-il fournir », exactement
 * la question que {@code DocumentRequirementService} traite déjà, et qu'il
 * traite <b>depuis la base</b> alors que celles-ci répondaient depuis
 * l'énumération compilée.
 *
 * <p>Deux implémentations d'une même question, dont une seule alimentée par le
 * catalogue réel : c'est le motif que ce dépôt connaît sous « deux magasins pour
 * un même état ». Les sept méthodes mortes sont retirées plutôt que migrées —
 * migrer un doublon le rend durable.
 *
 * <h2>Ce qu'il garde, et pourquoi c'est à lui</h2>
 *
 * Le comptage des tentatives est une règle propre à l'<b>envoi</b> : dix essais
 * ratés sur un même document, et il faut passer par le support. Elle n'a rien à
 * voir avec le catalogue, et c'est pourquoi elle ne part pas avec le reste.
 *
 * <p>L'applicabilité au rôle, elle, a déménagé chez
 * {@link DocumentTypeValidationService#resoudre} : elle se décide en lisant le
 * catalogue, donc au même endroit que lui.
 */
public interface DocumentValidationService {

    /**
     * L'utilisateur a-t-il encore droit à une tentative sur ce document ?
     *
     * @param documentTypeCode le code du catalogue, et non une valeur
     *                         d'énumération : le type a déjà été résolu et
     *                         confronté au rôle en amont
     */
    Mono<ValidationResult> canUserUploadDocument(String userId, UserRole userRole,
                                                 String documentTypeCode);

    /** Résultat de validation. */
    record ValidationResult(
        boolean canUpload,
        String reason,
        int uploadAttempts,
        int maxAttempts
    ) {}
}
