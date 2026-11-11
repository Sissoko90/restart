package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.cache.CacheDePortee;
import com.abdatytch.market_service.client.ClientProfilUtilisateur;
import com.abdatytch.market_service.dto.request.CreationBoutiqueRequest;
import com.abdatytch.market_service.dto.request.PieceJustificativeRequest;
import com.abdatytch.market_service.dto.response.BoutiqueResponse;
import com.abdatytch.market_service.entities.Boutique;
import com.abdatytch.market_service.entities.BoutiqueDocument;
import com.abdatytch.market_service.enums.StatutBoutique;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.exception.RefusExplique;
import com.abdatytch.market_service.mapper.MarketMapper;
import com.abdatytch.market_service.repository.BoutiqueDocumentRepository;
import com.abdatytch.market_service.repository.BoutiqueRepository;
import com.abdatytch.market_service.service.IBoutiqueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Le cycle de vie d'une boutique.
 *
 * <h2>Le pays vient de user-service, et son absence REFUSE l'ouverture</h2>
 *
 * Sans pays, un produit ne serait visible de personne : la clause de
 * visibilite compare {@code p.pays = :pays} pour tout particulier. Ouvrir une
 * boutique sans pays creerait donc une vitrine dont rien ne sortirait jamais,
 * sans que son proprietaire comprenne pourquoi.
 *
 * <p>Le refus dit quoi faire — completer son profil — au lieu de laisser
 * deviner.
 */
@Service
public class BoutiqueServiceImpl implements IBoutiqueService {

    private static final Logger log = LoggerFactory.getLogger(BoutiqueServiceImpl.class);

    private final BoutiqueRepository boutiques;
    private final BoutiqueDocumentRepository pieces;
    private final ClientProfilUtilisateur profils;
    private final CacheDePortee cache;

    public BoutiqueServiceImpl(BoutiqueRepository boutiques,
                               BoutiqueDocumentRepository pieces,
                               ClientProfilUtilisateur profils,
                               CacheDePortee cache) {
        this.boutiques = boutiques;
        this.pieces = pieces;
        this.profils = profils;
        this.cache = cache;
    }

    @Override
    public Mono<BoutiqueResponse> ouvrir(String authUserId, CreationBoutiqueRequest demande) {
        return boutiques.findByAuthUserId(authUserId)
                .flatMap(existante -> Mono.<BoutiqueResponse>error(RefusExplique.etatIncompatible(
                        "Ce compte tient deja une boutique. Modifiez-la au lieu d'en ouvrir "
                                + "une seconde.")))
                .switchIfEmpty(Mono.defer(() -> creer(authUserId, demande)));
    }

    private Mono<BoutiqueResponse> creer(String authUserId, CreationBoutiqueRequest demande) {
        return profils.pour(authUserId)
                // flatMap + justOrEmpty, et NON map.
                //
                // Dans Reactor, un `map` dont la fonction rend null leve une
                // NullPointerException — « The mapper returned a null value » —
                // au lieu de produire un signal vide. Le `switchIfEmpty`
                // ci-dessous n'etait donc jamais atteint, et un compte sans
                // pays recevait un 500 au lieu du refus explique qui lui dit
                // quoi faire. Mesure du 2026-11-12.
                .flatMap(profil -> Mono.justOrEmpty(profil.pays()))
                .switchIfEmpty(Mono.error(RefusExplique.etatIncompatible(
                        "Votre pays n'est pas renseigne. Completez votre profil avant "
                                + "d'ouvrir une boutique : c'est lui qui determine a qui "
                                + "vos produits seront visibles.")))
                .flatMap(pays -> {
                    Boutique boutique = new Boutique();
                    // setId() n'est JAMAIS appele : la base produit
                    // l'identifiant, et c'est ce qui dit a R2DBC que la ligne
                    // est neuve. Un identifiant deja pose ferait emettre un
                    // UPDATE pour une ligne jamais inseree.
                    boutique.setAuthUserId(authUserId);
                    appliquer(boutique, demande);
                    boutique.setPays(pays);
                    boutique.setStatut(StatutBoutique.EN_ATTENTE);
                    return boutiques.save(boutique);
                })
                .doOnNext(creee -> log.info("Boutique {} ouverte, en attente de moderation",
                        creee.getId()))
                .map(MarketMapper::versReponse);
    }

    @Override
    public Mono<BoutiqueResponse> mienne(String authUserId) {
        return boutiques.findByAuthUserId(authUserId)
                .map(MarketMapper::versReponse)
                .switchIfEmpty(Mono.error(MarketException.introuvable()));
    }

    @Override
    public Mono<BoutiqueResponse> modifier(String authUserId, CreationBoutiqueRequest demande) {
        return boutiques.findByAuthUserId(authUserId)
                .switchIfEmpty(Mono.error(MarketException.introuvable()))
                .flatMap(boutique -> {
                    if (boutique.getStatut() == StatutBoutique.SUSPENDUE) {
                        return Mono.error(RefusExplique.etatIncompatible(
                                "Cette boutique est suspendue. Contactez la moderation "
                                        + "avant toute modification."));
                    }
                    boolean piecesChangees =
                            !Objects.equals(boutique.getRccm(), demande.rccm())
                            || !Objects.equals(boutique.getNif(), demande.nif());
                    appliquer(boutique, demande);

                    // Changer le RCCM ou le NIF d'une boutique approuvee la
                    // renvoie en moderation : ce sont les pieces sur
                    // lesquelles la validation a porte. Sans cette regle, il
                    // suffirait de faire valider un dossier anodin puis de le
                    // remplacer.
                    if (piecesChangees && boutique.getStatut() == StatutBoutique.APPROUVEE) {
                        boutique.setStatut(StatutBoutique.EN_ATTENTE);
                        boutique.setMotifDecision(null);
                        log.info("Boutique {} renvoyee en moderation : RCCM ou NIF modifie",
                                boutique.getId());
                        return boutiques.save(boutique)
                                .flatMap(sauvee -> cache.evincer(authUserId).thenReturn(sauvee));
                    }
                    return boutiques.save(boutique);
                })
                .map(MarketMapper::versReponse);
    }

    @Override
    public Mono<Void> rattacherUnePiece(String authUserId, PieceJustificativeRequest demande) {
        return boutiques.findByAuthUserId(authUserId)
                .switchIfEmpty(Mono.error(MarketException.introuvable()))
                .flatMap(boutique -> {
                    BoutiqueDocument piece = new BoutiqueDocument();
                    piece.setBoutiqueId(boutique.getId());
                    piece.setType(demande.type());
                    piece.setDocumentId(demande.documentId());
                    return pieces.save(piece)
                            // Une piece ajoutee a une boutique APPROUVEE la
                            // renvoie en moderation, pour la meme raison que le
                            // RCCM : le dossier valide n'est plus celui qui a
                            // ete valide.
                            .then(Mono.defer(() -> {
                                if (boutique.getStatut() != StatutBoutique.APPROUVEE) {
                                    return Mono.empty();
                                }
                                boutique.setStatut(StatutBoutique.EN_ATTENTE);
                                return boutiques.save(boutique)
                                        .then(cache.evincer(authUserId));
                            }));
                })
                .then();
    }

    @Override
    public Mono<BoutiqueResponse> vitrine(UUID boutiqueId) {
        return boutiques.parIdentifiantSiApprouvee(boutiqueId)
                .map(MarketMapper::versReponse)
                .map(BoutiqueResponse::publique)
                // Une boutique en attente, rejetee ou suspendue est
                // INTROUVABLE pour un visiteur, et non « interdite » : un 403
                // confirmerait qu'elle existe.
                .switchIfEmpty(Mono.error(MarketException.introuvable()));
    }

    @Override
    public Flux<BoutiqueResponse> aModerer(int page, int taille) {
        return boutiques.aModerer(taille, page * taille).map(MarketMapper::versReponse);
    }

    /** Les champs que le proprietaire gouverne. Ni le pays, ni le statut. */
    private void appliquer(Boutique boutique, CreationBoutiqueRequest demande) {
        boutique.setNom(demande.nom());
        boutique.setDescription(demande.description());
        boutique.setRccm(demande.rccm());
        boutique.setNif(demande.nif());
        boutique.setVille(demande.ville());
        boutique.setAdresse(demande.adresse());
        boutique.setTelephone(demande.telephone());
    }
}
