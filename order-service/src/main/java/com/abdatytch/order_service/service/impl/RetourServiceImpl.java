package com.abdatytch.order_service.service.impl;

import com.abdatytch.order_service.dto.request.DecisionDeRetourRequest;
import com.abdatytch.order_service.dto.request.DemandeDeRetourRequest;
import com.abdatytch.order_service.dto.response.RetourResponse;
import com.abdatytch.order_service.entities.Commande;
import com.abdatytch.order_service.entities.Retour;
import com.abdatytch.order_service.enums.MotifRetour;
import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.enums.StatutRetour;
import com.abdatytch.order_service.exception.OrderException;
import com.abdatytch.order_service.exception.RefusExplique;
import com.abdatytch.order_service.metrics.OrderMetrics;
import com.abdatytch.order_service.repository.CommandeRepository;
import com.abdatytch.order_service.repository.RetourRepository;
import com.abdatytch.order_service.security.PorteeDeLAcheteur;
import com.abdatytch.order_service.service.IRetourService;
import com.abdatytch.order_service.service.MachineDeCommande;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Les retours, arbitres par Sankadi.
 *
 * <h2>Pourquoi la commande change d'etat en meme temps que le retour</h2>
 *
 * Un retour ouvert fait passer la commande en {@code RETOUR_EN_COURS}, ce qui
 * l'empeche d'etre cloturee par le balayeur — donc empeche la boutique d'etre
 * payee pendant l'arbitrage. Sans ce couplage, un retour serait accepte sur une
 * commande deja soldee, et Sankadi devrait reprendre l'argent a la boutique.
 *
 * <p>Un refus la renvoie a {@code LIVREE}, et sa fenetre de retour COURT
 * TOUJOURS : un acheteur dont le motif etait mal choisi peut redemander. C'est
 * voulu, et c'est pourquoi l'index unique ne bloque que les retours ouverts.
 *
 * <h2>JAMAIS_RECU est le seul motif acceptable avant la livraison</h2>
 *
 * Les autres supposent qu'on a vu l'article. Un colis qui n'arrive pas se
 * signale depuis {@code EXPEDIEE}, et sans fenetre : il n'y a pas de date de
 * livraison pour la faire courir, et exiger une livraison avant de signaler une
 * non-livraison serait absurde.
 */
@Service
public class RetourServiceImpl implements IRetourService {

    private static final Logger log = LoggerFactory.getLogger(RetourServiceImpl.class);

    private final RetourRepository retours;
    private final CommandeRepository commandes;
    private final MachineDeCommande machine;
    private final CommandeServiceImpl commandeService;
    private final OrderMetrics metriques;

    public RetourServiceImpl(RetourRepository retours, CommandeRepository commandes,
                             MachineDeCommande machine, CommandeServiceImpl commandeService,
                             OrderMetrics metriques) {
        this.retours = retours;
        this.commandes = commandes;
        this.machine = machine;
        this.commandeService = commandeService;
        this.metriques = metriques;
    }

    @Override
    public Mono<RetourResponse> demander(PorteeDeLAcheteur portee, UUID commandeId,
                                         DemandeDeRetourRequest demande) {
        return commandes.findById(commandeId)
                // L'APPARTENANCE, et 404 en cas de refus : un 403 confirmerait
                // que la commande existe.
                .filter(commande -> portee.authUserId().equals(commande.getAuthUserId()))
                .switchIfEmpty(Mono.error(OrderException::introuvable))
                .flatMap(commande -> verifierQuOnPeutDemander(commande, demande.motif())
                        // Mono.defer et non then(retours.ouvertDe(...)) : un
                        // argument de then est evalue a l'ASSEMBLAGE de la
                        // chaine, donc la base serait interrogee meme quand la
                        // verification ci-dessus echoue. Le resultat serait
                        // jete, mais la requete aurait eu lieu.
                        //
                        // Trouve par RetourServiceImplTest, dont le mock non
                        // stubbe rendait null : la preuve que l'appel partait
                        // avant que l'erreur ne remonte.
                        .then(Mono.defer(() -> retours.ouvertDe(commandeId)
                                // Un retour deja ouvert : 409 et non un second
                                // dossier. L'index unique partiel le refuserait
                                // de toute facon, mais une erreur de contrainte
                                // ne dit rien a l'acheteur.
                                .flatMap(deja -> Mono.<Retour>error(new RefusExplique(
                                        HttpStatus.CONFLICT,
                                        "Une demande de retour est deja en cours sur "
                                                + "cette commande")))
                                .switchIfEmpty(Mono.defer(() -> ecrire(commande, portee, demande)))))
                        .flatMap(retour -> machine.faireAvancer(commande,
                                        StatutCommande.RETOUR_EN_COURS, portee.authUserId(),
                                        RoleDeTransition.ACHETEUR, demande.motif().name())
                                .thenReturn(retour))
                        .map(retour -> rendre(retour, commande.getNumero())));
    }

    /**
     * Les trois conditions d'une demande recevable.
     *
     * <p>Rend un {@code Mono} vide quand tout va bien, et une erreur sinon. Un
     * booleen aurait oblige l'appelant a choisir le message, donc a le
     * dupliquer sur chaque chemin.
     */
    private Mono<Void> verifierQuOnPeutDemander(Commande commande, MotifRetour motif) {
        StatutCommande statut = commande.getStatut();

        if (motif == MotifRetour.JAMAIS_RECU) {
            // Un colis qui n'arrive pas se signale des l'expedition, et sans
            // fenetre : il n'y a pas de date de livraison pour la faire courir.
            if (statut != StatutCommande.EXPEDIEE && statut != StatutCommande.LIVREE) {
                return Mono.error(new RefusExplique(HttpStatus.CONFLICT,
                        "Un colis non recu ne peut etre signale qu'apres son expedition"));
            }
            return Mono.empty();
        }

        if (statut != StatutCommande.LIVREE) {
            return Mono.error(new RefusExplique(HttpStatus.CONFLICT,
                    "Un retour ne peut etre demande qu'apres la livraison"));
        }

        LocalDateTime echeance = commande.getRetourPossibleJusquAu();
        if (echeance == null || echeance.isBefore(LocalDateTime.now())) {
            // L'echeance est celle STOCKEE sur la commande, pas un recalcul
            // depuis le delai configure : l'acheteur a droit a la fenetre qu'on
            // lui a annoncee, meme si le reglage a change depuis.
            return Mono.error(new RefusExplique(HttpStatus.CONFLICT,
                    "La periode de retour est passee"));
        }
        return Mono.empty();
    }

    private Mono<Retour> ecrire(Commande commande, PorteeDeLAcheteur portee,
                                DemandeDeRetourRequest demande) {
        Retour retour = new Retour();
        retour.setCommandeId(commande.getId());
        retour.setDemandePar(portee.authUserId());
        retour.setMotif(demande.motif());
        retour.setCommentaire(demande.commentaire());
        retour.setStatut(StatutRetour.DEMANDE);
        retour.preparerAvantEcriture();
        return retours.save(retour)
                .doOnNext(ecrit -> {
                    metriques.retour("demande");
                    log.info("Retour demande sur la commande {} : motif {}",
                            commande.getNumero(), demande.motif());
                });
    }

    @Override
    public Flux<RetourResponse> mesRetours(PorteeDeLAcheteur portee, UUID commandeId) {
        return commandes.findById(commandeId)
                .filter(commande -> portee.authUserId().equals(commande.getAuthUserId()))
                .switchIfEmpty(Mono.error(OrderException::introuvable))
                .flatMapMany(commande -> retours.findByCommandeIdOrderByCreatedAtDesc(commandeId)
                        // L'acheteur ne voit pas QUI chez Sankadi a tranche :
                        // nommer l'agent l'expose a la pression d'un vendeur.
                        .map(retour -> rendreSansLAgent(retour, commande.getNumero())));
    }

    @Override
    public Mono<RetourResponse> arbitrer(UUID retourId, DecisionDeRetourRequest decision,
                                         String decidePar) {
        if (!decision.accepte()
                && (decision.motifDecision() == null || decision.motifDecision().isBlank())) {
            // La contrainte `retour_refus_motive` le refuserait aussi, mais une
            // erreur de contrainte ne dit rien a l'agent qui remplit le
            // formulaire.
            return Mono.error(new RefusExplique(HttpStatus.BAD_REQUEST,
                    "Un refus doit porter un motif"));
        }

        StatutRetour vers = decision.accepte() ? StatutRetour.ACCEPTE : StatutRetour.REFUSE;

        return transiter(retourId, StatutRetour.DEMANDE, vers, retour -> {
            retour.setDecidePar(decidePar);
            retour.setDecideLe(LocalDateTime.now());
            retour.setMotifDecision(decision.motifDecision());
        }).flatMap(retour -> suiteDeLArbitrage(retour, decision.accepte(), decidePar));
    }

    /**
     * Ce que l'arbitrage entraine sur la COMMANDE.
     *
     * <p>Un refus la renvoie a {@code LIVREE} : elle reprend son cours, le
     * balayeur pourra la cloturer, et la boutique sera payee. Une acceptation la
     * laisse en {@code RETOUR_EN_COURS} — donc non cloturable, donc la boutique
     * n'est pas payee tant que l'article n'est pas revenu.
     */
    private Mono<RetourResponse> suiteDeLArbitrage(Retour retour, boolean accepte,
                                                   String decidePar) {
        metriques.retour(accepte ? "accepte" : "refuse");

        return commandes.findById(retour.getCommandeId())
                .switchIfEmpty(Mono.error(OrderException::introuvable))
                .flatMap(commande -> {
                    if (accepte) {
                        return Mono.just(rendre(retour, commande.getNumero()));
                    }
                    return machine.faireAvancer(commande, StatutCommande.LIVREE, decidePar,
                                    RoleDeTransition.ADMIN, "Retour refuse")
                            .thenReturn(rendre(retour, commande.getNumero()));
                });
    }

    @Override
    public Mono<RetourResponse> marquerRecu(UUID retourId, String parQui) {
        return transiter(retourId, StatutRetour.ACCEPTE, StatutRetour.RECU,
                retour -> retour.setRecuLe(LocalDateTime.now()))
                .flatMap(retour -> commandes.findById(retour.getCommandeId())
                        .switchIfEmpty(Mono.error(OrderException::introuvable))
                        // LE STOCK EST RENDU ICI, et pas a l'acceptation : a
                        // l'acceptation, l'article n'etait pas encore reparti,
                        // et recrediter la boutique l'aurait fait vendre deux
                        // fois le meme exemplaire.
                        .flatMap(commande -> commandeService.rendreLeStockDe(commande)
                                .doOnNext(rendues -> log.info(
                                        "Retour recu sur la commande {} : {} ligne(s) "
                                                + "rendues au stock",
                                        commande.getNumero(), rendues))
                                .thenReturn(rendre(retour, commande.getNumero()))));
    }

    @Override
    public Mono<RetourResponse> rembourser(UUID retourId, String parQui) {
        return retours.findById(retourId)
                .switchIfEmpty(Mono.error(OrderException::introuvable))
                .flatMap(retour -> commandes.findById(retour.getCommandeId())
                        .switchIfEmpty(Mono.error(OrderException::introuvable))
                        .flatMap(commande -> transiter(retourId, StatutRetour.RECU,
                                StatutRetour.REMBOURSE, aEcrire -> {
                                    // Le montant est celui de la COMMANDE, et
                                    // non une valeur fournie par l'agent : un
                                    // montant saisi a la main est un montant
                                    // qui finit par differer de ce qui a ete
                                    // encaisse.
                                    aEcrire.setMontantRembourse(commande.getMontantArticles());
                                    aEcrire.setRembourseLe(LocalDateTime.now());
                                })
                                .flatMap(rembourse -> machine.faireAvancer(commande,
                                                StatutCommande.REMBOURSEE, parQui,
                                                RoleDeTransition.ADMIN, "Retour rembourse")
                                        .doOnNext(apres -> metriques.retour("rembourse"))
                                        .thenReturn(rendre(rembourse, commande.getNumero())))));
    }

    @Override
    public Flux<RetourResponse> enAttenteDArbitrage() {
        return retours.findAll()
                .filter(retour -> retour.getStatut() == StatutRetour.DEMANDE)
                .concatMap(retour -> commandes.findById(retour.getCommandeId())
                        .map(commande -> rendre(retour, commande.getNumero()))
                        // Une commande introuvable pour un retour existant ne
                        // doit pas faire disparaitre le retour de la file :
                        // c'est precisement le dossier qu'il faut regarder.
                        .defaultIfEmpty(rendre(retour, null)));
    }

    /**
     * La transition d'un retour, sous verrou optimiste.
     *
     * <p>Meme principe que pour la commande : le {@code WHERE statut = attendu}
     * est dans l'instruction. Deux agents qui arbitrent le meme dossier en meme
     * temps ne peuvent pas le trancher deux fois.
     */
    private Mono<Retour> transiter(UUID retourId, StatutRetour attendu, StatutRetour vers,
                                   java.util.function.Consumer<Retour> aPoser) {
        return retours.findById(retourId)
                .switchIfEmpty(Mono.error(OrderException::introuvable))
                .flatMap(retour -> {
                    if (!retour.getStatut().peutAllerVers(vers)) {
                        return Mono.error(OrderException.transitionRefusee(
                                retour.getStatut().name(), vers.name()));
                    }
                    return retours.changerStatut(retourId, attendu, vers)
                            .flatMap(lignes -> {
                                if (lignes == 0) {
                                    return Mono.error(OrderException.transitionRefusee(
                                            retour.getStatut().name(), vers.name()));
                                }
                                // Les champs de la decision sont ecrits APRES
                                // la transition : si la transition est perdue
                                // au profit d'un autre agent, rien n'a ete
                                // ecrit sur le dossier.
                                return retours.findById(retourId)
                                        .flatMap(frais -> {
                                            aPoser.accept(frais);
                                            frais.preparerAvantEcriture();
                                            return retours.save(frais);
                                        });
                            });
                });
    }

    private RetourResponse rendre(Retour retour, String numeroDeCommande) {
        return new RetourResponse(retour.getId(), retour.getCommandeId(), numeroDeCommande,
                retour.getMotif(), retour.getCommentaire(), retour.getStatut(),
                retour.getDecidePar(), retour.getDecideLe(), retour.getMotifDecision(),
                retour.getRecuLe(), retour.getMontantRembourse(), retour.getRembourseLe(),
                retour.getCreatedAt());
    }

    /** La meme reponse, sans l'identite de l'agent qui a tranche. */
    private RetourResponse rendreSansLAgent(Retour retour, String numeroDeCommande) {
        return new RetourResponse(retour.getId(), retour.getCommandeId(), numeroDeCommande,
                retour.getMotif(), retour.getCommentaire(), retour.getStatut(),
                null, retour.getDecideLe(), retour.getMotifDecision(),
                retour.getRecuLe(), retour.getMontantRembourse(), retour.getRembourseLe(),
                retour.getCreatedAt());
    }
}
