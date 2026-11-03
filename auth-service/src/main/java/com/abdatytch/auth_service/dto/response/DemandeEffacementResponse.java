package com.abdatytch.auth_service.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Accuse d'une demande d'effacement.
 *
 * <p>Remplace un {@code LinkedHashMap} assemble dans le controleur. Sur une
 * operation dont la preuve est exigee par l'article 5.2, le nom du champ qui
 * porte l'identifiant de la demande ne doit pas dependre d'une chaine ecrite a
 * la main dans une methode privee.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Demande d'effacement enregistree")
public class DemandeEffacementResponse {

    /**
     * Identifiant au registre de preuve.
     *
     * <p>C'est ce que la personne doit conserver : une fois l'effacement fait,
     * elle n'a plus de compte pour consulter quoi que ce soit, et cet
     * identifiant est le seul moyen de justifier de sa demande.
     */
    @Schema(description = "Identifiant de la demande au registre de preuve")
    private String requestId;

    @Schema(description = "Consigne destinee a la personne")
    private String message;

    @Schema(description = "Toujours vrai : l'effacement ne se defait pas")
    private boolean irreversible;

    public static DemandeEffacementResponse de(String requestId, String message) {
        return DemandeEffacementResponse.builder()
                .requestId(requestId)
                .message(message)
                .irreversible(true)
                .build();
    }
}
