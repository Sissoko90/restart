package com.abdatytch.auth_service.service.sms;

/**
 * Met un numéro de téléphone dans la forme exigée par AfrikSMS.
 *
 * <p>La documentation est explicite : « Numéro destinataire avec votre code pays
 * mais sans préfixe + ou 00 ». Or la plateforme stocke les numéros en E.164,
 * donc avec le {@code +}. <b>Envoyer le numéro tel qu'il est stocké fait échouer
 * tous les SMS</b> — et l'échec n'est pas une erreur HTTP, mais un
 * {@code code} de refus dans un corps 200, c'est-à-dire la forme la plus facile
 * à prendre pour un succès.
 *
 * <h2>Ce que cette classe ne peut pas faire</h2>
 *
 * Un numéro national sans indicatif pays — {@code 90909090} — est
 * indiscernable d'un numéro international court. Aucune normalisation ne peut
 * deviner le pays, et en inventer un enverrait le code de vérification à un
 * inconnu dans un autre pays. Ces numéros sont donc transmis tels quels, et
 * c'est AfrikSMS qui les refuse, visiblement, par son {@code code}.
 */
final class NumeroPourAfrikSms {

    /** Longueur maximale d'un numéro E.164, indicatif pays compris. */
    private static final int LONGUEUR_MAX = 15;

    /**
     * Longueur minimale retenue comme plausible.
     *
     * <p>Huit chiffres, ce qui est la taille d'un numéro national dans la
     * plupart des pays desservis par AfrikSMS. En dessous, le numéro est
     * tronqué ou saisi de travers, et il vaut mieux refuser que d'appeler l'API
     * avec le code de vérification dans l'URL pour rien.
     */
    private static final int LONGUEUR_MIN = 8;

    private NumeroPourAfrikSms() {
    }

    /**
     * @return le numéro en chiffres seuls, sans {@code +} ni {@code 00} de tête,
     *         ou la <b>chaîne vide</b> si rien d'exploitable n'en sort
     */
    static String normaliser(String numero) {
        if (numero == null) {
            return "";
        }

        // Les separateurs de mise en forme : espaces de toute sorte, tirets,
        // points, parentheses, barres obliques.
        String nettoye = numero.replaceAll("[\\s\\u00A0.()/-]", "");

        if (nettoye.startsWith("+")) {
            nettoye = nettoye.substring(1);
        }
        // Apres le +, car « +00228... » existe dans les saisies manuelles.
        // Aucun indicatif pays ne commence par zero : retirer un « 00 » de tete
        // ne peut pas amputer un indicatif.
        if (nettoye.startsWith("00")) {
            nettoye = nettoye.substring(2);
        }

        // Tout caractere restant qui n'est pas un chiffre rend le numero
        // inexploitable. Les RETIRER serait pire que refuser : « 22890x90909 »
        // deviendrait un numero d'apparence valide, et le code partirait chez
        // quelqu'un d'autre.
        if (!nettoye.matches("\\d+")) {
            return "";
        }
        if (nettoye.length() < LONGUEUR_MIN || nettoye.length() > LONGUEUR_MAX) {
            return "";
        }
        return nettoye;
    }
}
