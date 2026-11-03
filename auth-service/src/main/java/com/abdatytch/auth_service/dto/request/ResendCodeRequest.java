package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Renvoi d'un code d'inscription, par le canal de l'inscription.
 *
 * <h2>Pourquoi ce DTO remplace {@code ForgotPasswordRequest}</h2>
 *
 * {@code /auth/resend-code} prenait un {@code ForgotPasswordRequest}, qui ne
 * porte qu'un {@code phoneNumber} et l'exige. L'endpoint etait donc
 * <b>structurellement</b> limite au telephone, malgre son nom generique, et
 * appelait {@code initiatePhoneRegister} quelle que soit la demande.
 *
 * <p>Consequence pour qui s'est inscrit par adresse : aucun moyen de se faire
 * renvoyer son code. Le parcours d'inscription par courriel n'avait pas de
 * seconde chance, et un code perdu obligeait a recommencer — sur une adresse
 * desormais connue, donc en butant sur {@code EMAIL_ALREADY_EXISTS}.
 *
 * <p>Ce DTO accepte l'un <b>ou</b> l'autre, comme le fait deja
 * {@code LoginRequest} depuis qu'il a gagne son champ {@code identifier}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResendCodeRequest {

    /**
     * Numero au format E.164, quand c'est le canal choisi.
     *
     * <p>Le motif est <b>repris de {@code ForgotPasswordRequest}</b>, que cet
     * endpoint utilisait. Sans lui, le changement de DTO aurait relache la
     * validation en silence : un numero quelconque serait parti jusqu'au
     * service, qui en deduit un indicatif par {@code PhoneNumbers}. Ce depot a
     * deja corrige un 500 venu de la — l'indicatif etait tire par
     * {@code substring(0, 4)} sur une entree que le client controle.
     *
     * <p>Pas de {@code @NotBlank} : l'absence est permise puisque l'adresse est
     * l'autre canal. C'est {@link #isUnSeulCanalFourni()} qui exige qu'un des
     * deux soit la.
     */
    @Pattern(regexp = AppConstants.PHONE_PATTERN,
            message = AppConstants.PHONE_INVALID_MESSAGE)
    private String phoneNumber;

    @Email(message = AppConstants.EMAIL_INVALID_MESSAGE)
    private String email;

    /**
     * Un canal, et un seul.
     *
     * <p>Les deux renseignes seraient ambigus : on ne sait pas lequel la
     * personne attend, et choisir a sa place enverrait le code la ou elle ne
     * regarde pas. Aucun des deux ne designe personne.
     */
    @AssertTrue(message = AppConstants.RESEND_CANAL_UNIQUE_REQUIS)
    public boolean isUnSeulCanalFourni() {
        boolean parTelephone = phoneNumber != null && !phoneNumber.isBlank();
        boolean parAdresse = email != null && !email.isBlank();
        return parTelephone ^ parAdresse;
    }
}
