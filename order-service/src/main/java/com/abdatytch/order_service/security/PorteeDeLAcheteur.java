package com.abdatytch.order_service.security;

import java.util.UUID;

/**
 * Ce que ce service sait de l'acheteur, et qu'il transmet a market-service.
 *
 * <h2>Il ne DECIDE rien</h2>
 *
 * Cette portee ne contient aucune regle : elle porte des faits — qui, quel
 * pays, quelle boutique, professionnel ou non. C'est market-service qui en
 * tire les conclusions de visibilite, parce que la regle n'a qu'un detenteur
 * dans ce depot et que {@code RegleDeVisibiliteUniqueTest} l'impose.
 *
 * <p>La tentation serait d'ajouter ici un {@code peutAcheter(...)}. Ce serait
 * une seconde copie de la regle, et c'est ainsi qu'un article reserve aux
 * professionnels devient achetable par un particulier : non par une faute, mais
 * parce qu'une des deux copies a oublie une condition.
 *
 * <h2>Le pays ne vient JAMAIS de la requete</h2>
 *
 * Il est lu chez user-service. Un parametre laisserait quiconque acheter un
 * article d'un autre pays en se declarant d'ailleurs.
 *
 * @param pays         code a deux lettres, ou nul si user-service n'a pas
 *                     repondu. Nul interdit de commander : voir
 *                     {@code OrderException.paysInconnu()}
 * @param boutiqueId   la boutique de l'acheteur s'il en a une, pour qu'un
 *                     vendeur puisse commander ses propres articles
 * @param professionnel si vrai, il voit les articles reserves aux pro et ceux
 *                      des autres pays
 */
public record PorteeDeLAcheteur(
        String authUserId,
        String pays,
        UUID boutiqueId,
        boolean professionnel) {

    /** Le pays est-il connu ? Sans lui, aucune commande ne peut etre creee. */
    public boolean paysConnu() {
        return pays != null && !pays.isBlank();
    }
}
