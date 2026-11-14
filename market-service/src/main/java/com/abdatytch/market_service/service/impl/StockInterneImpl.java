package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.dto.request.ReservationStockRequest;
import com.abdatytch.market_service.dto.request.RestitutionStockRequest;
import com.abdatytch.market_service.dto.response.LigneDecriteResponse;
import com.abdatytch.market_service.dto.response.LigneReserveeResponse;
import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.entities.ProduitVariante;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.repository.ProduitRepository;
import com.abdatytch.market_service.repository.ProduitVarianteRepository;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import com.abdatytch.market_service.security.ResolveurDePortee;
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
    private final ResolveurDePortee portees;

    public StockInterneImpl(ProduitRepository produits, ProduitVarianteRepository variantes,
                            ResolveurDePortee portees) {
        this.produits = produits;
        this.variantes = variantes;
        this.portees = portees;
    }

    @Override
    @Transactional
    public Mono<List<LigneReserveeResponse>> reserver(ReservationStockRequest demande) {
        List<ReservationStockRequest.Ligne> ordonnees = demande.lignes().stream()
                .sorted(java.util.Comparator.comparing(ReservationStockRequest.Ligne::varianteId))
                .toList();

        // La portee est RESOLUE ICI, depuis le seul identifiant transmis.
        // L'appelant ne l'annonce pas : il suffirait sinon d'un appel forge pour
        // se declarer professionnel et acheter un article qui ne vous est pas
        // destine.
        return portees.pourLeCompte(demande.authUserId())
                .flatMap(portee -> Flux.fromIterable(ordonnees)
                        // concatMap et non flatMap : l'ordre de verrouillage
                        // doit etre tenu, donc les lignes sont traitees une a
                        // une. flatMap les lancerait en parallele et l'ordre
                        // impose plus haut ne servirait a rien.
                        .concatMap(ligne -> reserverUneLigne(portee, ligne))
                        .collectList())
                // LE GARDE-FOU DE TAILLE.
                //
                // Une reservation rend AUTANT de lignes qu'on lui en a
                // demande, ou elle echoue. Jamais moins.
                //
                // C'est une protection de forme, et non de cas : elle attrape
                // n'importe quel operateur qui laisserait tomber une ligne en
                // silence, y compris ceux qu'on ajoutera plus tard. Le defaut
                // du 2026-11-14 — un Mono vide avale par un flatMap — serait
                // mort ici meme sans le defaultIfEmpty qui le corrige a la
                // source.
                //
                // Deux filets valent mieux qu'un quand le cout d'un trou est
                // une commande a zero ligne, donc a zero franc, parfaitement
                // valide pour la base.
                .flatMap(reservees -> {
                    if (reservees.size() != ordonnees.size()) {
                        log.error("Reservation incoherente : {} ligne(s) rendues pour "
                                        + "{} demandees. Rien n'est conserve.",
                                reservees.size(), ordonnees.size());
                        return Mono.error(new IllegalStateException(
                                "Reservation incoherente : " + reservees.size() + " sur "
                                        + ordonnees.size()));
                    }
                    return Mono.just(reservees);
                });
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
                // defaultIfEmpty(0) : un UPDATE R2DBC qui ne touche AUCUNE ligne
                // emet un Mono VIDE, et non zero. Sans cette ligne, le flatMap
                // ci-dessous n'est jamais appele : la branche de refus est
                // sautee, la ligne disparait du resultat, et l'appel rend 200
                // avec une liste vide.
                //
                // Mesure du 2026-11-14 sur le service en marche : reserver 5
                // unites d'un article qui n'en a qu'une rendait « 200 [] » et
                // ne journalisait rien. L'appelant croyait avoir reserve.
                .defaultIfEmpty(0)
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

    /**
     * La description, sans ecriture ni transaction.
     *
     * <p>Pas de {@code @Transactional} : rien n'est ecrit, et une transaction
     * en lecture seule n'apporterait ici qu'une connexion retenue plus
     * longtemps.
     *
     * <p>Pas d'ordre impose sur les lignes non plus : aucun verrou n'est pris,
     * donc aucun interblocage possible. {@code flatMap} plutot que
     * {@code concatMap} : les lignes sont lues en parallele, ce qui divise le
     * temps d'affichage d'un panier de dix articles.
     */
    @Override
    public Mono<List<LigneDecriteResponse>> decrire(ReservationStockRequest demande) {
        return portees.pourLeCompte(demande.authUserId())
                .flatMap(portee -> Flux.fromIterable(demande.lignes())
                        .flatMap(ligne -> decrireUneLigne(portee, ligne))
                        .collectList());
    }

    private Mono<LigneDecriteResponse> decrireUneLigne(
            PorteeDuCatalogue portee, ReservationStockRequest.Ligne ligne) {

        return produits.parIdentifiantVisible(ligne.produitId(), portee.administrateur(),
                        portee.boutiqueId(), portee.professionnel(), portee.pays())
                .flatMap(produit -> variantes.findById(ligne.varianteId())
                        .filter(variante -> produit.getId().equals(variante.getProduitId()))
                        .map(variante -> decrite(produit, variante, ligne.quantite())))
                // Invisible, inexistant, ou variante d'un autre produit : la
                // ligne est rendue INDISPONIBLE et non omise. Omettre ferait
                // disparaitre une ligne du panier sans explication, et
                // l'acheteur chercherait ce qu'il avait mis.
                .switchIfEmpty(Mono.fromSupplier(() -> new LigneDecriteResponse(
                        ligne.produitId(), ligne.varianteId(), null, null, null,
                        null, null, null, null, null, ligne.quantite(),
                        false, "Article indisponible")));
    }

    private LigneDecriteResponse decrite(Produit produit, ProduitVariante variante,
                                         int quantiteDemandee) {
        boolean actif = Boolean.TRUE.equals(variante.getActif());
        int stock = variante.getStock() == null ? 0 : variante.getStock();
        boolean assez = stock >= quantiteDemandee;

        String motif = null;
        if (!actif) {
            motif = "Declinaison desactivee par la boutique";
        } else if (!assez) {
            // Le stock EXACT n'est pas rendu, et c'est delibere : il renseigne
            // un concurrent sur le volume de ventes d'une boutique. « Il n'en
            // reste pas assez » suffit a l'acheteur pour reduire sa quantite.
            motif = "Quantite demandee superieure au stock disponible";
        }

        return new LigneDecriteResponse(
                produit.getId(), variante.getId(), produit.getBoutiqueId(),
                produit.getPays(), produit.getNom(), variante.getCouleur(),
                variante.getTaille(), variante.getSku(), variante.getPrix(),
                produit.getDevise(), quantiteDemandee, actif && assez, motif);
    }

    @Override
    public Mono<com.abdatytch.market_service.dto.response.PorteeDuCompteResponse>
            porteeDuCompte(String authUserId) {
        return portees.pourLeCompte(authUserId)
                .map(portee -> new com.abdatytch.market_service.dto.response
                        .PorteeDuCompteResponse(
                        portee.boutiqueId(), portee.professionnel(), portee.pays()));
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
                // Meme piege qu'a la reservation : un UPDATE qui ne touche
                // aucune ligne emet un Mono VIDE. Ici la consequence serait un
                // total sous-estime, donc un journal qui affirme avoir rendu
                // moins qu'il n'a rendu.
                .concatMap(ligne -> variantes.rendre(ligne.varianteId(), ligne.quantite())
                        .defaultIfEmpty(0))
                .reduce(0, Integer::sum)
                .doOnNext(rendues -> log.info("Stock rendu sur {} ligne(s)", rendues));
    }
}
