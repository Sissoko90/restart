package com.abdatytch.notification_service.constants;

/**
 * La plateforme d'un appareil enregistre pour le push.
 *
 * <h2>Une enumeration et non une chaine libre</h2>
 *
 * La valeur part en base, dans une colonne que l'exploitant lit, et servira de
 * dimension aux metriques. Une chaine libre y ferait coexister « android »,
 * « Android », « ANDROID » et « andoid » — et une etiquette de metrique non
 * bornee fait exploser le nombre de series Prometheus.
 *
 * <p>Jackson refuse une valeur inconnue et rend 400 : le client apprend tout de
 * suite que sa valeur n'est pas reconnue, au lieu d'enregistrer un appareil
 * dont la plateforme est fausse.
 */
public enum PlateformeDAppareil {

    ANDROID,

    IOS,

    /**
     * Navigateur, par Web Push.
     *
     * <p>FCM sait pousser vers un navigateur, donc ce canal n'est pas reserve
     * au mobile. Utile pour une application web installee, et c'est le chemin
     * qui ne demande ni magasin d'applications ni compte Apple.
     */
    WEB
}
