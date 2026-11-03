package com.abdatytch.auth_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Un lot de codes de secours, remis <b>une seule fois</b>.
 *
 * <h2>Pourquoi le message est aussi insistant</h2>
 *
 * Les codes ne sont stockés que hachés : ils ne pourront plus être relus, par
 * personne. Une personne qui ne les note pas à cet instant se retrouvera sans
 * sortie le jour où elle perdra son téléphone — c'est-à-dire exactement la
 * situation que ces codes existent pour éviter.
 *
 * <p>Même précaution que {@code TotpEnrolementResponse} et
 * {@code ConfianceAccordeeResponse}, et pour la même raison.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Codes de secours, remis une seule fois")
public class LotDeCodesResponse {

    /**
     * Les codes, en clair, pour la seule et unique fois.
     *
     * <p>Chacun est à usage unique.
     */
    @Schema(description = "Codes a usage unique. A noter MAINTENANT : ils ne "
            + "seront plus affiches.")
    private List<String> codes;

    @Schema(description = "Nombre de codes dans ce lot", example = "10")
    private Integer nombre;

    /**
     * Vrai si un lot précédent vient d'être invalidé.
     *
     * <p>Le dire explicitement évite une mauvaise surprise : la personne qui
     * régénère ses codes doit savoir que son ancien papier ne vaut plus rien.
     */
    @Schema(description = "Vrai si un lot precedent a ete invalide par celui-ci")
    private Boolean lotPrecedentInvalide;

    private String message;
}
