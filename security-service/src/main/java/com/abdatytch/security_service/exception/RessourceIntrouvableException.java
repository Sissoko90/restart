package com.abdatytch.security_service.exception;

/**
 * Refus portant sur une ressource que l'appelant designe par un identifiant
 * qu'il ne peut pas deviner.
 *
 * <h2>Pourquoi une classe distincte plutot qu'un message</h2>
 *
 * Elle existe pour que {@link GlobalExceptionHandler} rende un <b>404</b> la ou
 * {@link SecurityPolicyException} rend un 400, sans comparer des chaines de
 * messages. Une comparaison de message aurait lie le code de statut au libelle :
 * reformuler le message changerait le statut HTTP, et rien ne l'aurait signale.
 *
 * <h2>Pourquoi 404 et non 403</h2>
 *
 * Les identifiants concernes sont des UUID de fenetres de maintenance et de
 * regles d'adresses. Repondre 403 sur un identifiant existant et 404 sur un
 * identifiant inconnu permettrait d'enumerer ce qui existe en lisant le seul
 * code de statut.
 *
 * <p>La regle vaut pour les identifiants qu'on peut parcourir. Elle ne vaut pas
 * pour un code pays, qui est public et dont les 250 valeurs sont connues de
 * tous : c'est pourquoi un pays non refuse rend 400 et non 404.
 */
public class RessourceIntrouvableException extends SecurityPolicyException {

    public RessourceIntrouvableException(String message) {
        super(message);
    }
}
