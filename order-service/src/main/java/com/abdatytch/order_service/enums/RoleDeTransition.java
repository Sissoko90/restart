package com.abdatytch.order_service.enums;

/**
 * A quel titre une transition a ete demandee.
 *
 * <h2>Le role compte autant que l'identite</h2>
 *
 * La meme personne peut etre acheteuse sur une commande et administratrice sur
 * une autre. C'est le role EXERCE qui legitime la transition, et c'est lui que
 * le registre doit garder : savoir qu'une personne a annule une commande ne dit
 * pas si elle l'a fait comme cliente ou comme arbitre de Sankadi, et les deux
 * n'engagent pas la meme responsabilite.
 */
public enum RoleDeTransition {
    ACHETEUR,
    BOUTIQUE,
    ADMIN,

    /**
     * Les transitions du balayeur periodique : expiration d'une commande
     * impayee, cloture d'une fenetre de retour.
     *
     * <p>Distinct d'{@code ADMIN} : personne ne l'a decidee, et un audit doit
     * pouvoir separer ce qu'un humain a voulu de ce qu'une echeance a produit.
     */
    SYSTEME
}
