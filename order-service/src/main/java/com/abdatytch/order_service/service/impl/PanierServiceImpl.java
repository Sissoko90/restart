package com.abdatytch.order_service.service.impl;

import com.abdatytch.order_service.client.ClientMarketCatalogue;
import com.abdatytch.order_service.client.ClientMarketStock;
import com.abdatytch.order_service.dto.request.AjoutAuPanierRequest;
import com.abdatytch.order_service.dto.response.LignePanierResponse;
import com.abdatytch.order_service.dto.response.PanierResponse;
import com.abdatytch.order_service.entities.Panier;
import com.abdatytch.order_service.entities.PanierLigne;
import com.abdatytch.order_service.enums.StatutPanier;
import com.abdatytch.order_service.exception.OrderException;
import com.abdatytch.order_service.exception.RefusExplique;
import com.abdatytch.order_service.repository.PanierLigneRepository;
import com.abdatytch.order_service.repository.PanierRepository;
import com.abdatytch.order_service.security.PorteeDeLAcheteur;
import com.abdatytch.order_service.service.IPanierService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Le panier.
 *
 * <h2>Aucun prix n'est stocke</h2>
 *
 * Les prix sont relus chez market-service a chaque affichage. Un prix copie
 * dans le panier devient faux des que la boutique le change, et l'acheteur
 * verrait un montant qui n'est pas celui qu'on lui demandera.
 *
 * <h2>Une ligne indisponible n'est pas supprimee</h2>
 *
 * Elle est rendue a part, avec son motif. Un article qui disparait du panier
 * sans explication se lit comme une perte de donnees, et l'acheteur cherche ce
 * qu'il avait mis — puis abandonne.
 *
 * <h2>Un seul appel sortant par affichage</h2>
 *
 * Toutes les lignes partent dans la meme demande. Un appel par ligne ferait
 * quarante aller-retours pour un panier de quarante articles, et c'est ce genre
 * de N+1 que ce depot a deja corrige ailleurs.
 */
@Service
public class PanierServiceImpl implements IPanierService {

    private static final Logger log = LoggerFactory.getLogger(PanierServiceImpl.class);

    private final PanierRepository paniers;
    private final PanierLigneRepository lignes;
    private final ClientMarketCatalogue catalogue;
    private final int lignesMaximum;
    private final int quantiteMaximumParLigne;

    public PanierServiceImpl(
            PanierRepository paniers,
            PanierLigneRepository lignes,
            ClientMarketCatalogue catalogue,
            @Value("${order.panier.lignes-maximum:50}") int lignesMaximum,
            @Value("${order.panier.quantite-maximum-par-ligne:20}") int quantiteMaximumParLigne) {
        this.paniers = paniers;
        this.lignes = lignes;
        this.catalogue = catalogue;
        this.lignesMaximum = lignesMaximum;
        this.quantiteMaximumParLigne = quantiteMaximumParLigne;
    }

    @Override
    public Mono<PanierResponse> afficher(PorteeDeLAcheteur portee) {
        return paniers.ouvertDe(portee.authUserId())
                .flatMap(panier -> enrichir(portee, panier))
                // Pas de panier : on en rend un VIDE et non un 404. Demander
                // son panier quand on n'en a pas n'est pas une faute, et un 404
                // obligerait chaque client a traiter ce cas a part.
                .switchIfEmpty(Mono.fromSupplier(
                        () -> new PanierResponse(null, List.of(), List.of(), 0L, null)));
    }

    @Override
    public Mono<PanierResponse> ajouter(PorteeDeLAcheteur portee, AjoutAuPanierRequest demande) {
        if (demande.quantite() > quantiteMaximumParLigne) {
            return Mono.error(new RefusExplique(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Quantite maximale par article : " + quantiteMaximumParLigne));
        }

        return panierOuvertOuNouveau(portee.authUserId())
                .flatMap(panier -> lignes.findByPanierIdOrderByCreatedAtAsc(panier.getId())
                        .count()
                        .flatMap(nombre -> {
                            // Le plafond est verifie AVANT l'ecriture, et il
                            // compte les lignes existantes : augmenter la
                            // quantite d'une ligne deja presente ne doit pas
                            // etre refuse parce que le panier est plein.
                            if (nombre >= lignesMaximum) {
                                return lignes.findByPanierIdAndVarianteId(
                                                panier.getId(), demande.varianteId())
                                        .switchIfEmpty(Mono.error(new RefusExplique(
                                                org.springframework.http.HttpStatus.BAD_REQUEST,
                                                "Panier plein : " + lignesMaximum
                                                        + " articles au maximum")))
                                        .then(Mono.just(panier));
                            }
                            return Mono.just(panier);
                        }))
                .flatMap(panier -> lignes.ajouterOuAugmenter(panier.getId(),
                                demande.produitId(), demande.varianteId(), demande.quantite())
                        .thenReturn(panier))
                .flatMap(panier -> enrichir(portee, panier));
    }

    @Override
    public Mono<PanierResponse> fixerQuantite(PorteeDeLAcheteur portee, UUID ligneId,
                                              int quantite) {
        if (quantite > quantiteMaximumParLigne) {
            return Mono.error(new RefusExplique(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Quantite maximale par article : " + quantiteMaximumParLigne));
        }

        return paniers.ouvertDe(portee.authUserId())
                .switchIfEmpty(Mono.error(OrderException::introuvable))
                .flatMap(panier -> lignes.findById(ligneId)
                        // LE CONTROLE D'APPARTENANCE. Sans lui, n'importe qui
                        // modifie le panier d'autrui en citant un identifiant
                        // de ligne. Et le refus rend 404 et non 403 : un 403
                        // confirmerait que la ligne existe, et une reference
                        // confirmee s'enumere.
                        .filter(ligne -> panier.getId().equals(ligne.getPanierId()))
                        .switchIfEmpty(Mono.error(OrderException::introuvable))
                        .flatMap(ligne -> quantite <= 0
                                ? lignes.delete(ligne)
                                : fixer(ligne, quantite))
                        .thenReturn(panier))
                .flatMap(panier -> enrichir(portee, panier));
    }

    private Mono<PanierLigne> fixer(PanierLigne ligne, int quantite) {
        ligne.setQuantite(quantite);
        ligne.preparerAvantEcriture();
        return lignes.save(ligne);
    }

    @Override
    public Mono<PanierResponse> vider(PorteeDeLAcheteur portee) {
        return paniers.ouvertDe(portee.authUserId())
                // Vider un panier inexistant n'est pas une erreur : le
                // resultat demande est deja atteint. Rendre 404 ferait echouer
                // un bouton « vider » appuye deux fois.
                .flatMap(panier -> lignes.deleteByPanierId(panier.getId())
                        .thenReturn(panier))
                .flatMap(panier -> enrichir(portee, panier))
                .switchIfEmpty(Mono.fromSupplier(
                        () -> new PanierResponse(null, List.of(), List.of(), 0L, null)));
    }

    /** Le panier ouvert, ou un nouveau. */
    private Mono<Panier> panierOuvertOuNouveau(String acheteur) {
        return paniers.ouvertDe(acheteur)
                .switchIfEmpty(Mono.defer(() -> {
                    Panier nouveau = new Panier();
                    nouveau.setAuthUserId(acheteur);
                    nouveau.setStatut(StatutPanier.OUVERT);
                    nouveau.preparerAvantEcriture();
                    return paniers.save(nouveau)
                            // Deux onglets peuvent arriver ici en meme temps.
                            // L'index unique partiel refuse le second, et on
                            // relit celui qui a gagne plutot que d'echouer :
                            // du point de vue de l'acheteur, son panier
                            // existe, et c'est tout ce qui compte.
                            .onErrorResume(erreur -> paniers.ouvertDe(acheteur));
                }));
    }

    /**
     * Enrichit les lignes avec ce que market-service en dit aujourd'hui.
     *
     * <p>Un panier sans ligne ne declenche AUCUN appel sortant : interroger
     * market-service pour une liste vide gaspillerait un aller-retour et
     * compterait dans les metriques du disjoncteur.
     */
    private Mono<PanierResponse> enrichir(PorteeDeLAcheteur portee, Panier panier) {
        return lignes.findByPanierIdOrderByCreatedAtAsc(panier.getId())
                .collectList()
                .flatMap(mesLignes -> {
                    if (mesLignes.isEmpty()) {
                        return Mono.just(new PanierResponse(
                                panier.getId(), List.of(), List.of(), 0L, null));
                    }
                    return catalogue.decrire(demandeDeDescription(portee, mesLignes))
                            .map(decrites -> composer(panier, mesLignes, decrites));
                });
    }

    private ClientMarketStock.ReservationDeStock demandeDeDescription(
            PorteeDeLAcheteur portee, List<PanierLigne> mesLignes) {
        return new ClientMarketStock.ReservationDeStock(
                portee.authUserId(),
                mesLignes.stream()
                        .map(l -> new ClientMarketStock.ReservationDeStock.Ligne(
                                l.getProduitId(), l.getVarianteId(), l.getQuantite()))
                        .toList());
    }

    /**
     * Recolle les lignes locales et les descriptions distantes.
     *
     * <p>L'appariement se fait sur {@code varianteId}, qui est unique par
     * panier — la contrainte {@code panier_ligne_variante_unique} le garantit.
     * S'apparier sur la position serait fragile : market-service decrit les
     * lignes en parallele et ne garantit pas l'ordre.
     */
    private PanierResponse composer(Panier panier, List<PanierLigne> mesLignes,
                                    List<ClientMarketCatalogue.LigneDecrite> decrites) {

        Map<UUID, ClientMarketCatalogue.LigneDecrite> parVariante = decrites.stream()
                .collect(Collectors.toMap(ClientMarketCatalogue.LigneDecrite::varianteId,
                        Function.identity(), (a, b) -> a));

        List<LignePanierResponse> disponibles = new ArrayList<>();
        List<LignePanierResponse> indisponibles = new ArrayList<>();
        long total = 0L;
        String devise = null;

        for (PanierLigne ligne : mesLignes) {
            ClientMarketCatalogue.LigneDecrite decrite = parVariante.get(ligne.getVarianteId());
            if (decrite == null) {
                // market-service n'a rien dit de cette ligne. On la rend
                // indisponible plutot que de l'omettre : une absence de reponse
                // n'est pas une absence d'article.
                indisponibles.add(ligneIndisponible(ligne, "Article non decrit"));
                continue;
            }

            Long montant = decrite.prixUnitaire() == null ? null
                    : decrite.prixUnitaire() * ligne.getQuantite();

            LignePanierResponse rendue = new LignePanierResponse(
                    ligne.getId(), ligne.getProduitId(), ligne.getVarianteId(),
                    decrite.boutiqueId(), decrite.nomProduit(), decrite.couleur(),
                    decrite.taille(), decrite.prixUnitaire(), decrite.devise(),
                    ligne.getQuantite(), montant, decrite.disponible(), decrite.motif());

            if (decrite.disponible() && montant != null) {
                disponibles.add(rendue);
                total += montant;
                devise = decrite.devise();
            } else {
                indisponibles.add(rendue);
            }
        }

        if (!indisponibles.isEmpty()) {
            log.debug("Panier {} : {} ligne(s) indisponible(s) sur {}",
                    panier.getId(), indisponibles.size(), mesLignes.size());
        }

        return new PanierResponse(panier.getId(), disponibles, indisponibles, total, devise);
    }

    private LignePanierResponse ligneIndisponible(PanierLigne ligne, String motif) {
        return new LignePanierResponse(ligne.getId(), ligne.getProduitId(),
                ligne.getVarianteId(), null, null, null, null, null, null,
                ligne.getQuantite(), null, false, motif);
    }
}
