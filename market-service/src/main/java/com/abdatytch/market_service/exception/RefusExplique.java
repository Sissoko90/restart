package com.abdatytch.market_service.exception;

import org.springframework.http.HttpStatus;

/**
 * Un refus dont le motif a ete REDIGE pour celui qui a fait la demande.
 *
 * <h2>Pourquoi un TYPE et non un drapeau</h2>
 *
 * {@code GlobalExceptionHandler} ne peut pas deviner si un motif a ete ecrit
 * pour l'appelant ou recopie d'une cause interne — Spring lui-meme en pose dans
 * ses propres refus. Le type tranche.
 *
 * <h2>Le constructeur REFUSE 401, 403 et 5xx</h2>
 *
 * Les causes d'un refus d'authentification ne doivent pas etre distinguables,
 * et un 5xx porte une cause interne par definition. Ce qui passe par ici decrit
 * donc la DEMANDE : un RCCM mal forme, une note hors bornes, une taille de page
 * trop grande.
 */
public class RefusExplique extends MarketException {

    public RefusExplique(HttpStatus statut, String motifPourLAppelant) {
        super(verifier(statut), motifPourLAppelant);
    }

    private static HttpStatus verifier(HttpStatus statut) {
        if (statut == HttpStatus.UNAUTHORIZED || statut == HttpStatus.FORBIDDEN
                || statut.is5xxServerError()) {
            throw new IllegalArgumentException(
                    "RefusExplique rend son motif a l'appelant : " + statut
                            + " n'a rien a lui expliquer. Utiliser MarketException.");
        }
        return statut;
    }

    /** Le cas courant : la demande est mal formee, et on dit pourquoi. */
    public static RefusExplique demandeInvalide(String motif) {
        return new RefusExplique(HttpStatus.BAD_REQUEST, motif);
    }

    /** L'operation est impossible dans l'etat courant. */
    public static RefusExplique etatIncompatible(String motif) {
        return new RefusExplique(HttpStatus.CONFLICT, motif);
    }
}
