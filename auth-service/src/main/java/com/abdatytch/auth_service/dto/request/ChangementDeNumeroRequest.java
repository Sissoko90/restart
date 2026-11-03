package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Demande de remplacement du numéro de téléphone.
 *
 * <h2>Pourquoi le mot de passe actuel y figure</h2>
 *
 * Le numéro n'est pas seulement un identifiant de connexion : c'est le canal de
 * `/auth/forgot-password`. Qui le remplace prend le contrôle de la
 * réinitialisation du mot de passe, donc du compte entier — sans jamais
 * connaître le mot de passe. Une session seule ne peut donc pas suffire : un
 * jeton d'accès volé ouvrirait une prise de contrôle définitive.
 *
 * <h2>Aucun identifiant de compte</h2>
 *
 * Il vient du jeton vérifié, jamais du corps. C'est ce qui rend l'IDOR
 * impossible par construction plutôt que par vigilance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Remplacement du numero : le code part au NOUVEAU numero")
public class ChangementDeNumeroRequest {

    /**
     * Le nouveau numéro, au format E.164.
     *
     * <p>Le même motif que partout ailleurs dans ce service : un numéro mal
     * formé échouerait chez le fournisseur SMS, et la personne lirait une panne
     * là où il s'agit d'une saisie à corriger.
     */
    @NotBlank(message = AppConstants.PHONE_REQUIRED_MESSAGE)
    @Pattern(regexp = AppConstants.PHONE_PATTERN,
            message = AppConstants.PHONE_INVALID_E164_MESSAGE)
    @Size(max = AppConstants.PHONE_MAX_LENGTH,
            message = AppConstants.PHONE_TOO_LONG_MESSAGE)
    @Schema(description = "Nouveau numero, format E.164", example = "+22374427759",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String nouveauNumero;

    /**
     * Indicatif du pays, envoyé explicitement.
     *
     * <p>Et non déduit du numéro : `PhoneNumbers.prefixeIndicatif` coupe un
     * nombre fixe de caractères, défaut connu et documenté de ce dépôt. Le
     * déduire ici le reproduirait sur chaque compte qui change de numéro.
     */
    @NotBlank(message = AppConstants.COUNTRY_CODE_REQUIRED_MESSAGE)
    @Size(min = 2, max = 5, message = AppConstants.COUNTRY_CODE_INVALID_MESSAGE)
    @Schema(description = "Indicatif du pays", example = "+223",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String indicatif;

    /**
     * Mot de passe actuel.
     *
     * <p>Exigé : voir la javadoc de la classe. Un échec est compté comme un
     * échec de connexion, sinon ce point d'entrée devient un oracle à éprouver
     * les mots de passe depuis une session volée.
     */
    @NotBlank(message = AppConstants.PASSWORD_REQUIRED_MESSAGE)
    @Schema(description = "Mot de passe actuel du compte",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String motDePasseActuel;
}
