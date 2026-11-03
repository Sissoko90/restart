package com.abdatytch.quotas_service.service;

import com.abdatytch.quotas_service.dto.response.QuotaCheckResponse;
import com.abdatytch.quotas_service.dto.response.QuotaStatusResponse;
import com.abdatytch.quotas_service.enums.QuotaResource;
import com.abdatytch.quotas_service.enums.QuotaTier;
import reactor.core.publisher.Mono;

/**
 * Gestion des quotas : consultation, vérification, consommation, libération.
 *
 * <p>Modèle d'application retenu (voir docs/QUOTAS.md) :
 * <ul>
 *   <li><b>vérification synchrone</b> : l'appelant interroge {@link #check} avant
 *       de créer une ressource, et reçoit un refus immédiat en cas de dépassement ;</li>
 *   <li><b>consommation asynchrone</b> : le compteur est incrémenté à la réception
 *       de l'événement Kafka émis après création effective.</li>
 * </ul>
 */
public interface IQuotaService {

    /**
     * État complet des quotas d'un utilisateur (palier + consommation par ressource).
     * Crée un enregistrement au palier par défaut si l'utilisateur est inconnu.
     */
    Mono<QuotaStatusResponse> getStatus(String userId);

    /**
     * Vérifie si {@code amount} unités de {@code resource} peuvent être consommées.
     * Ne modifie aucun compteur.
     */
    Mono<QuotaCheckResponse> check(String userId, QuotaResource resource, long amount);

    /**
     * Consomme {@code amount} unités de {@code resource}.
     *
     * @throws com.abdatytch.quotas_service.exception.QuotaExceededException si le quota serait dépassé
     */
    Mono<QuotaCheckResponse> consume(String userId, QuotaResource resource, long amount);

    /**
     * Libère {@code amount} unités de {@code resource} (suppression d'un projet, etc.).
     * Le compteur ne descend jamais sous zéro.
     */
    Mono<QuotaCheckResponse> release(String userId, QuotaResource resource, long amount);

    /**
     * Change le palier commercial d'un utilisateur. Réservé aux administrateurs.
     */
    Mono<QuotaStatusResponse> updateTier(String userId, QuotaTier tier);

    /**
     * Remet à zéro toute la consommation d'un utilisateur. Réservé aux administrateurs.
     */
    Mono<QuotaStatusResponse> resetConsumption(String userId);
}
