package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Connexion par mot de passe, pour tous les comptes.
 *
 * <h2>Un seul login, administrateurs compris</h2>
 *
 * Il y avait deux chemins : {@code /auth/login} pour les utilisateurs finaux du
 * SDK, et {@code /auth/admin/login} pour les administrateurs de la plateforme.
 * Deux chemins pour une meme operation, avec deux jeux de controles ecrits
 * separement — et c'est de la que venait un verrouillage de compte qui ne
 * s'appliquait que d'un cote.
 *
 * <p>{@code /auth/login} accepte desormais les comptes {@code ADMIN} et
 * {@code SUPER_ADMIN}, avec le second facteur quand il est enrole.
 *
 * <h2>Deux champs pour designer le compte</h2>
 *
 * <ul>
 *   <li>{@code phoneNumber} — contrat d'origine du SDK, conserve tel quel pour
 *       ne pas casser les integrations existantes ;</li>
 *   <li>{@code identifier} — adresse electronique <b>ou</b> telephone. Un
 *       administrateur retient son adresse, et le compte seme au deploiement
 *       porte les deux.</li>
 * </ul>
 *
 * L'un des deux suffit, et {@code identifier} a la priorite s'il est fourni.
 * Les deux colonnes sont {@code UNIQUE} dans {@code users}, donc la resolution
 * ne peut pas desigher deux comptes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Connexion par mot de passe. Le code TOTP n'est attendu "
        + "que pour un compte d'administration dont le second facteur est enrole.")
public class LoginRequest {

    /**
     * Telephone, contrat d'origine du SDK.
     *
     * <p>Le {@code @NotBlank} a ete retire : il rendait {@code identifier}
     * inutilisable, puisqu'une requete qui ne portait que l'adresse etait
     * rejetee en 400 avant d'atteindre le service. L'exigence « au moins un des
     * deux » est portee par {@link #isIdentifiantFourni()}.
     */
    @Pattern(regexp = AppConstants.PHONE_PATTERN, message = AppConstants.PHONE_INVALID_MESSAGE)
    @Schema(description = "Numero de telephone au format international",
            example = "+22370000000")
    private String phoneNumber;

    @Schema(description = "Adresse electronique ou numero de telephone. "
            + "Prioritaire sur phoneNumber.", example = "admin@example.com")
    private String identifier;

    @NotBlank(message = AppConstants.PASSWORD_REQUIRED_MESSAGE)
    @Schema(description = "Mot de passe")
    private String password;

    /**
     * Code a six chiffres du second facteur.
     *
     * <p>Sans annotation d'obligation, et ce n'est pas un oubli : la validation
     * de format ne sait pas si le compte vise porte un second facteur enrole.
     * Seule la couche service le sait, et c'est elle qui refuse avec
     * {@code AUTH_1009} quand le code manque alors qu'il est exige. L'annoter
     * {@code @NotBlank} rendrait la connexion impossible pour les utilisateurs
     * finaux, qui n'en ont pas.
     */
    @Schema(description = "Code TOTP a six chiffres, pour un compte "
            + "d'administration avec second facteur", example = "123456")
    private String totpCode;

    /**
     * Jeton d'appareil de confiance, s'il en existe un.
     *
     * <h2>Pourquoi il est dans le corps et non dans un en-tete</h2>
     *
     * Le mot de passe et le code TOTP y sont deja : la connexion est une requete
     * qui transporte des justificatifs, et en ajouter un ne change pas sa
     * nature. Le choix de l'en-tete, retenu pour {@code X-Refresh-Token}, se
     * justifiait sur une requete GET — ou le corps n'existe pas et ou la chaine
     * de requete finirait dans l'historique et les journaux d'acces.
     *
     * <h2>Ce qu'il dispense, et ce qu'il ne dispense pas</h2>
     *
     * Il dispense du <b>second facteur</b>, et de lui seul. Le mot de passe
     * reste exige : un jeton d'appareil vole ne doit pas suffire a entrer.
     *
     * <p>Un jeton absent, perime, revoque ou appartenant a un autre compte fait
     * simplement demander le code — ce n'est pas une erreur, c'est le
     * comportement par defaut.
     */
    @Schema(description = "Jeton d'appareil de confiance. Dispense du code TOTP, "
            + "jamais du mot de passe. Obtenu par POST /auth/trusted-devices.")
    private String deviceToken;

    /**
     * Au moins un des deux designateurs de compte.
     *
     * <p>Porte par la validation et non par le service : une requete qui ne
     * designe aucun compte est malformee, et doit rendre 400 — non 401, qui
     * laisserait croire a un probleme d'identifiants.
     */
    @AssertTrue(message = AppConstants.IDENTIFIANT_REQUIS_MESSAGE)
    @Schema(hidden = true)
    public boolean isIdentifiantFourni() {
        return (phoneNumber != null && !phoneNumber.isBlank())
                || (identifier != null && !identifier.isBlank());
    }
}
