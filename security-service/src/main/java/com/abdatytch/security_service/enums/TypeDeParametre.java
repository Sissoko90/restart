package com.abdatytch.security_service.enums;

/**
 * Nature d'un parametre de securite, et donc la façon de le valider.
 *
 * <p>Le type n'est pas decoratif : il est ce qui distingue cette politique du
 * magasin cle/valeur qu'elle remplace. Dans {@code setting-service}, toute
 * valeur etait une chaine — un seuil de verrouillage pouvait valoir
 * {@code "cinq"} sans que rien ne s'y oppose, et le defaut n'apparaissait qu'a
 * la lecture, loin de l'ecriture qui l'avait cause.
 */
public enum TypeDeParametre {

    /** {@code true} ou {@code false}, et rien d'autre. */
    BOOLEEN,

    /**
     * Entier, borne par le parametre.
     *
     * <p>Les bornes comptent autant que le type : un seuil de tentatives a zero
     * verrouillerait tout le monde a la premiere requete, et un seuil a un
     * million n'offrirait aucune protection. Les deux sont des entiers valides.
     */
    ENTIER,

    /**
     * Duree exprimee en minutes.
     *
     * <p>Une unite explicite dans le nom du parametre, jamais dans la valeur :
     * ce depot a deja paye une confusion entre secondes et millisecondes.
     */
    DUREE_MINUTES,

    /** Texte libre, longueur bornee. Pour un message affiche, pas pour un secret. */
    TEXTE
}
