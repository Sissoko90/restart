package com.abdatytch.auth_service.util;

/**
 * Extraction de l'indicatif pays depuis un numero au format E.164.
 *
 * <h2>Le defaut corrige</h2>
 *
 * Deux endroits ecrivaient {@code request.getPhoneNumber().substring(0, 4)} —
 * {@code AuthController.resendCode} et {@code AuthServiceImpl} — en supposant
 * qu'un numero valide fait au moins quatre caracteres.
 *
 * <p>La validation ne le garantit pas. Le motif E.164 applique par
 * {@code @Pattern} est {@code ^\+?[1-9]\d{1,14}$} : il accepte deux caracteres.
 * {@code "12"}, {@code "+22"} et {@code "223"} passent la validation, puis
 * {@code substring(0, 4)} leve une {@code StringIndexOutOfBoundsException}.
 *
 * <p>Consequence sur {@code POST /auth/resend-code} : une requete recevable
 * produisait une <b>erreur 500</b> au lieu d'une reponse metier. Un 500 sur une
 * entree que le client controle est au mieux un defaut de robustesse, au pire un
 * revelateur de pile d'appels.
 *
 * <h2>Ce que cette classe ne pretend pas faire</h2>
 *
 * Les quatre premiers caracteres ne sont pas l'indicatif pays. Un indicatif
 * compte de un a trois chiffres : {@code +1} pour l'Amerique du Nord,
 * {@code +33} pour la France, {@code +223} pour le Mali. Decouper a une
 * longueur fixe donne {@code "+123"} pour un numero americain, ce qui est faux.
 *
 * <p>Le comportement d'origine est neanmoins conserve pour les numeros
 * suffisamment longs : corriger la segmentation changerait les valeurs
 * enregistrees pour les comptes existants, ce qui est une migration de donnees,
 * pas une correction de robustesse. Seul le plantage est traite ici.
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /** Longueur historique du prefixe extrait. */
    private static final int LONGUEUR_PREFIXE = 4;

    /**
     * Rend le prefixe du numero, sans jamais depasser sa longueur reelle.
     *
     * @param numero numero au format E.164, eventuellement {@code null}
     * @return le prefixe, ou {@code null} si le numero est {@code null} ou vide
     */
    public static String prefixeIndicatif(String numero) {
        if (numero == null || numero.isBlank()) {
            return null;
        }
        String propre = numero.trim();
        return propre.length() <= LONGUEUR_PREFIXE
                ? propre
                : propre.substring(0, LONGUEUR_PREFIXE);
    }
}
