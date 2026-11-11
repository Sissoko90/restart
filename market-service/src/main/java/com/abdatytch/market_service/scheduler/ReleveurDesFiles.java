package com.abdatytch.market_service.scheduler;

import com.abdatytch.market_service.metrics.MarketMetrics;
import com.abdatytch.market_service.repository.AvisRepository;
import com.abdatytch.market_service.repository.BoutiqueRepository;
import com.abdatytch.market_service.repository.ProduitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Releve la taille des files de moderation.
 *
 * <h2>Pourquoi une tache planifiee et non un compteur a l'ecriture</h2>
 *
 * Un compteur incremente a la creation et decremente a la decision suppose
 * qu'aucun des deux ne manque jamais. Une decision reprise, une suppression en
 * cascade, un redemarrage : il derive, et personne ne s'en apercoit puisque
 * c'est la seule source.
 *
 * <p>Trois {@code COUNT} toutes les minutes coutent moins cher qu'une metrique
 * en laquelle on ne peut pas avoir confiance. Et ils portent sur des index
 * PARTIELS — qui ne contiennent que les lignes en attente — donc leur cout ne
 * grandit pas avec le catalogue.
 *
 * <h2>Ce que cette metrique rend visible</h2>
 *
 * Une file qui monte sans redescendre : plus personne ne modere. Rien d'autre
 * ne le signale — le service repond normalement, les vendeurs deposent, et
 * leurs articles ne sortent simplement jamais.
 */
@Component
public class ReleveurDesFiles {

    private static final Logger log = LoggerFactory.getLogger(ReleveurDesFiles.class);

    private final BoutiqueRepository boutiques;
    private final ProduitRepository produits;
    private final AvisRepository avis;
    private final MarketMetrics metriques;

    public ReleveurDesFiles(BoutiqueRepository boutiques, ProduitRepository produits,
                            AvisRepository avis, MarketMetrics metriques) {
        this.boutiques = boutiques;
        this.produits = produits;
        this.avis = avis;
        this.metriques = metriques;
    }

    /**
     * {@code initialDelay} a zero : le premier releve a lieu au demarrage.
     *
     * <p>Sans cela, les jauges resteraient a zero pendant la premiere minute —
     * et « zero en attente » ressemble beaucoup trop a « pas encore mesure »
     * pour qu'une alerte puisse s'y fier.
     */
    @Scheduled(fixedDelayString = "${market.moderation.releve-ms:60000}", initialDelay = 0)
    public void relever() {
        Mono.zip(boutiques.compterAModerer(), produits.compterAModerer(),
                        avis.compterAModerer())
                .subscribe(
                        trois -> metriques.filesDeModeration(
                                trois.getT1().intValue(),
                                trois.getT2().intValue(),
                                trois.getT3().intValue()),
                        // La base peut etre injoignable au demarrage. Les
                        // jauges gardent alors leur valeur precedente, ce qui
                        // vaut mieux que de les remettre a zero : un zero
                        // franc declencherait une alerte « plus rien a
                        // moderer » au moment ou l'on ne sait plus compter.
                        panne -> log.warn("Releve des files impossible ({}) : les "
                                        + "jauges gardent leur valeur precedente",
                                panne.getClass().getSimpleName()));
    }
}
