package com.abdatytch.security_service.exception;

/**
 * Refus nomme d'une operation sur la politique de securite.
 *
 * <h2>Pourquoi ces refus sont detailles</h2>
 *
 * Contrairement aux refus de connexion, qui doivent rester uniformes pour ne
 * pas renseigner un attaquant, ces refus s'adressent a un administrateur
 * authentifie agissant sur la configuration. Lui repondre « valeur invalide »
 * sans dire quelle borne il a franchie l'envoie deviner, et il finira par
 * essayer des valeurs au hasard sur une surface de securite.
 */
public class SecurityPolicyException extends RuntimeException {

    private final String cle;

    public SecurityPolicyException(String message) {
        this(message, null);
    }

    public SecurityPolicyException(String message, String cle) {
        super(message);
        this.cle = cle;
    }

    /** Cle concernee, ou {@code null} si le refus ne porte pas sur un parametre. */
    public String getCle() {
        return cle;
    }
}
