package com.abdatytch.order_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Un refus de ce service, avec son statut.
 *
 * <h2>Le motif n'est PAS rendu a l'appelant</h2>
 *
 * {@code GlobalExceptionHandler} rend le libelle du statut, jamais
 * {@code getReason()} : un message interne nomme des classes, des tables,
 * parfois un identifiant. Le motif reel va au journal.
 *
 * <p>L'exception est {@link RefusExplique}, pour les motifs REDIGES a
 * l'intention de celui qui a fait la demande.
 */
public class OrderException extends ResponseStatusException {

    public OrderException(HttpStatus statut, String motif) {
        super(statut, motif);
    }

    /**
     * Introuvable — ou refuse, et c'est le meme code.
     *
     * <h3>404 et jamais 403, et ce n'est pas de la pudeur</h3>
     *
     * Un produit reserve aux professionnels ne doit pas etre distinguable d'un
     * produit inexistant. Un 403 confirmerait que la reference existe, et une
     * reference confirmee se revend — c'est la regle que ce depot applique
     * deja sur quotas-service et setting-service.
     */
    public static OrderException introuvable() {
        return new OrderException(HttpStatus.NOT_FOUND, "Ressource introuvable ou hors portee");
    }

    /** La dependance interrogee n'a pas repondu. Compte par le disjoncteur. */
    public static OrderException dependanceIndisponible(String laquelle) {
        return new OrderException(HttpStatus.SERVICE_UNAVAILABLE,
                "Dependance indisponible : " + laquelle);
    }
}
