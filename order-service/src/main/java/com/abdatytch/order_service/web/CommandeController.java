package com.abdatytch.order_service.web;

import com.abdatytch.order_service.constants.CheminsDesCommandes;
import com.abdatytch.order_service.dto.request.DemandeDeRetourRequest;
import com.abdatytch.order_service.dto.response.CommandeResponse;
import com.abdatytch.order_service.dto.response.RetourResponse;
import com.abdatytch.order_service.security.ResolveurDePortee;
import com.abdatytch.order_service.service.ICommandeService;
import com.abdatytch.order_service.service.IRetourService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Les commandes de l'acheteur courant, et ses demandes de retour.
 *
 * <h2>Le passage en caisse n'est pas idempotent, et il est borne</h2>
 *
 * {@code POST /commandes} vide le panier en le convertissant. Un second appel
 * ne trouve plus de panier ouvert et rend 400 : l'acheteur ne peut pas
 * commander deux fois par un double-clic, parce que le panier a disparu.
 *
 * <p>Ce n'est pas une idempotence formelle — un jeton de requete le serait —
 * mais c'est une protection REELLE contre le cas qui arrive vraiment.
 */
@RestController
@RequestMapping(CheminsDesCommandes.COMMANDES)
public class CommandeController {

    private final ICommandeService commandes;
    private final IRetourService retours;
    private final ResolveurDePortee portees;

    public CommandeController(ICommandeService commandes, IRetourService retours,
                              ResolveurDePortee portees) {
        this.commandes = commandes;
        this.retours = retours;
        this.portees = portees;
    }

    /**
     * Transforme le panier en commandes : UNE PAR BOUTIQUE.
     *
     * <p>Rend donc une LISTE, et non une commande. Un panier de trois boutiques
     * en produit trois, et masquer cela derriere un objet unique obligerait le
     * client a le redecouvrir au premier suivi de livraison.
     */
    @PostMapping
    public Mono<List<CommandeResponse>> passerCommande() {
        return portees.courante().flatMap(commandes::passerCommande);
    }

    /** Mes commandes, la plus recente d'abord. */
    @GetMapping
    public Flux<CommandeResponse> mesCommandes() {
        return portees.courante().flatMapMany(commandes::mesCommandes);
    }

    /** Une de mes commandes. 404 si ce n'est pas la mienne. */
    @GetMapping("/{commandeId}")
    public Mono<CommandeResponse> detail(@PathVariable UUID commandeId) {
        return portees.courante().flatMap(portee -> commandes.detail(portee, commandeId));
    }

    /**
     * J'annule, tant que rien n'est encaisse.
     *
     * <p>Au-dela, 409 avec un motif en clair qui dirige vers la demande de
     * retour. Un refus sans direction fait revenir l'acheteur au support, et
     * c'est Sankadi qui le recoit.
     */
    @PostMapping("/{commandeId}/annulation")
    public Mono<CommandeResponse> annuler(@PathVariable UUID commandeId,
                                          @RequestParam(required = false)
                                          @Size(max = 500) String motif) {
        return portees.courante()
                .flatMap(portee -> commandes.annulerParLAcheteur(portee, commandeId, motif));
    }

    /** Je demande un retour. Sankadi arbitrera. */
    @PostMapping("/{commandeId}/retours")
    public Mono<RetourResponse> demanderUnRetour(
            @PathVariable UUID commandeId,
            @Valid @RequestBody DemandeDeRetourRequest demande) {
        return portees.courante()
                .flatMap(portee -> retours.demander(portee, commandeId, demande));
    }

    /** Mes demandes de retour sur une commande. */
    @GetMapping("/{commandeId}/retours")
    public Flux<RetourResponse> mesRetours(@PathVariable UUID commandeId) {
        return portees.courante()
                .flatMapMany(portee -> retours.mesRetours(portee, commandeId));
    }
}
