package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.cache.CacheDePortee;
import com.abdatytch.market_service.dto.request.DecisionRequest;
import com.abdatytch.market_service.dto.response.AvisResponse;
import com.abdatytch.market_service.dto.response.BoutiqueResponse;
import com.abdatytch.market_service.dto.response.ProduitResponse;
import com.abdatytch.market_service.entities.Boutique;
import com.abdatytch.market_service.enums.StatutBoutique;
import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.exception.RefusExplique;
import com.abdatytch.market_service.mapper.MarketMapper;
import com.abdatytch.market_service.metrics.MarketMetrics;
import com.abdatytch.market_service.repository.AvisRepository;
import com.abdatytch.market_service.repository.BoutiqueRepository;
import com.abdatytch.market_service.repository.ProduitRepository;
import com.abdatytch.market_service.service.IModerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Les trois files de moderation.
 *
 * <h2>Le vendeur est PREVENU, et les avis ne le sont pas encore</h2>
 *
 * Une decision sur une boutique ou sur un article part en courriel au vendeur.
 * Sans cela il devait revenir voir, et pour un refus il attendait une reponse
 * deja arrivee — puis redeposait le meme dossier.
 *
 * <p>Les AVIS ne declenchent aucune notification, et c'est une decision a
 * prendre plutot qu'un oubli : prevenir l'auteur d'un avis refuse ouvre une
 * discussion avec quelqu'un qui n'est pas client de la plateforme mais d'une
 * boutique. La question se tranchera avec le besoin.
 *
 * <h2>Le motif est OBLIGATOIRE au refus, et il est RENDU</h2>
 *
 * Verifie ici et non par une annotation : la regle depend du SENS de la
 * decision, et {@code @NotBlank} refuserait aussi les approbations. Un refus
 * sans motif se represente a l'identique et fait deux fois le travail de
 * l'equipe.
 *
 * <h2>Approuver une boutique EVINCE sa portee</h2>
 *
 * Sans cela, le vendeur attendrait l'expiration du cache pour voir ses droits
 * elargis — et conclurait que l'approbation n'a pas pris.
 *
 * <h2>Suspendre n'est pas rejeter</h2>
 *
 * Une boutique suspendue a deja ete approuvee : elle a des articles publies,
 * que la clause de visibilite retire aussitot puisqu'elle exige
 * {@code b.statut = 'APPROUVEE'}. Aucun produit n'est touche, donc lever la
 * suspension les rend tels qu'ils etaient — y compris ceux que le vendeur
 * avait lui-meme desactives.
 */
@Service
public class ModerationServiceImpl implements IModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationServiceImpl.class);

    private final BoutiqueRepository boutiques;
    private final ProduitRepository produits;
    private final AvisRepository avis;
    private final CacheDePortee cache;
    private final MarketMetrics metriques;
    private final NotificateurDeDecision notificateur;

    public ModerationServiceImpl(BoutiqueRepository boutiques, ProduitRepository produits,
                                 AvisRepository avis, CacheDePortee cache,
                                 MarketMetrics metriques,
                                 NotificateurDeDecision notificateur) {
        this.boutiques = boutiques;
        this.produits = produits;
        this.avis = avis;
        this.cache = cache;
        this.metriques = metriques;
        this.notificateur = notificateur;
    }

    @Override
    public Flux<BoutiqueResponse> boutiquesAModerer(int page, int taille) {
        return boutiques.aModerer(taille, page * taille).map(MarketMapper::versReponse);
    }

    @Override
    public Mono<BoutiqueResponse> deciderBoutique(String moderateurId, UUID boutiqueId,
                                                  DecisionRequest decision) {
        exigerUnMotif(decision);
        return boutiques.findById(boutiqueId)
                .switchIfEmpty(Mono.error(MarketException.introuvable()))
                .flatMap(boutique -> appliquer(boutique, moderateurId, decision))
                .flatMap(boutique -> boutiques.save(boutique)
                        // L'eviction suit l'ECRITURE, jamais l'inverse : evincer
                        // d'abord laisserait une fenetre ou une requete
                        // concurrente recalculerait l'ancienne portee et la
                        // remettrait en cache.
                        .flatMap(sauvee -> cache.evincer(sauvee.getAuthUserId())
                                .thenReturn(sauvee)))
                // La notification suit l'ecriture, et NE PEUT PAS la faire
                // echouer : la decision est deja en base.
                //
                // L'onErrorResume est ici ET dans NotificateurDeDecision, et ce
                // n'est pas une redondance inutile : le notificateur avale les
                // erreurs de SA chaine, celui-ci couvre ce qui echapperait
                // avant — une dependance non injectee, une exception levee de
                // façon synchrone. La garantie est ecrite a l'endroit ou elle
                // est promise.
                .flatMap(sauvee -> notifier(notificateur.boutique(
                        sauvee.getAuthUserId(), sauvee.getNom(), sens(decision),
                        decision.motif())).thenReturn(sauvee))
                .doOnNext(sauvee -> {
                    metriques.decisionDeModeration("boutique", decision.sens().name());
                    log.info("Boutique {} : {} par {}", sauvee.getId(),
                            sauvee.getStatut(), moderateurId);
                })
                .map(MarketMapper::versReponse);
    }

    private Mono<Boutique> appliquer(Boutique boutique, String moderateurId,
                                     DecisionRequest decision) {
        StatutBoutique cible = switch (decision.sens()) {
            case APPROUVER -> StatutBoutique.APPROUVEE;
            case REJETER -> StatutBoutique.REJETEE;
            case SUSPENDRE -> StatutBoutique.SUSPENDUE;
        };
        if (cible == StatutBoutique.SUSPENDUE
                && boutique.getStatut() != StatutBoutique.APPROUVEE) {
            return Mono.error(RefusExplique.etatIncompatible(
                    "Seule une boutique approuvee peut etre suspendue. Celle-ci est "
                            + boutique.getStatut() + "."));
        }
        boutique.setStatut(cible);
        boutique.setMotifDecision(decision.motif());
        boutique.setDecidePar(moderateurId);
        boutique.setDecideLe(LocalDateTime.now());
        return Mono.just(boutique);
    }

    @Override
    public Flux<ProduitResponse> produitsAModerer(int page, int taille) {
        return produits.aModerer(taille, page * taille)
                // Les files de moderation rendent une vue SOBRE : ni photos
                // signees, ni variantes. Le moderateur ouvre ensuite la fiche,
                // ou tout est charge. Enrichir la liste ferait un appel a
                // media-service pour un ecran de tri.
                .map(produit -> MarketMapper.versReponse(produit, null, List.of(),
                        List.of(), List.of()));
    }

    @Override
    public Mono<ProduitResponse> deciderProduit(String moderateurId, UUID produitId,
                                                DecisionRequest decision) {
        exigerUnMotif(decision);
        if (decision.sens() == DecisionRequest.Sens.SUSPENDRE) {
            throw RefusExplique.demandeInvalide(
                    "Un article ne se suspend pas : il se rejette, ou son vendeur le retire "
                            + "de la vitrine.");
        }
        return produits.findById(produitId)
                .switchIfEmpty(Mono.error(MarketException.introuvable()))
                .flatMap(produit -> {
                    produit.setStatutModeration(
                            decision.sens() == DecisionRequest.Sens.APPROUVER
                                    ? StatutModeration.APPROUVE : StatutModeration.REJETE);
                    produit.setMotifDecision(decision.motif());
                    produit.setDecidePar(moderateurId);
                    produit.setDecideLe(LocalDateTime.now());
                    return produits.save(produit);
                })
                .doOnNext(sauve -> {
                    metriques.decisionDeModeration("produit", decision.sens().name());
                    log.info("Produit {} : {} par {}", sauve.getId(),
                            sauve.getStatutModeration(), moderateurId);
                })
                // Le destinataire est le PROPRIETAIRE de la boutique, retrouve
                // depuis l'article. L'article ne porte pas d'identifiant de
                // compte : la propriete passe par la boutique, et un second
                // chemin finirait par dire autre chose.
                .flatMap(sauve -> notifier(boutiques.findById(sauve.getBoutiqueId())
                        .flatMap(boutique -> notificateur.produit(
                                boutique.getAuthUserId(), sauve.getNom(),
                                sens(decision), decision.motif())))
                        .thenReturn(sauve))
                .map(produit -> MarketMapper.versReponse(produit, null, List.of(),
                        List.of(), List.of()));
    }

    @Override
    public Flux<AvisResponse> avisAModerer(int page, int taille) {
        return avis.aModerer(taille, page * taille).map(MarketMapper::versReponse);
    }

    @Override
    public Mono<AvisResponse> deciderAvis(String moderateurId, UUID avisId,
                                          DecisionRequest decision) {
        exigerUnMotif(decision);
        if (decision.sens() == DecisionRequest.Sens.SUSPENDRE) {
            throw RefusExplique.demandeInvalide("Un avis se rejette, il ne se suspend pas.");
        }
        return avis.findById(avisId)
                .switchIfEmpty(Mono.error(MarketException.introuvable()))
                .flatMap(unAvis -> {
                    unAvis.setStatutModeration(
                            decision.sens() == DecisionRequest.Sens.APPROUVER
                                    ? StatutModeration.APPROUVE : StatutModeration.REJETE);
                    unAvis.setMotifDecision(decision.motif());
                    unAvis.setDecidePar(moderateurId);
                    unAvis.setDecideLe(LocalDateTime.now());
                    return avis.save(unAvis);
                })
                // La note moyenne du produit change : un avis approuve y entre,
                // un avis rejete en sort. Recalcule plutot qu'incremente — un
                // increment suppose connaitre l'etat precedent, et se
                // desynchronise des qu'une decision est reprise.
                .flatMap(sauve -> produits.reconcilierLesAgregats(sauve.getProduitId())
                        .thenReturn(sauve))
                .doOnNext(sauve -> {
                    metriques.decisionDeModeration("avis", decision.sens().name());
                    log.info("Avis {} : {} par {}", sauve.getId(),
                            sauve.getStatutModeration(), moderateurId);
                })
                .map(MarketMapper::versReponse);
    }

    /**
     * Rend un envoi de notification INCAPABLE de faire echouer son appelant.
     *
     * <p>Une decision de moderation est ecrite en base avant que cette methode
     * soit atteinte. Propager une erreur de notification annulerait donc la
     * REPONSE d'une operation deja faite : le moderateur verrait un echec,
     * reprendrait sa decision, et la file afficherait un element qui n'y est
     * plus.
     *
     * <p>L'echec est journalise par {@code NotificateurDeDecision}. Ce qui est
     * avale ici est ce qui lui echapperait.
     */
    private Mono<Void> notifier(Mono<Void> envoi) {
        return envoi.onErrorResume(erreur -> {
            log.warn("Notification de decision impossible ({}) : la decision reste "
                    + "acquise", erreur.getClass().getSimpleName());
            return Mono.empty();
        });
    }

    /**
     * Le sens d'une decision, pour le message envoye au vendeur.
     *
     * <p>Deux enumerations plutot qu'une : {@code DecisionRequest.Sens} decrit
     * ce qu'un moderateur DEMANDE — approuver, rejeter, suspendre — tandis que
     * {@code NotificateurDeDecision.Sens} porte ce qu'on ECRIT a quelqu'un.
     * Les confondre reviendrait a faire dependre le texte d'un courriel du
     * vocabulaire d'une API.
     */
    private static NotificateurDeDecision.Sens sens(DecisionRequest decision) {
        return switch (decision.sens()) {
            case APPROUVER -> NotificateurDeDecision.Sens.APPROUVE;
            case REJETER -> NotificateurDeDecision.Sens.REJETE;
            case SUSPENDRE -> NotificateurDeDecision.Sens.SUSPENDU;
        };
    }

    /** Un refus sans motif n'apprend rien a celui qui doit corriger. */
    private void exigerUnMotif(DecisionRequest decision) {
        if (decision.exigeUnMotif()
                && (decision.motif() == null || decision.motif().isBlank())) {
            throw RefusExplique.demandeInvalide(
                    "Un refus ou une suspension doit porter un motif : c'est lui qui "
                            + "permet de corriger.");
        }
    }
}
