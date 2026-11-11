package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.dto.request.AvisRequest;
import com.abdatytch.market_service.dto.response.AvisResponse;
import com.abdatytch.market_service.entities.Avis;
import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.exception.RefusExplique;
import com.abdatytch.market_service.mapper.MarketMapper;
import com.abdatytch.market_service.repository.AvisRepository;
import com.abdatytch.market_service.repository.ProduitRepository;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import com.abdatytch.market_service.service.IAvisService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Les avis clients.
 *
 * <h2>On ne note que ce qu'on a le droit de VOIR</h2>
 *
 * Chaque operation passe par la portee du catalogue. Sans cela, un particulier
 * pourrait noter un article reserve aux professionnels, ou d'un autre pays —
 * et surtout APPRENDRE qu'il existe, puisque la reponse differerait selon que
 * l'identifiant est connu ou non.
 *
 * <h2>Un vendeur ne note pas sa propre boutique</h2>
 *
 * Le controle est explicite, et le refus est explique : c'est une regle
 * comprehensible, pas une suspicion.
 */
@Service
public class AvisServiceImpl implements IAvisService {

    private final AvisRepository avis;
    private final ProduitRepository produits;

    public AvisServiceImpl(AvisRepository avis, ProduitRepository produits) {
        this.avis = avis;
        this.produits = produits;
    }

    @Override
    public Mono<AvisResponse> deposer(PorteeDuCatalogue portee, UUID produitId,
                                      AvisRequest demande) {
        return visible(portee, produitId)
                .flatMap(produit -> {
                    if (portee.boutiqueId() != null
                            && portee.boutiqueId().equals(produit.getBoutiqueId())) {
                        return Mono.<Avis>error(RefusExplique.etatIncompatible(
                                "Vous ne pouvez pas noter un article de votre propre "
                                        + "boutique."));
                    }
                    return avis.findByProduitIdAndAuthUserId(produitId, portee.authUserId())
                            .defaultIfEmpty(nouveau(produitId, portee.authUserId()));
                })
                .flatMap(existant -> {
                    existant.setNote(demande.note());
                    existant.setCommentaire(demande.commentaire());
                    // Toute ecriture repart EN_ATTENTE : un commentaire
                    // approuve puis remplace par un autre texte contournerait
                    // la moderation.
                    existant.setStatutModeration(StatutModeration.EN_ATTENTE);
                    existant.setMotifDecision(null);
                    existant.setDecidePar(null);
                    existant.setDecideLe(null);
                    return avis.save(existant);
                })
                // Les agregats sont recalcules : si l'avis etait APPROUVE et
                // repasse EN_ATTENTE, il ne doit plus compter dans la moyenne.
                .flatMap(sauve -> produits.reconcilierLesAgregats(produitId)
                        .thenReturn(sauve))
                .map(MarketMapper::versReponse);
    }

    @Override
    public Flux<AvisResponse> publies(PorteeDuCatalogue portee, UUID produitId,
                                      int page, int taille) {
        return visible(portee, produitId)
                .flatMapMany(produit -> avis.publiesPourProduit(produitId, taille, page * taille))
                .map(MarketMapper::versReponse);
    }

    @Override
    public Mono<AvisResponse> mien(String authUserId, UUID produitId) {
        return avis.findByProduitIdAndAuthUserId(produitId, authUserId)
                .map(MarketMapper::versReponse)
                .switchIfEmpty(Mono.error(MarketException.introuvable()));
    }

    private Mono<com.abdatytch.market_service.entities.Produit> visible(
            PorteeDuCatalogue portee, UUID produitId) {
        return produits.parIdentifiantVisible(produitId, portee.administrateur(),
                        portee.boutiqueId(), portee.professionnel(), portee.pays())
                .switchIfEmpty(Mono.error(MarketException.introuvable()));
    }

    private Avis nouveau(UUID produitId, String authUserId) {
        Avis neuf = new Avis();
        neuf.setProduitId(produitId);
        neuf.setAuthUserId(authUserId);
        return neuf;
    }
}
