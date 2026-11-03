package com.abdatytch.quotas_service.dto.response;

import com.abdatytch.quotas_service.enums.QuotaTier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * État complet des quotas d'un utilisateur : palier + consommation de chaque ressource.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaStatusResponse {

    private String userId;

    private QuotaTier tier;

    private List<ResourceUsageResponse> resources;

    /** Début de la fenêtre horaire courante pour le compteur d'appels API. */
    private Instant apiCallsWindowStart;

    private Instant updatedAt;
}
