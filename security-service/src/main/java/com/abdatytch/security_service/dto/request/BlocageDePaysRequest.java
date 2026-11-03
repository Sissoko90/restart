package com.abdatytch.security_service.dto.request;

import com.abdatytch.security_service.constant.SecurityConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Refus d'un ou plusieurs pays.
 *
 * <h2>Pourquoi le motif est obligatoire</h2>
 *
 * L'ensemble Redis que cette surface remplace ne portait qu'un code pays. Un
 * pays refuse sans motif se retrouve refuse indefiniment, parce que personne
 * n'ose lever une decision dont il ignore la raison — et parce que rien ne
 * permet de savoir si elle est encore justifiee.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Pays a refuser, avec le motif de la decision")
public class BlocageDePaysRequest {

    /**
     * Codes ISO 3166-1 alpha-2.
     *
     * <p>La casse est libre : le service normalise en majuscules <b>avant</b> de
     * valider. L'implementation remplacee validait d'abord et normalisait
     * ensuite, ce qui refusait {@code ml} comme code invalide.
     */
    @NotEmpty(message = SecurityConstants.CODES_PAYS_REQUIS)
    @Size(max = 250, message = SecurityConstants.TROP_DE_CODES_PAYS)
    @Schema(description = "Codes ISO 3166-1 alpha-2, casse libre",
            example = "[\"KP\", \"ml\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> codesIso;

    @NotBlank(message = SecurityConstants.MOTIF_REQUIS)
    @Size(max = 500)
    @Schema(description = "Pourquoi ces pays sont refuses",
            example = "Aucun client dans cette zone, trafic exclusivement automatise",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String motif;
}
