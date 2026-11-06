package com.abdatytch.notification_service.exception;

import com.abdatytch.notification_service.constants.ErrorCode;

/**
 * Un echec traduit de ce service.
 *
 * <h2>Pourquoi pas {@code AuthException}</h2>
 *
 * Le sous-systeme SMS levait {@code AuthException} quand il vivait dans
 * auth-service. Le nom y etait deja approximatif ; ici il serait faux : aucune
 * de ces erreurs ne concerne une authentification. Un fournisseur SMS
 * injoignable n'est pas un probleme d'identite, et laisser ce nom aurait envoye
 * chaque lecteur de pile chercher un defaut d'authentification inexistant.
 *
 * <h2>Le code d'erreur n'est jamais nul</h2>
 *
 * C'est la raison de l'unique constructeur. La version d'origine en offrait
 * quatre, dont un {@code (String message)} sans code — et c'est celui-la que le
 * seul appelant restant utilisait, un test. Une exception sans code oblige tout
 * lecteur a se demander si {@code getErrorCode()} peut rendre {@code null} :
 * {@code estUneIndisponibilite} portait precisement cette garde.
 *
 * <p>Avec un code obligatoire, l'invariant est pose par le type et plus personne
 * n'a a s'en souvenir. Le test qui voulait « fournisseur injoignable » demande
 * desormais {@link ErrorCode#SMS_PROVIDER_UNAVAILABLE}, ce qui dit la meme chose
 * dans le vocabulaire que le code de production utilise reellement.
 *
 * <h2>Pourquoi la cause n'est pas conservee</h2>
 *
 * Les fournisseurs enveloppent volontairement <b>sans cause</b> : la cause d'un
 * echec de transport porte l'URL appelee, parfois la cle d'API en parametre de
 * requete. Elle est journalisee au point d'appel, ou le journal est maitrise, et
 * ne remonte pas dans une pile que {@code GlobalExceptionHandler} pourrait
 * rendre.
 */
public class NotificationException extends RuntimeException {

    private final ErrorCode errorCode;

    public NotificationException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** Jamais {@code null} : garanti par l'unique constructeur. */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
