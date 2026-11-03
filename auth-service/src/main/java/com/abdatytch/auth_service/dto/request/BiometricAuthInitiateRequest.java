package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Début d'une authentification biométrique, pour un utilisateur <b>non encore
 * connecté</b>.
 *
 * <h2>Pourquoi cette requête existe</h2>
 *
 * {@code POST /auth/biometric/authenticate/initiate} lisait l'identité par
 * {@code extractUserIdFromToken} : il exigeait donc un jeton. Or la biométrie
 * est une <b>méthode de connexion</b> — pour s'y authentifier, il fallait déjà
 * être authentifié. Le même cercle que {@code /auth/login}, à un second
 * endroit, et il rendait ce parcours inutilisable.
 *
 * <p>L'identifiant remplace donc le jeton. {@code register/**} garde le jeton :
 * on y ajoute un authentificateur à un compte existant, ce qui suppose
 * légitimement une session.
 */
@Data
public class BiometricAuthInitiateRequest {

    /**
     * Numéro de téléphone ou adresse de courriel.
     *
     * <p>Les deux formes sont acceptées, comme sur {@code /auth/admin/login} :
     * le client ne sait pas nécessairement par lequel le compte a été créé.
     */
    @NotBlank(message = AppConstants.IDENTIFIER_REQUIRED_MESSAGE)
    private String identifier;
}
