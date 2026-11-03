package com.abdatytch.quotas_service.dto.response;

import com.abdatytch.quotas_service.enums.QuotaResource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Réponse à une vérification de quota : la question posée par interface-service
 * avant de créer une ressource.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaCheckResponse {

    private String userId;

    private QuotaResource resource;

    /** true si la quantité demandée peut être consommée. */
    private boolean allowed;

    private long used;

    private long limit;

    private long requested;

    /** Reste disponible après la demande (0 si refusée). */
    private long remaining;

    /** Pourcentage du quota consommé, arrondi à l'entier. */
    private int usagePercent;

    /** true si le seuil d'alerte (par défaut 80 %) est atteint. */
    private boolean warning;
}
