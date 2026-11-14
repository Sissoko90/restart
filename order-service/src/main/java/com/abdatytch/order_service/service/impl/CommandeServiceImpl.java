package com.abdatytch.order_service.service.impl;

import com.abdatytch.order_service.client.ClientMarketStock;
import com.abdatytch.order_service.dto.response.CommandeResponse;
import com.abdatytch.order_service.dto.response.LigneCommandeResponse;
import com.abdatytch.order_service.entities.Commande;
import com.abdatytch.order_service.entities.CommandeLigne;
import com.abdatytch.order_service.entities.Panier;
import com.abdatytch.order_service.entities.PanierLigne;
import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.enums.StatutPanier;
import com.abdatytch.order_service.exception.OrderException;
import com.abdatytch.order_service.exception.RefusExplique;
import com.abdatytch.order_service.metrics.OrderMetrics;
import com.abdatytch.order_service.repository.CommandeLigneRepository;
import com.abdatytch.order_service.repository.CommandeRepository;
import com.abdatytch.order_service.repository.PanierLigneRepository;
import com.abdatytch.order_service.repository.PanierRepository;
import com.abdatytch.order_service.security.PorteeDeLAcheteur;
import com.abdatytch.order_service.service.ICommandeService;
import com.abdatytch.order_service.service.MachineDeCommande;
import com.abdatytch.order_service.util.CalculDesMontants;
import com.abdatytch.order_service.util.NumeroDeCommande;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Le passage en caisse, et la vie d'une commande.
 *
 * <h2>L'ORDRE DES OPERATIONS, et pourquoi il est celui-la</h2>
 *
 * <ol>
 *   <li>le pays de l'acheteur est resolu. S'il est inconnu, on REFUSE : le pays
 *       est inscrit sur la commande et sert au controle de visibilite ;</li>
 *   <li>le stock est reserve chez market-service, en tout ou rien ;</li>
 *   <li>les commandes sont ecrites, une par boutique ;</li>
 *   <li>le panier est marque converti.</li>
 * </ol>
 *
 * <h2>LA LIMITE CONNUE : deux bases, pas de transaction commune</h2>
 *
 * La reservation vit chez market-service, la commande ici. Si l'etape 3 echoue
 * apres une reservation reussie, le stock est rendu par COMPENSATION — un
 * appel explicite a {@code rendre}.
 *
 * <p>Si cette compensation echoue a son tour, du stock reste retenu sans
 * commande : invisible, et introuvable sans inventaire. Ce trou n'est pas
 * cache, il est MESURE — {@code OrderMetrics#compensationEchouee()} le compte,
 * une alerte le surveille, et le detail des lignes est journalise pour pouvoir
 * rendre le stock a la main.
 *
 * <p>Le fermer completement demanderait une reservation IDEMPOTENTE, portant
 * l'identifiant de la commande, que market-service pourrait rejouer sans
 * double effet. C'est la bonne suite ; ce n'est pas fait, et le dire vaut mieux
 * que le laisser croire.
 */
@Service
public class CommandeServiceImpl implements ICommandeService {

    private static final Logger log = LoggerFactory.getLogger(CommandeServiceImpl.class);

    private final CommandeRepository commandes;
    private final CommandeLigneRepository lignesDeCommande;
    private final PanierRepository paniers;
    private final PanierLigneRepository lignesDePanier;
    private final ClientMarketStock stock;
    private final MachineDeCommande machine;
    private final OrderMetrics metriques;
    private final BigDecimal tauxDeCommission;
    private final long delaiDePaiementMinutes;
    private final long fenetreDeRetourEnJours;

    public CommandeServiceImpl(
            CommandeRepository commandes,
            CommandeLigneRepository lignesDeCommande,
            PanierRepository paniers,
            PanierLigneRepository lignesDePanier,
            ClientMarketStock stock,
            MachineDeCommande machine,
            OrderMetrics metriques,
            @Value("${order.commission.taux-pourcent:5.00}") BigDecimal tauxDeCommission,
            @Value("${order.paiement.delai-minutes:30}") long delaiDePaiementMinutes,
            @Value("${order.retour.fenetre-jours:7}") long fenetreDeRetourEnJours) {
        this.commandes = commandes;
        this.lignesDeCommande = lignesDeCommande;
        this.paniers = paniers;
        this.lignesDePanier = lignesDePanier;
        this.stock = stock;
        this.machine = machine;
        this.metriques = metriques;
        this.tauxDeCommission = tauxDeCommission;
        this.delaiDePaiementMinutes = delaiDePaiementMinutes;
        this.fenetreDeRetourEnJours = fenetreDeRetourEnJours;
    }

    @Override
    public Mono<List<CommandeResponse>> passerCommande(PorteeDeLAcheteur portee) {
        // Le pays d'abord, avant toute reservation : refuser ici ne coute rien,
        // refuser apres avoir reserve obligerait a compenser.
        if (!portee.paysConnu()) {
            return Mono.error(OrderException.paysInconnu());
        }

        long debut = System.nanoTime();

        return paniers.ouvertDe(portee.authUserId())
                .switchIfEmpty(Mono.error(() -> new RefusExplique(HttpStatus.BAD_REQUEST,
                        "Aucun panier ouvert")))
                .flatMap(panier -> lignesDePanier.findByPanierIdOrderByCreatedAtAsc(panier.getId())
                        .collectList()
                        .flatMap(lignes -> lignes.isEmpty()
                                ? Mono.error(new RefusExplique(HttpStatus.BAD_REQUEST,
                                        "Le panier est vide"))
                                : reserverPuisEcrire(portee, panier, lignes)))
                // Le refus de stock est compte ici et non dans le client : le
                // client sert aussi l'affichage du panier, ou un article epuise
                // n'est pas un refus de caisse. C'est le CHEMIN qui donne son
                // sens a la mesure, pas le code HTTP.
                .doOnError(erreur -> {
                    if (erreur instanceof OrderException refus
                            && refus.getStatusCode().value() == 409) {
                        metriques.refusDeStock();
                    }
                })
                // doFinally et non doOnSuccess : un passage en caisse qui
                // ECHOUE est celui qui dure le plus longtemps, et l'exclure de
                // la mesure cacherait precisement ce qu'on veut voir.
                .doFinally(signal -> metriques.dureeDeLaCaisse().record(
                        System.nanoTime() - debut, java.util.concurrent.TimeUnit.NANOSECONDS));
    }

    private Mono<List<CommandeResponse>> reserverPuisEcrire(
            PorteeDeLAcheteur portee, Panier panier, List<PanierLigne> lignes) {

        ClientMarketStock.ReservationDeStock demande = new ClientMarketStock.ReservationDeStock(
                portee.authUserId(),
                lignes.stream()
                        .map(l -> new ClientMarketStock.ReservationDeStock.Ligne(
                                l.getProduitId(), l.getVarianteId(), l.getQuantite()))
                        .toList());

        return stock.reserver(demande)
                .flatMap(reservees -> ecrireLesCommandes(portee, panier, reservees)
                        // La COMPENSATION. Elle ne s'applique qu'a un echec
                        // POSTERIEUR a la reservation : une reservation refusee
                        // n'a rien pris, et rendre du stock jamais pris le
                        // creerait.
                        .onErrorResume(erreur -> compenser(reservees, erreur)));
    }

    /**
     * Rend le stock reserve apres un echec d'ecriture, puis propage l'erreur.
     *
     * <p>L'erreur d'origine est TOUJOURS propagee, meme si la compensation
     * reussit : l'acheteur n'a pas de commande, et lui rendre un succes serait
     * un mensonge.
     */
    private <T> Mono<T> compenser(List<ClientMarketStock.LigneReservee> reservees,
                                  Throwable erreurDOrigine) {
        ClientMarketStock.RestitutionDeStock restitution =
                new ClientMarketStock.RestitutionDeStock(reservees.stream()
                        .map(r -> new ClientMarketStock.LigneARendre(
                                r.varianteId(), r.quantite()))
                        .toList());

        log.error("Ecriture de commande echouee apres reservation ({}) : compensation "
                + "de {} ligne(s)", erreurDOrigine.getClass().getSimpleName(), reservees.size());

        return stock.rendre(restitution)
                .doOnNext(rendues -> log.warn("Compensation reussie : {} ligne(s) rendues",
                        rendues))
                .onErrorResume(echecDeCompensation -> {
                    // LE TROU EST ICI, et il est mesure. Le detail des lignes
                    // est journalise pour pouvoir rendre le stock a la main :
                    // sans lui, il n'existe aucun moyen de savoir quoi
                    // rendre.
                    metriques.compensationEchouee();
                    log.error("COMPENSATION ECHOUEE ({}) : du stock reste retenu sans "
                                    + "commande. Lignes a rendre manuellement : {}",
                            echecDeCompensation.getClass().getSimpleName(),
                            restitution.lignes());
                    return Mono.just(0);
                })
                .then(Mono.error(erreurDOrigine));
    }

    /**
     * Ecrit une commande par boutique.
     *
     * <p>Le regroupement se fait sur la boutique rendue par market-service, et
     * non sur une boutique que le client aurait annoncee : c'est market-service
     * qui sait a qui appartient un article.
     */
    private Mono<List<CommandeResponse>> ecrireLesCommandes(
            PorteeDeLAcheteur portee, Panier panier,
            List<ClientMarketStock.LigneReservee> reservees) {

        Map<UUID, List<ClientMarketStock.LigneReservee>> parBoutique = reservees.stream()
                .collect(Collectors.groupingBy(ClientMarketStock.LigneReservee::boutiqueId));

        return Flux.fromIterable(parBoutique.entrySet())
                // concatMap : les commandes sont ecrites une a une. En
                // parallele, deux insertions pourraient tirer le meme numero,
                // et le second echouerait sur la contrainte d'unicite.
                .concatMap(entree -> ecrireUneCommande(portee, entree.getKey(), entree.getValue()))
                .collectList()
                .flatMap(ecrites -> fermerLePanier(panier).thenReturn(ecrites));
    }

    private Mono<CommandeResponse> ecrireUneCommande(
            PorteeDeLAcheteur portee, UUID boutiqueId,
            List<ClientMarketStock.LigneReservee> reservees) {

        long montantArticles = reservees.stream()
                .mapToLong(r -> r.prixUnitaire() * r.quantite())
                .sum();

        var decomposition = CalculDesMontants.decomposer(montantArticles, tauxDeCommission);

        Commande commande = new Commande();
        commande.setNumero(NumeroDeCommande.nouveau());
        commande.setAuthUserId(portee.authUserId());
        commande.setBoutiqueId(boutiqueId);
        // Le pays de la COMMANDE est celui de l'acheteur, et non celui du
        // produit : c'est lui qui determinera la livraison et la fiscalite.
        commande.setPays(portee.pays());
        commande.setStatut(StatutCommande.A_PAYER);
        commande.setMontantArticles(decomposition.montantArticles());
        commande.setCommissionTauxPourcent(tauxDeCommission);
        commande.setCommissionMontant(decomposition.commission());
        commande.setMontantDuALaBoutique(decomposition.duALaBoutique());
        // La devise vient du produit, et non d'un defaut : deux boutiques de
        // pays differents ne vendent pas dans la meme monnaie. Toutes les
        // lignes d'une commande partagent la boutique, donc la devise.
        commande.setDevise(reservees.get(0).devise());
        commande.setAPayerJusquAu(
                LocalDateTime.now().plusMinutes(delaiDePaiementMinutes));
        commande.preparerAvantEcriture();

        return commandes.save(commande)
                .flatMap(ecrite -> ecrireLesLignes(ecrite.getId(), reservees)
                        .then(machine.inscrireLaCreation(ecrite.getId(), portee.authUserId()))
                        .then(Mono.fromCallable(() -> {
                            metriques.commandeCreee();
                            return rendreDepuisLesReservees(ecrite, reservees, true);
                        })));
    }

    private Mono<Void> ecrireLesLignes(UUID commandeId,
                                       List<ClientMarketStock.LigneReservee> reservees) {
        return Flux.fromIterable(reservees)
                .concatMap(r -> {
                    CommandeLigne ligne = new CommandeLigne();
                    ligne.setCommandeId(commandeId);
                    ligne.setProduitId(r.produitId());
                    ligne.setVarianteId(r.varianteId());
                    // L'INSTANTANE. Ces valeurs ne changeront jamais, meme si
                    // la boutique renomme son article ou le supprime.
                    ligne.setNomProduit(r.nomProduit());
                    ligne.setCouleur(r.couleur());
                    ligne.setTaille(r.taille());
                    ligne.setSku(r.sku());
                    ligne.setPrixUnitaire(r.prixUnitaire());
                    ligne.setQuantite(r.quantite());
                    ligne.setMontantLigne(r.prixUnitaire() * r.quantite());
                    ligne.preparerAvantEcriture();
                    return lignesDeCommande.save(ligne);
                })
                .then();
    }

    /**
     * Le panier passe a CONVERTI, et ses lignes sont CONSERVEES.
     *
     * <p>Les supprimer effacerait ce qui a produit les commandes. Le panier
     * converti ne genant pas — l'index unique partiel ne porte que sur
     * {@code OUVERT} — l'acheteur en obtient un neuf au prochain ajout.
     */
    private Mono<Panier> fermerLePanier(Panier panier) {
        panier.setStatut(StatutPanier.CONVERTI);
        panier.preparerAvantEcriture();
        return paniers.save(panier);
    }

    @Override
    public Flux<CommandeResponse> mesCommandes(PorteeDeLAcheteur portee) {
        return commandes.findByAuthUserIdOrderByCreatedAtDesc(portee.authUserId())
                .concatMap(commande -> avecLignes(commande, false));
    }

    @Override
    public Mono<CommandeResponse> detail(PorteeDeLAcheteur portee, UUID commandeId) {
        return commandes.findById(commandeId)
                // L'APPARTENANCE. L'acheteur voit la sienne ; le proprietaire
                // d'une boutique voit celles de sa boutique. Tout autre cas
                // rend 404 : un 403 confirmerait que la commande existe.
                .filter(commande -> portee.authUserId().equals(commande.getAuthUserId())
                        || (portee.boutiqueId() != null
                            && portee.boutiqueId().equals(commande.getBoutiqueId())))
                .switchIfEmpty(Mono.error(OrderException::introuvable))
                .flatMap(commande -> avecLignes(commande,
                        // La boutique voit la decomposition, l'acheteur non.
                        portee.boutiqueId() != null
                                && portee.boutiqueId().equals(commande.getBoutiqueId())));
    }

    @Override
    public Flux<CommandeResponse> commandesDeLaBoutique(UUID boutiqueId) {
        return commandes.findByBoutiqueIdOrderByCreatedAtDesc(boutiqueId)
                .concatMap(commande -> avecLignes(commande, true));
    }

    @Override
    public Mono<CommandeResponse> annulerParLAcheteur(PorteeDeLAcheteur portee, UUID commandeId,
                                                      String motif) {
        return commandes.findById(commandeId)
                .filter(commande -> portee.authUserId().equals(commande.getAuthUserId()))
                .switchIfEmpty(Mono.error(OrderException::introuvable))
                .flatMap(commande -> {
                    // L'acheteur n'annule que tant que rien n'est encaisse.
                    // Au-dela, c'est a Sankadi d'arbitrer : la boutique a
                    // peut-etre deja prepare le colis, et le remboursement
                    // engage de l'argent.
                    if (commande.getStatut().argentEncaisse()) {
                        return Mono.error(new RefusExplique(HttpStatus.CONFLICT,
                                "Cette commande est deja payee : demander un retour "
                                        + "ou contacter le support"));
                    }
                    return annulerEtRendreLeStock(commande, portee.authUserId(),
                            RoleDeTransition.ACHETEUR, motif);
                });
    }

    /**
     * Annule et rend le stock.
     *
     * <p>Le stock est rendu APRES la transition, et non avant : si la
     * transition echoue — parce qu'un autre appel l'a devancee — le stock ne
     * doit pas avoir ete rendu pour une commande qui vit toujours.
     *
     * <p>Un echec de restitution ne fait PAS echouer l'annulation. L'acheteur a
     * droit a son annulation ; le stock non rendu est un probleme de boutique,
     * mesure et journalise, pas un motif de refuser.
     */
    Mono<CommandeResponse> annulerEtRendreLeStock(Commande commande, String parQui,
                                                  RoleDeTransition role, String motif) {
        return machine.faireAvancer(commande, StatutCommande.ANNULEE, parQui, role, motif)
                .flatMap(annulee -> inscrireLeMotif(annulee, motif))
                .flatMap(annulee -> rendreLeStockDe(annulee).thenReturn(annulee))
                .flatMap(annulee -> avecLignes(annulee, false));
    }

    private Mono<Commande> inscrireLeMotif(Commande commande, String motif) {
        if (motif == null || motif.isBlank()) {
            return Mono.just(commande);
        }
        commande.setMotifAnnulation(motif);
        commande.preparerAvantEcriture();
        return commandes.save(commande);
    }

    /** Rend le stock des lignes d'une commande. Echoue en silence mesure. */
    Mono<Integer> rendreLeStockDe(Commande commande) {
        return lignesDeCommande.findByCommandeIdOrderByCreatedAtAsc(commande.getId())
                .collectList()
                .flatMap(lignes -> {
                    if (lignes.isEmpty()) {
                        return Mono.just(0);
                    }
                    var restitution = new ClientMarketStock.RestitutionDeStock(lignes.stream()
                            .map(l -> new ClientMarketStock.LigneARendre(
                                    l.getVarianteId(), l.getQuantite()))
                            .toList());
                    return stock.rendre(restitution)
                            .onErrorResume(erreur -> {
                                metriques.compensationEchouee();
                                log.error("Stock NON rendu pour la commande {} ({}) : "
                                                + "lignes {}", commande.getNumero(),
                                        erreur.getClass().getSimpleName(), restitution.lignes());
                                return Mono.just(0);
                            });
                });
    }

    @Override
    public Mono<CommandeResponse> fairePasserA(UUID commandeId, StatutCommande vers,
                                               String parQui, RoleDeTransition role,
                                               String motif) {
        return commandes.findById(commandeId)
                .switchIfEmpty(Mono.error(OrderException::introuvable))
                .flatMap(commande -> {
                    // ANNULEE passe par le chemin qui rend le stock. Sans ce
                    // detour, une annulation administrative laisserait le stock
                    // retenu — et ce serait d'autant plus invisible que
                    // l'operation aurait l'air d'avoir reussi.
                    if (vers == StatutCommande.ANNULEE) {
                        return annulerEtRendreLeStock(commande, parQui, role, motif);
                    }
                    return machine.faireAvancer(commande, vers, parQui, role, motif)
                            .flatMap(this::posterTransition)
                            .flatMap(apres -> avecLignes(apres, role != RoleDeTransition.ACHETEUR));
                });
    }

    /**
     * Ce qui suit une transition : les dates constatees.
     *
     * <p>Les dates sont posees ICI et non par l'appelant. Laisser chaque
     * appelant poser sa date produirait un etat {@code LIVREE} sans
     * {@code livreeLe} — que la contrainte de base refuserait, mais seulement
     * sur les deux cas qu'elle couvre.
     */
    private Mono<Commande> posterTransition(Commande commande) {
        LocalDateTime maintenant = LocalDateTime.now();
        switch (commande.getStatut()) {
            case PAYEE -> commande.setPayeeLe(maintenant);
            case EXPEDIEE -> commande.setExpedieeLe(maintenant);
            case LIVREE -> {
                commande.setLivreeLe(maintenant);
                // La fenetre de retour s'ouvre A LA LIVRAISON et sa date est
                // STOCKEE. Recalculee depuis `livreeLe` plus un delai
                // configure, elle changerait sous les pieds d'un acheteur a qui
                // on a annonce sept jours.
                commande.setRetourPossibleJusquAu(
                        maintenant.plusDays(fenetreDeRetourEnJours));
            }
            case CLOTUREE -> commande.setClotureeLe(maintenant);
            default -> {
                // PREPAREE, RETOUR_EN_COURS, REMBOURSEE : aucune date propre.
                // Le registre des transitions porte deja l'horodatage, et une
                // colonne de plus par etat ferait une table que personne ne
                // tient a jour.
                return Mono.just(commande);
            }
        }
        commande.preparerAvantEcriture();
        return commandes.save(commande);
    }

    @Override
    public Flux<CommandeResponse> parStatut(StatutCommande statut) {
        return commandes.findAll()
                .filter(commande -> commande.getStatut() == statut)
                .concatMap(commande -> avecLignes(commande, true));
    }

    private Mono<CommandeResponse> avecLignes(Commande commande, boolean montrerLaCommission) {
        return lignesDeCommande.findByCommandeIdOrderByCreatedAtAsc(commande.getId())
                .map(l -> new LigneCommandeResponse(l.getProduitId(), l.getVarianteId(),
                        l.getNomProduit(), l.getCouleur(), l.getTaille(), l.getSku(),
                        l.getPrixUnitaire(), l.getQuantite(), l.getMontantLigne()))
                .collectList()
                .map(lignes -> rendre(commande, lignes, montrerLaCommission));
    }

    /**
     * La reponse construite depuis les lignes RESERVEES, sans relire la base.
     *
     * <p>Nom distinct de {@link #rendre} et non une surcharge : deux methodes
     * prenant {@code List<X>} et {@code List<Y>} ont la meme signature pour la
     * JVM, les parametres generiques etant effaces a la compilation.
     *
     * <p>Utilisee juste apres la creation : les lignes viennent d'etre ecrites
     * et sont deja en memoire. Les relire couterait une requete par commande
     * pour un resultat identique.
     */
    private CommandeResponse rendreDepuisLesReservees(
            Commande commande,
            List<ClientMarketStock.LigneReservee> reservees,
            boolean montrerLaCommission) {
        return rendre(commande, reservees.stream()
                .map(r -> new LigneCommandeResponse(r.produitId(), r.varianteId(),
                        r.nomProduit(), r.couleur(), r.taille(), r.sku(),
                        r.prixUnitaire(), r.quantite(), r.prixUnitaire() * r.quantite()))
                .toList(), montrerLaCommission);
    }

    /**
     * La reponse, avec ou sans la decomposition.
     *
     * <p>La commission est MASQUEE pour l'acheteur. Ce que Sankadi prend sur
     * une vente est une affaire entre Sankadi et la boutique : l'acheteur paie
     * le montant des articles et n'a pas a connaitre la repartition.
     *
     * <p>Le masquage est fait ICI, dans le service. Envoyer le champ et
     * compter sur le client pour ne pas l'afficher n'est pas une protection.
     */
    private CommandeResponse rendre(Commande commande, List<LigneCommandeResponse> lignes,
                                    boolean montrerLaCommission) {
        return new CommandeResponse(
                commande.getId(), commande.getNumero(), commande.getStatut(),
                commande.getBoutiqueId(), commande.getPays(),
                commande.getMontantArticles(),
                montrerLaCommission ? commande.getCommissionMontant() : null,
                montrerLaCommission ? commande.getMontantDuALaBoutique() : null,
                commande.getDevise(), commande.getAPayerJusquAu(), commande.getPayeeLe(),
                commande.getExpedieeLe(), commande.getLivreeLe(),
                commande.getRetourPossibleJusquAu(), commande.getClotureeLe(),
                commande.getMotifAnnulation(), commande.getCreatedAt(), lignes);
    }
}
