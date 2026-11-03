package com.abdatytch.auth_service.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Confirmation d'une operation d'administration sur un compte.
 *
 * <p>Remplace trois {@code HashMap} assembles a la main dans
 * {@code AdminAccountController}, dont les cles differaient d'une operation a
 * l'autre — {@code message} et {@code identifier} partout, plus
 * {@code duration} au format {@code "30 minutes"} pour le verrouillage. Une
 * duree rendue comme texte libre oblige l'appelant a l'analyser pour
 * l'afficher autrement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Confirmation d'une operation d'administration sur un compte")
public class OperationCompteResponse {

    @Schema(description = "Identifiant du compte concerne, tel que fourni")
    private String identifier;

    @Schema(description = "Message destine a l'administrateur")
    private String message;

    /**
     * Duree du verrou, en minutes, nulle pour les operations qui n'en posent
     * pas.
     *
     * <p>Un nombre et non la chaine {@code "30 minutes"} : c'est a l'interface
     * de choisir la formulation.
     */
    @Schema(description = "Duree du verrou en minutes, si l'operation en pose un")
    private Integer dureeMinutes;

    public static OperationCompteResponse de(String identifier, String message) {
        return OperationCompteResponse.builder()
                .identifier(identifier)
                .message(message)
                .build();
    }

    public static OperationCompteResponse verrou(
            String identifier, String message, int dureeMinutes) {
        return OperationCompteResponse.builder()
                .identifier(identifier)
                .message(message)
                .dureeMinutes(dureeMinutes)
                .build();
    }
}
