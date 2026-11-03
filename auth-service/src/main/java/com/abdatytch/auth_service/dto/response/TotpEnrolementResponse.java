package com.abdatytch.auth_service.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Remise du second facteur a un administrateur, a l'enrolement ou apres une
 * reinitialisation.
 *
 * <h2>Pourquoi un type et non une Map</h2>
 *
 * L'enrolement rendait un {@code Map.of("qrCodeUrl", ..., "secret", ...,
 * "message", ...)} construit dans la couche service. Le contrat de l'API etait
 * donc decrit nulle part : ni dans la signature, ni dans la documentation
 * OpenAPI, qui annonçait un objet libre. Un consommateur ne pouvait apprendre
 * les noms de champs qu'en appelant l'endpoint.
 *
 * <h2>Le secret ne sort qu'une fois</h2>
 *
 * Ce type est le <b>seul</b> endroit d'ou {@code totpSecret} sort du service.
 * Une fois {@code totpEnabled} pose a vrai, aucun appel ne le ressert : il faut
 * passer par une reinitialisation, qui en genere un nouveau. C'est la correction
 * de la faille d'interface-service, ou le secret etait recuperable a volonte par
 * qui connaissait le mot de passe.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Second facteur remis une seule fois : QR Code et secret")
public class TotpEnrolementResponse {

    @Schema(description = "URL otpauth:// a ouvrir dans l'application "
            + "d'authentification")
    private String qrCodeUrl;

    @Schema(description = "Secret en Base32, a saisir manuellement si le QR Code "
            + "ne peut etre scanne. Ne sera plus jamais remis.")
    private String secret;

    @Schema(description = "Consigne a afficher a l'administrateur")
    private String message;
}
