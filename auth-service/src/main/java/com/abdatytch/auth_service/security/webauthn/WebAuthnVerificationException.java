package com.abdatytch.auth_service.security.webauthn;

/**
 * Refus d'une attestation ou d'une assertion WebAuthn.
 *
 * <p>Le message est volontairement uniforme et sans detail : indiquer
 * <em>quelle</em> regle a echoue renseignerait l'appelant sur ce qu'il doit
 * ajuster a l'essai suivant. La cause reelle est journalisee cote serveur.
 */
public class WebAuthnVerificationException extends RuntimeException {

    public WebAuthnVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
