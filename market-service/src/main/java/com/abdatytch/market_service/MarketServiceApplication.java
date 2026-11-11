package com.abdatytch.market_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * La place de marche : boutiques professionnelles, catalogue et moderation.
 *
 * <h2>Ce que ce service NE fait pas</h2>
 *
 * Il ne stocke aucun fichier — media-service detient les photos de produit et
 * les pieces justificatives, et ce module n'en garde que l'identifiant. Il ne
 * tient aucune identite — auth-service la detient. Il ne connait pas le pays
 * d'une personne — user-service le lui dit.
 *
 * <p>Il ne gere ni panier, ni commande, ni paiement : ce sont d'autres bornes.
 *
 * <h2>Ce qu'il detient, et lui seul</h2>
 *
 * La boutique et son dossier de validation, le catalogue, la file de
 * moderation, les avis — et la <b>regle de visibilite</b>, qui est sa raison
 * d'etre : qui voit quel produit, selon son pays et sa qualite de
 * professionnel.
 *
 * <h2>{@code @EnableScheduling}</h2>
 *
 * Pour la reconciliation des agregats d'avis, qui derivent par construction :
 * ils sont tenus a jour a l'ecriture pour eviter une agregation par produit
 * affiche, donc ils peuvent se desynchroniser si une moderation echoue a
 * mi-chemin.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class MarketServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketServiceApplication.class, args);
    }
}
