package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Changement de mot de passe par une personne deja connectee.
 *
 * <p>L'identifiant du compte n'est <b>pas</b> dans ce corps : il vient du jeton,
 * via le contexte de securite. L'y mettre permettrait de changer le mot de passe
 * d'un autre compte en modifiant un champ — l'IDOR le plus direct qui soit, et
 * ce depot en a deja corrige sur la passerelle.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = AppConstants.ANCIEN_MOT_DE_PASSE_REQUIS)
    private String currentPassword;

    @NotBlank(message = AppConstants.NOUVEAU_MOT_DE_PASSE_REQUIS)
    @Size(min = 8, message = AppConstants.MOT_DE_PASSE_TROP_COURT)
    private String newPassword;

    @NotBlank(message = AppConstants.CONFIRMATION_MOT_DE_PASSE_REQUISE)
    private String confirmPassword;

    /**
     * Le nouveau mot de passe doit differer de l'ancien.
     *
     * <p>Sans ce controle, l'appel reussit sans rien changer tout en fermant
     * toutes les sessions : la personne est deconnectee partout et croit avoir
     * change son mot de passe. Le refus est donc plus utile que le succes.
     */
    @AssertTrue(message = AppConstants.NOUVEAU_MOT_DE_PASSE_IDENTIQUE)
    public boolean isNouveauMotDePasseDifferent() {
        if (currentPassword == null || newPassword == null) {
            // Les @NotBlank s'en chargent : ne pas doubler leur message.
            return true;
        }
        return !currentPassword.equals(newPassword);
    }

    /**
     * La confirmation doit correspondre.
     *
     * <p>Verifiee ici et non dans le service : {@code resetPassword} portait ce
     * controle dans son corps et rendait {@code VALIDATION_ERROR} sans designer
     * le champ. Dans le DTO, la reponse nomme le champ fautif comme pour toute
     * autre contrainte.
     */
    @AssertTrue(message = AppConstants.CONFIRMATION_MOT_DE_PASSE_DIFFERENTE)
    public boolean isConfirmationConforme() {
        if (newPassword == null || confirmPassword == null) {
            return true;
        }
        return newPassword.equals(confirmPassword);
    }
}
