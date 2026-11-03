package com.abdatytch.media_service.service;

import com.abdatytch.media_service.dto.request.DocumentUploadRequest;
import com.abdatytch.media_service.enums.UserRole;
import reactor.core.publisher.Mono;

/**
 * Service de validation des documents basé sur DocumentTypeConfig.
 *
 * <p>C'est le <b>seul</b> détenteur de la question « ce code de type existe-t-il,
 * est-il encore accepté, et ce rôle a-t-il le droit de le fournir ». L'ancien
 * chemin posait cette question à deux endroits qui ne se parlaient pas :
 * {@code DocumentType.valueOf} dans le contrôleur, et ce service en base.
 */
public interface DocumentTypeValidationService {

    /**
     * Résout un code de type contre le catalogue et le rôle de l'appelant.
     *
     * <p>Remplace {@code DocumentType.valueOf(code)}, qui ne consultait que
     * l'énumération compilée et rejetait donc tout type créé par l'API
     * d'administration.
     *
     * @return le type résolu, avec son caractère obligatoire pour ce rôle
     * @throws IllegalArgumentException si le code est inconnu du catalogue, si le
     *                                  type n'est plus actif, ou s'il n'est pas
     *                                  applicable à ce rôle
     */
    Mono<TypeDeDocumentResolu> resoudre(String documentTypeCode, UserRole userRole);

    /**
     * Valider les champs obligatoires d'un document selon sa configuration
     * @param documentTypeCode Code du type de document
     * @param request Requête d'upload contenant les champs
     * @return Mono<Void> qui échoue si validation invalide
     */
    Mono<Void> validateRequiredFields(String documentTypeCode, DocumentUploadRequest request);
    
    /**
     * Vérifier si un type de document existe et est actif
     * @param documentTypeCode Code du type de document
     * @return Mono<Boolean> true si existe et actif
     */
    Mono<Boolean> isDocumentTypeActive(String documentTypeCode);
}
