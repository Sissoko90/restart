package com.abdatytch.setting_service.enums;

/**
 * Regroupement fonctionnel d'un paramètre système.
 *
 * <p>Correspond au bloc {@code settings.system} de
 * {@code config-repo/dev/setting-service-dev.yml}. Il n'en reste qu'un : les
 * deux autres categories sont parties vers les services qui APPLIQUENT leurs
 * reglages — voir les deux notes en fin de fichier.
 */
public enum SettingCategory {

    /** Comportement général de la plateforme (maintenance, inscriptions...). */
    SYSTEM;

    // NOTIFICATIONS a été RETIRÉE, pour la même raison que SECURITY ci-dessous
    // et avec la même règle : une catégorie sans aucune clé laisserait croire
    // qu'il existe des paramètres de notification ici, et une interface les
    // afficherait comme une section vide.
    //
    // Ses trois clés — notifications.{email,sms,push}-enabled — étaient écrites
    // dans le catalogue, exposées, modifiables, et lues par PERSONNE. Un
    // administrateur qui désactivait les SMS ne changeait rien : ils
    // continuaient de partir, et de se payer. L'interrupteur vit désormais là
    // où il est appliqué, dans notification-service et son config-repo devenu
    // modifiable à chaud.
    //
    // Le consentement PAR PERSONNE reste dans ce service, dans le magasin par
    // utilisateur, sous les clés `notifications.canal.<canal>`. Il n'est pas
    // catégorisé : ce magasin accepte des clés libres.

    // SECURITY a été RETIRÉE : la politique de sécurité est portée par
    // security-service, typée, bornée et publiée dans Redis. La garder ici
    // aurait laissé deux magasins pour une même décision — le défaut qui a fait
    // qu'un verrouillage administrateur ne verrouillait personne dans ce dépôt.
    //
    // La valeur n'est pas conservée en obsolète : une catégorie sans aucune clé
    // laisserait croire qu'il existe des paramètres de sécurité ici, et une
    // interface les afficherait comme une section vide.

}
