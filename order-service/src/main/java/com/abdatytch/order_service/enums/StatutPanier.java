package com.abdatytch.order_service.enums;

/**
 * L'etat d'un panier.
 *
 * <p>{@link #ABANDONNE} existe pour la mesure et non pour le menage : un panier
 * ferme sans commande est le signal le plus direct d'un parcours d'achat qui
 * echoue, et le supprimer effacerait precisement ce qu'on veut compter.
 */
public enum StatutPanier {
    OUVERT,
    CONVERTI,
    ABANDONNE
}
