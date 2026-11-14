package com.abdatytch.order_service.service;

import com.abdatytch.order_service.entities.Commande;
import com.abdatytch.order_service.entities.EvenementCommande;
import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.exception.OrderException;
import com.abdatytch.order_service.metrics.OrderMetrics;
import com.abdatytch.order_service.repository.CommandeRepository;
import com.abdatytch.order_service.repository.EvenementCommandeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/**
 * L'unique applicateur de transitions de commande.
 *
 * <h2>Trois gestes qui ne doivent jamais se separer</h2>
 *
 * Une transition, c'est verifier qu'elle est legale, l'ecrire, et l'inscrire au
 * registre. Dispersees dans les services, ces trois operations finissent par se
 * desaccorder : un chemin oublie le registre, un autre oublie la verification.
 * Elles vivent donc ici, et les services appellent
 * {@link #faireAvancer(Commande, StatutCommande, String, RoleDeTransition, String)}.
 *
 * <h2>Le verrou est OPTIMISTE, dans le WHERE</h2>
 *
 * {@code changerStatut} porte {@code WHERE statut = :attendu}. Deux appels
 * concurrents qui veulent faire avancer la meme commande se presentent avec le
 * meme etat attendu, et un seul le trouve encore : le second recoit zero ligne
 * modifiee et echoue franchement.
 *
 * <p>Lire l'etat, verifier en Java, puis ecrire laisserait une fenetre entre la
 * lecture et l'ecriture. Sur une commande, cette fenetre suffit a expedier deux
 * fois, ou a rembourser deux fois.
 *
 * <h2>Le registre est ecrit DANS la meme transaction</h2>
 *
 * Une transition ecrite sans son evenement est une transition que Sankadi ne
 * peut pas prouver. Et Sankadi est l'intermediaire : quand la boutique affirme
 * avoir expedie et l'acheteur n'avoir rien recu, le registre est le seul
 * recours.
 */
@Component
public class MachineDeCommande {

    private static final Logger log = LoggerFactory.getLogger(MachineDeCommande.class);

    private final CommandeRepository commandes;
    private final EvenementCommandeRepository evenements;
    private final OrderMetrics metriques;

    public MachineDeCommande(CommandeRepository commandes,
                             EvenementCommandeRepository evenements,
                             OrderMetrics metriques) {
        this.commandes = commandes;
        this.evenements = evenements;
        this.metriques = metriques;
    }

    /**
     * Fait avancer une commande, ou echoue.
     *
     * @param commande la commande telle qu'elle a ete lue : son statut sert
     *                 d'etat attendu au verrou optimiste
     * @param vers     l'etat cible
     * @param parQui   le {@code sub} de l'auteur, ou un libelle pour le
     *                 balayeur
     * @param role     a quel titre. Le role importe autant que l'identite :
     *                 la meme personne peut etre acheteuse sur une commande et
     *                 arbitre sur une autre
     * @param motif    facultatif, mais indispensable sur une annulation
     * @return la commande rechargee, avec son nouvel etat
     * @throws OrderException 409 si la transition n'est pas permise depuis
     *         l'etat courant, ou si l'etat a change sous l'appelant
     */
    @Transactional
    public Mono<Commande> faireAvancer(Commande commande, StatutCommande vers,
                                       String parQui, RoleDeTransition role,
                                       String motif) {
        StatutCommande depuis = commande.getStatut();

        // La legalite est demandee a l'ENUMERATION, qui detient la machine. La
        // reecrire ici en ferait une seconde copie.
        if (!depuis.peutAllerVers(vers)) {
            return Mono.error(OrderException.transitionRefusee(depuis.name(), vers.name()));
        }

        return commandes.changerStatut(commande.getId(), depuis, vers)
                .flatMap(lignes -> {
                    if (lignes == 0) {
                        // L'etat a change entre la lecture et l'ecriture. Ce
                        // n'est pas une erreur de l'appelant mais une course,
                        // et il doit relire plutot que corriger sa requete.
                        log.info("Transition {} vers {} perdue sur la commande {} : "
                                        + "l'etat avait change", depuis, vers, commande.getId());
                        return Mono.error(
                                OrderException.transitionRefusee(depuis.name(), vers.name()));
                    }
                    // La transition est comptee ICI, et nulle part ailleurs :
                    // un seul endroit l'applique, donc un seul endroit la
                    // compte. Compter chez chaque appelant ferait oublier un
                    // chemin, et les autres compteraient double.
                    metriques.transition(vers.name(), role.name());
                    return inscrireAuRegistre(commande.getId(), depuis, vers, parQui, role, motif)
                            .then(commandes.findById(commande.getId()))
                            .switchIfEmpty(Mono.error(OrderException::introuvable));
                });
    }

    /**
     * L'evenement de CREATION, dont l'etat precedent est nul.
     *
     * <p>Distinct de {@link #faireAvancer} : il n'y a pas de transition a
     * verifier, et le registre doit quand meme commencer par l'arrivee de la
     * commande. Sans cet evenement, l'historique d'une commande annulee
     * n'indiquerait pas qui l'avait passee.
     */
    public Mono<Void> inscrireLaCreation(java.util.UUID commandeId, String parQui) {
        return inscrireAuRegistre(commandeId, null, StatutCommande.A_PAYER,
                parQui, RoleDeTransition.ACHETEUR, null).then();
    }

    private Mono<EvenementCommande> inscrireAuRegistre(
            java.util.UUID commandeId, StatutCommande depuis, StatutCommande vers,
            String parQui, RoleDeTransition role, String motif) {

        EvenementCommande evenement = new EvenementCommande();
        evenement.setCommandeId(commandeId);
        evenement.setDeStatut(depuis);
        evenement.setVersStatut(vers);
        evenement.setParQui(parQui);
        evenement.setRole(role);
        evenement.setMotif(motif);
        evenement.preparerAvantEcriture();
        // Aucun setId : l'identifiant est produit par la base, et le poser
        // ferait emettre un UPDATE pour une ligne jamais inseree. Le save()
        // serait alors silencieusement sans effet.
        return evenements.save(evenement);
    }
}
