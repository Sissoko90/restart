package com.abdatytch.notification_service.dto.response;

import com.abdatytch.notification_service.entities.JetonDAppareil;

import java.time.LocalDateTime;

/**
 * Un appareil enregistre, tel qu'on le rend a son proprietaire.
 *
 * <h2>Le jeton est TRONQUE, et c'est une regle de securite</h2>
 *
 * Un jeton d'enregistrement FCM est une <b>capacite</b> : qui le detient peut
 * faire afficher une notification sur cet appareil, a condition de posseder
 * aussi la cle du projet. Le rendre en entier le ferait circuler dans les
 * journaux d'acces, le cache du navigateur et l'historique des outils de
 * developpement, sans qu'aucun usage le justifie.
 *
 * <p>Ce que le proprietaire a besoin de reconnaitre, c'est SON appareil parmi
 * les siens — la plateforme et la date suffisent, et les derniers caracteres
 * permettent de distinguer deux appareils de meme plateforme.
 *
 * @param plateforme    ANDROID, IOS ou WEB
 * @param jetonAbrege   les derniers caracteres du jeton, pour distinguer deux
 *                      appareils semblables
 * @param enregistreLe  premiere declaration de cet appareil
 * @param vuLe          derniere fois qu'il s'est manifeste
 */
public record AppareilResponse(
        String plateforme,
        String jetonAbrege,
        LocalDateTime enregistreLe,
        LocalDateTime vuLe) {

    /** Nombre de caracteres de fin conserves. Assez pour distinguer, trop peu pour servir. */
    private static final int CARACTERES_VISIBLES = 6;

    public static AppareilResponse de(JetonDAppareil appareil) {
        return new AppareilResponse(
                appareil.getPlateforme(),
                abreger(appareil.getJeton()),
                appareil.getEnregistreLe(),
                appareil.getVuLe());
    }

    private static String abreger(String jeton) {
        if (jeton == null || jeton.length() <= CARACTERES_VISIBLES) {
            // Un jeton anormalement court est rendu entier : il ne peut pas
            // servir, et le masquer empecherait de diagnostiquer son origine.
            return jeton;
        }
        return "..." + jeton.substring(jeton.length() - CARACTERES_VISIBLES);
    }
}
