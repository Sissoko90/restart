package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.entities.ErasureAcknowledgement;
import com.abdatytch.auth_service.entities.ErasureRequest;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent.ErasureReason;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Preuve d'execution d'un effacement, service par service.
 *
 * <h2>Pourquoi un type et non une Map imbriquee</h2>
 *
 * Le controleur assemblait un {@code LinkedHashMap} contenant une liste de
 * {@code Map.of(...)} a quatre cles. C'est la reponse qui demontre la conformite
 * a l'article 5.2 : sa forme ne doit pas dependre de chaines ecrites a la main
 * dans un controleur, et elle doit apparaitre dans OpenAPI pour que l'appelant
 * sache quoi lire.
 *
 * <h2>Ce que l'absence d'un service signifie</h2>
 *
 * {@code services} ne liste que les detenteurs de donnees qui ont
 * <b>confirme</b>. Un service absent n'a pas encore efface — ou n'a pas reçu
 * l'evenement. C'est precisement ce qu'il faut pouvoir constater, et c'est la
 * raison pour laquelle {@code termineLe} peut rester nul alors que la demande
 * existe.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Etat d'avancement d'un effacement")
public class EtatEffacementResponse {

    @Schema(description = "Identifiant de la demande au registre")
    private String requestId;

    @Schema(description = "Horodatage de la demande")
    private LocalDateTime demandeLe;

    @Schema(description = "Motif conserve au registre")
    private ErasureReason motif;

    @Schema(description = "Nul tant que l'effacement n'est pas acheve")
    private LocalDateTime termineLe;

    @Schema(description = "Detenteurs de donnees ayant confirme l'effacement")
    private List<ServiceEfface> services;

    /**
     * Confirmation d'un service.
     *
     * @param enregistrements nombre de lignes effacees ; <b>zero est une
     *        reponse valide</b> et non une anomalie : un service peut n'avoir
     *        jamais detenu de donnee sur cette personne
     */
    @Schema(description = "Confirmation d'effacement par un service")
    public record ServiceEfface(
            String service,
            LocalDateTime effaceLe,
            Integer enregistrements,
            String detail) {

        public static ServiceEfface de(ErasureAcknowledgement ack) {
            return new ServiceEfface(
                    ack.getServiceName(),
                    ack.getCompletedAt(),
                    ack.getRecordsErased(),
                    ack.getDetails() == null ? "" : ack.getDetails());
        }
    }

    public static EtatEffacementResponse de(
            ErasureRequest requete, List<ErasureAcknowledgement> confirmations) {
        return EtatEffacementResponse.builder()
                .requestId(requete.getId())
                .demandeLe(requete.getRequestedAt())
                .motif(requete.getReason())
                .termineLe(requete.getCompletedAt())
                .services(confirmations.stream().map(ServiceEfface::de).toList())
                .build();
    }
}
