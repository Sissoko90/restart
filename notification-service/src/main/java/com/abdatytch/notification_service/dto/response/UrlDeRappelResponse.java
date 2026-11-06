package com.abdatytch.notification_service.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Confirmation de la declaration de l'URL de rappel au fournisseur SMS.
 *
 * <p>Remplace un {@code Map.of("declaree", true, "typeNotification", ...)}
 * assemble dans le controleur : la cle et le type de chaque champ n'etaient
 * decrits nulle part, ni dans la signature ni dans OpenAPI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Declaration de l'URL de rappel des accuses de reception")
public class UrlDeRappelResponse {

    @Schema(description = "Vrai si le fournisseur a accepte la declaration")
    private boolean declaree;

    @Schema(description = "Type de notification confirme par le fournisseur "
            + "(appel en GET ou en POST)")
    private String typeNotification;

    public static UrlDeRappelResponse de(String typeConfirme) {
        return UrlDeRappelResponse.builder()
                .declaree(true)
                .typeNotification(typeConfirme)
                .build();
    }
}
