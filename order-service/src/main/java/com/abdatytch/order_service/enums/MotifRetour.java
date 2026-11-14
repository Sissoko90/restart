package com.abdatytch.order_service.enums;

/**
 * Les motifs qu'un acheteur peut invoquer.
 *
 * <h2>Une liste fermee, et pourquoi</h2>
 *
 * Un champ libre ne se compte pas. Sankadi arbitre : il doit pouvoir voir
 * qu'une boutique accumule les {@code NON_CONFORME}, ce qu'un texte libre ne
 * permet pas. Le commentaire libre existe en plus, pour les details.
 *
 * <p>{@link #JAMAIS_RECU} n'est pas un defaut de l'article mais de la
 * livraison. Il est ici quand meme : du point de vue de l'acheteur, c'est la
 * meme demande, et c'est a Sankadi de faire la part des choses.
 */
public enum MotifRetour {
    NON_CONFORME,
    ENDOMMAGE,
    TAILLE_INCORRECTE,
    JAMAIS_RECU,
    CHANGEMENT_D_AVIS,
    AUTRE
}
