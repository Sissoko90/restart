package com.abdatytch.commons.erasure;

import java.time.Instant;
import java.util.UUID;

/**
 * Accusé d'effacement émis par un service ayant traité une
 * {@link UserErasureRequestedEvent}.
 *
 * <p>Ces accusés constituent le registre d'exécution. L'article 5.2 du RGPD
 * impose au responsable de traitement de pouvoir <b>démontrer</b> la conformité :
 * affirmer avoir effacé sans pouvoir le prouver ne suffit pas.
 *
 * <p>Un service qui ne détient aucune donnée de la personne accuse quand même,
 * avec {@code recordsErased = 0}. C'est ce qui distingue « rien à effacer » de
 * « service qui n'a jamais répondu » — la seconde situation exige une reprise,
 * la première non.
 *
 * @param requestId     reprend celui de la demande
 * @param service       nom du service émetteur ({@code spring.application.name})
 * @param completedAt   horodatage de l'effacement
 * @param recordsErased nombre d'enregistrements effacés ou anonymisés
 * @param details       ce qui a été traité, pour l'audit — jamais de donnée personnelle
 */
public record ErasureCompletedEvent(
        UUID requestId,
        String service,
        Instant completedAt,
        int recordsErased,
        String details
) {

    public static ErasureCompletedEvent of(UUID requestId, String service,
                                           int recordsErased, String details) {
        return new ErasureCompletedEvent(requestId, service, Instant.now(), recordsErased, details);
    }
}
