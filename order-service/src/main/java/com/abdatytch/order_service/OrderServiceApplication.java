package com.abdatytch.order_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Panier, commandes et retours de la place de marche Sankadi.
 *
 * <h2>SANKADI EST L'INTERMEDIAIRE</h2>
 *
 * L'acheteur n'a pas de relation directe avec la boutique : il commande aupres
 * de Sankadi, et c'est Sankadi qui arbitre les retours. Une boutique ne peut
 * donc pas refuser un retour que Sankadi a accepte.
 *
 * <p>Consequence sur le modele : ce service porte un REGISTRE des transitions
 * ({@code evenement_commande}), parce qu'un intermediaire qui ne peut pas
 * prouver ce qui s'est passe n'arbitre rien. Quand la boutique affirme avoir
 * expedie et l'acheteur n'avoir rien recu, le seul recours est l'historique.
 *
 * <h2>Ce que ce service NE fait pas</h2>
 *
 * <ul>
 *   <li>Il ne connait pas le catalogue. market-service detient les produits,
 *       les prix et le STOCK ; ce module reserve ce stock par un appel
 *       interne, et ne le duplique pas.</li>
 *   <li>Il ne deplace AUCUN argent. Le paiement est un service a part entiere,
 *       a venir. La commande porte les montants et la date d'encaissement
 *       constatee, pas les transactions.</li>
 *   <li>Il n'organise AUCUNE livraison. La logistique est un service a part
 *       entiere, a venir. La commande porte les dates d'expedition et de
 *       livraison constatees, parce que ce sont elles qui ouvrent la fenetre de
 *       retour.</li>
 * </ul>
 *
 * <h2>Les deux dates qui viennent de l'exterieur</h2>
 *
 * {@code payee_le} sera constatee par payment-service, {@code livree_le} par
 * logistics-service. Aucun endpoint n'est ecrit en avance pour eux : ce depot a
 * plusieurs fois paye du code ecrit pour un appelant qui n'existe pas. Ces deux
 * transitions sont donc pilotees aujourd'hui par l'administration Sankadi, par
 * les memes methodes de service — ce qui rend la place de marche exploitable a
 * la main des le premier jour, et laisse la couture prete.
 *
 * <h2>{@code @EnableScheduling}</h2>
 *
 * Deux balayages, et chacun protege de quelque chose de concret :
 *
 * <ul>
 *   <li>les commandes impayees dont l'echeance est passee sont annulees et leur
 *       stock rendu. Sans cela, un seul acheteur mal intentionne viderait une
 *       boutique sans rien debourser ;</li>
 *   <li>les commandes livrees dont la fenetre de retour est passee sont
 *       cloturees. C'est cet etat que payment-service attendra pour verser a la
 *       boutique : sans le balayage, aucune boutique ne serait jamais payee.</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
