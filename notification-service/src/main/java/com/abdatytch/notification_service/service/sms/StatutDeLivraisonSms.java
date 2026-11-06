package com.abdatytch.notification_service.service.sms;

/**
 * Codes de livraison rendus par AfrikSMS dans ses accusés de réception.
 *
 * <h2>Les codes ne sont pas des entiers</h2>
 *
 * La documentation les écrit {@code 000}, {@code 001}, {@code 002} — des
 * <b>chaînes</b> à trois chiffres, dont les zéros de tête portent du sens. Les
 * lire comme des entiers confondrait {@code "000"} et {@code "0"}, et surtout
 * confondrait ces codes avec celui de la réponse d'envoi, qui vaut 100 en cas
 * de succès et qui est, lui, un entier. Deux espaces de codes différents dans
 * la même API, sous le même nom de champ : la confusion est une erreur à
 * commettre une fois.
 */
public enum StatutDeLivraisonSms {

    /** {@code 000} — remis au terminal du destinataire. */
    LIVRE("000"),

    /**
     * {@code 001} — remis à l'opérateur, sans accusé de sa part.
     *
     * <p>Ni un succès ni un échec : le SMS a quitté AfrikSMS, et on ne sait pas
     * s'il est arrivé. Le traiter comme un succès masquerait des numéros qui ne
     * reçoivent jamais rien ; le traiter comme un échec déclencherait de faux
     * incidents. Il mérite donc son propre état.
     */
    REMIS_SANS_ACCUSE("001"),

    /** {@code 002} — numéro injoignable, ou erreur de réception. */
    INJOIGNABLE("002"),

    /**
     * Code absent, ou valeur non documentée.
     *
     * <p>Un fournisseur tiers peut ajouter des codes sans nous prévenir : une
     * valeur inconnue ne doit pas faire échouer le traitement de l'accusé, ni
     * être rangée en silence dans l'un des trois états connus.
     */
    INCONNU("");

    private final String code;

    StatutDeLivraisonSms(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * @param code la valeur brute du champ {@code code} de l'accusé
     * @return l'état correspondant, ou {@link #INCONNU}
     */
    public static StatutDeLivraisonSms depuisLeCode(String code) {
        if (code == null) {
            return INCONNU;
        }
        String normalise = code.trim();
        for (StatutDeLivraisonSms statut : values()) {
            if (statut != INCONNU && statut.code.equals(normalise)) {
                return statut;
            }
        }
        return INCONNU;
    }

    /** L'accusé signale-t-il un échec sur lequel il faut agir ? */
    public boolean estUnEchec() {
        return this == INJOIGNABLE;
    }
}
