package com.abdatytch.auth_service.enums;

public enum VerificationCodeType {
    REGISTRATION("Enregistrement"),
    PASSWORD_RESET("Réinitialisation de mot de passe"),
    /**
     * Connexion par code à usage unique sur le numéro.
     *
     * <p>Le nom dit « vérification de téléphone », mais l'usage réel est la
     * <b>connexion</b> : {@code initiatePhoneAuth} et {@code verifyPhoneAuth}
     * s'en servent, et ni l'un ni l'autre ne pose {@code isPhoneVerified}. Le
     * nom est conservé parce qu'il est écrit dans les lignes déjà en base.
     */
    PHONE_VERIFICATION("Connexion par code sur le téléphone"),
    /** Connexion par code à usage unique sur l'adresse. Voir {@link #PHONE_VERIFICATION}. */
    EMAIL_VERIFICATION("Connexion par code sur l'adresse"),
    /**
     * Confirmation de la possession du numéro, après inscription.
     *
     * <h2>Pourquoi un type distinct de {@link #PHONE_VERIFICATION}</h2>
     *
     * Le type fait partie de la clé de recherche du code. Réutiliser celui de la
     * connexion rendrait les deux codes <b>interchangeables</b> : un code reçu
     * pour se connecter confirmerait le numéro, et un code reçu pour confirmer
     * le numéro ouvrirait une session. Deux usages de sensibilité différente
     * partageraient le même justificatif.
     */
    PHONE_CONFIRMATION("Confirmation du numéro de téléphone"),
    /**
     * Changement de numéro : le code part au NOUVEAU numéro.
     *
     * <p>Distinct de {@code PHONE_CONFIRMATION}, qui confirme le numéro
     * courant. Les confondre permettrait de confirmer un changement avec un
     * code demandé pour une simple vérification, et inversement.
     */
    PHONE_CHANGE("Changement de numéro de téléphone"),
    /** Confirmation de la possession de l'adresse. Voir {@link #PHONE_CONFIRMATION}. */
    EMAIL_CONFIRMATION("Confirmation de l'adresse électronique"),
    TWO_FACTOR_AUTH("Authentification à deux facteurs");
    
    private final String description;
    
    VerificationCodeType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}
