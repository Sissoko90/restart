package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.dto.request.ReservationStockRequest;
import com.abdatytch.market_service.dto.request.RestitutionStockRequest;
import com.abdatytch.market_service.dto.response.LigneReserveeResponse;
import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.entities.ProduitVariante;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.repository.ProduitRepository;
import com.abdatytch.market_service.repository.ProduitVarianteRepository;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import com.abdatytch.market_service.service.IStockInterne;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * La reservation de stock, arbitree par la base.
 *
 * <h2>TOUT ou RIEN, par une transaction</h2>
 *
 * {@code @Transactional} et non une compensation a la main. Rendre une par une
 * les lignes deja reservees quand la suivante manque laisse une seconde
 * occasion d'echouer, et un echec a cet endroit retient du stock pour une
 * commande qui n'existera jamais — invisible, et impossible a retrouver sans
 * inventaire.
 *
 * <h2>La visibilite est verifiee ICI</h2>
 *
 * Par {@code parIdentifiantVisible}, qui applique {@code PorteeDuCatalogue} —
 * le detenteur unique de la regle. order-service transmet ce qu'il sait de
 * l'acheteur et ne conclut rien : un second endroit qui deciderait de la
 * visibilite finirait par oublier une condition, et un article reserve aux
 * professionnels deviendrait achetable par un particulier.
 *
 * <h2>L'ordre des lignes est impose</h2>
 *
 * Les lignes sont traitees dans l'ordre de leur identifiant de variante, et non
 * dans celui du panier. Deux acheteurs dont les paniers se recouvrent
 * verrouillent alors les memes lignes dans le MEME ordre : sans cela, l'un
 * prend A puis B et l'autre B puis A, et les deux attendent l'autre
 * indefiniment. PostgreSQL finit par tuer l'un des deux, mais le symptome est
 * un passage en caisse qui echoue au hasard sous la charge.
 */
@Service
public class StockInterneImpl implements IStockInterne {

    private static final Logger log = LoggerFactory.getLogger(StockInterneImpl.class);

    private final ProduitRepository produits;
    private final ProduitVarianteRepository variantes;

    public StockInterneImpl(ProduitRepository produits, ProduitVarianteRepository variantes) {
        this.produits = produits;
        this.variantes = variantes;
    }

    @Override
    @Transactional
    public Mono<List<LigneReserveeResponse>> reserver(ReservationStockRequest demande) {
        PorteeDuCatalogue portee = new PorteeDuCatalogue(
                demande.authUserId(), demande.pays(), demande.boutiqueId(),
                demande.professionnel(), false);

        List<ReservationStockRequest.Ligne> ordonnees = demande.lignes().stream()
                .sorted(java.util.Comparator.comparing(ReservationStockRequest.Ligne::varianteId))
                .toList();

        // concatMap et non flatMap : l'ordre de verrouillage doit etre tenu,
        // donc les lignes sont traitees une a une. flatMap les lancerait en
        // parallele et l'ordre impose plus haut ne servirait a rien.
        return Flux.fromIterable(ordonnees)
                .concatMap(ligne -> reserverUneLigne(portee, ligne))
                .collectList();
    }

    private Mono<LigneReserveeResponse> reserverUneLigne(
            PorteeDuCatalogue portee, ReservationStockRequest.Ligne ligne) {

        return produits.parIdentifiantVisible(ligne.produitId(), portee.administrateur(),
                        portee.boutiqueId(), portee.professionnel(), portee.pays())
                // Invisible pour cet acheteur : la meme reponse qu'un produit
                // inexistant. Distinguer les deux dirait a un particulier qu'un
                // article existe mais lui est interdit, ce qui est une fuite
                // d'information sur le catalogue professionnel.
                .switchIfEmpty(Mono.error(() -> MarketException.introuvable()))
                .flatMap(produit -> variantes.findById(ligne.varianteId())
                        .switchIfEmpty(Mono.error(() -> MarketException.introuvable()))
                        // La variante doit appartenir au produit annonce. Sans
                        // ce controle, un appelant reserverait le stock d'un
                        // article bon marche en citant un produit visible, et
                        // la ligne de commande porterait le nom du mauvais.
                        .flatMap(variante -> produit.getId().equals(variante.getProduitId())
                                ? prendreLeStock(produit, variante, ligne.quantite())
                                : Mono.error(MarketException.introuvable())));
    }

    private Mono<LigneReserveeResponse> prendreLeStock(
            Produit produit, ProduitVariante variante, int quantite) {

        return variantes.reserver(variante.getId(), quantite)
                .flatMap(lignesModifiees -> {
                    if (lignesModifiees == 0) {
                        // Stock insuffisant ou variante inactive. Ce n'est pas
                        // une panne : c'est la reponse. L'erreur fait annuler la
                        // transaction, donc rend les lignes deja prises.
                        log.info("Reservation refusee : variante {} indisponible pour {} unites",
                                variante.getId(), quantite);
                        return Mono.error(MarketException.stockInsuffisant(variante.getId()));
                    }
                    return Mono.just(instantane(produit, variante, quantite));
                });
    }

    /**
     * L'instantane rendu a order-service.
     *
     * <p>Le prix vient de la VARIANTE et non du produit : c'est elle qui porte
     * le prix effectif d'une declinaison, et deux tailles peuvent ne pas couter
     * le meme prix. Le produit ne porte qu'un prix d'affichage.
     */
    private LigneReserveeResponse instantane(Produit produit, ProduitVariante variante,
                                             int quantite) {
        return new LigneReserveeResponse(
                produit.getId(),
                variante.getId(),
                produit.getBoutiqueId(),
                produit.getPays(),
                produit.getNom(),
                variante.getCouleur(),
                variante.getTaille(),
                variante.getSku(),
                variante.getPrix(),
                produit.getDevise(),
                quantite);
    }

    @Override
    @Transactional
    public Mono<Integer> rendre(RestitutionStockRequest demande) {
        // Meme ordre impose que pour la reservation : une restitution et une
        // reservation simultanees sur les memes articles se verrouillent
        // mutuellement si elles les prennent en sens inverse.
        return Flux.fromIterable(demande.lignes().stream()
                        .sorted(java.util.Comparator.comparing(
                                RestitutionStockRequest.Ligne::varianteId))
                        .toList())
                .concatMap(ligne -> variantes.rendre(ligne.varianteId(), ligne.quantite()))
                .reduce(0, Integer::sum)
                .doOnNext(rendues -> log.info("Stock rendu sur {} ligne(s)", rendues));
    }
}
