package com.abdatytch.commons.erasure;

import java.time.Instant;
import java.util.UUID;

/**
 * Demande d'effacement des données d'une personne (RGPD art. 17).
 *
 * <h2>Pourquoi un événement plutôt qu'un appel direct</h2>
 *
 * Les données personnelles d'une même personne sont réparties sur six services,
 * chacun avec sa base : identité dans auth-service, profil dans user-service,
 * pièces justificatives dans media-service, consommation dans quotas-service,
 * projets dans interface-service, paramètres dans setting-service.
 *
 * <p>Un effacement en cascade par appels synchrones échouerait dès qu'un service
 * est indisponible — et laisserait la personne à moitié effacée, sans trace de
 * ce qui reste à faire. L'événement permet à chaque service de traiter quand il
 * le peut, et de réessayer.
 *
 * <p><b>Chaque service accuse réception</b> en publiant un
 * {@link ErasureCompletedEvent}. Ce n'est pas une politesse : l'article 5.2
 * impose au responsable de traitement de pouvoir <b>démontrer</b> la conformité.
 * Sans registre d'exécution, on affirme avoir effacé sans pouvoir le prouver.
 *
 * <h2>Effacer, ce n'est pas supprimer la ligne</h2>
 *
 * Supprimer la ligne casse les références (journaux d'audit, écritures de
 * facturation) et fait échouer l'effacement là où il compte. La règle appliquée
 * dans la plateforme : <b>écraser les champs identifiants, conserver
 * l'identifiant technique</b>. Celui-ci devient alors un jeton opaque qui ne
 * permet plus de remonter à une personne, puisqu'aucune table ne conserve la
 * correspondance.
 *
 * @param requestId  identifiant de la demande, repris dans chaque accusé
 * @param subjectId  identifiant du porteur ({@code sub} des jetons, aussi appelé
 *                   {@code authUserId} dans les services aval)
 * @param requestedAt horodatage de la demande
 * @param reason     motif — demande de la personne, expiration de conservation,
 *                   décision administrative
 */
public record UserErasureRequestedEvent(
        UUID requestId,
        String subjectId,
        Instant requestedAt,
        ErasureReason reason
) {

    public static UserErasureRequestedEvent of(String subjectId, ErasureReason reason) {
        return new UserErasureRequestedEvent(UUID.randomUUID(), subjectId, Instant.now(), reason);
    }

    public enum ErasureReason {
        /** La personne a exercé son droit à l'effacement. */
        DEMANDE_DE_LA_PERSONNE,
        /** La durée de conservation est écoulée. */
        FIN_DE_CONSERVATION,
        /** Décision administrative (compte frauduleux, injonction). */
        DECISION_ADMINISTRATIVE
    }
}
