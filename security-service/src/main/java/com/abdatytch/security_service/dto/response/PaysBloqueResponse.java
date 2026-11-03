package com.abdatytch.security_service.dto.response;

import com.abdatytch.security_service.entities.PaysBloque;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Un pays refuse, avec le motif et l'auteur de la decision. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Pays refuse, et la trace de la decision")
public class PaysBloqueResponse {

    @Schema(description = "Code ISO 3166-1 alpha-2, en majuscules", example = "KP")
    private String codeIso;

    @Schema(description = "Pourquoi ce pays est refuse")
    private String motif;

    @Schema(description = "Qui a decide")
    private String bloquePar;

    private Instant creeLe;

    @Schema(description = "Vrai si le refus s'applique encore")
    private Boolean actif;

    @Schema(description = "Levee du refus. Absent si le refus court toujours")
    private Instant leveeLe;

    @Schema(description = "Qui a leve le refus")
    private String leveePar;

    public static PaysBloqueResponse de(PaysBloque pays) {
        return PaysBloqueResponse.builder()
                .codeIso(pays.getCodeIso())
                .motif(pays.getMotif())
                .bloquePar(pays.getBloquePar())
                .creeLe(pays.getCreeLe())
                .actif(pays.estActif())
                .leveeLe(pays.getLeveeLe())
                .leveePar(pays.getLeveePar())
                .build();
    }
}
