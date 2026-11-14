package com.abdatytch.order_service.web;

import com.abdatytch.order_service.constants.CheminsDesCommandes;
import com.abdatytch.order_service.dto.response.CommandeResponse;
import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.exception.OrderException;
import com.abdatytch.order_service.security.ResolveurDePortee;
import com.abdatytch.order_service.service.ICommandeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Les commandes recues par la boutique de l'utilisateur courant.
 *
 * <h2>La boutique n'a QU'UNE transition : « c'est pret »</h2>
 *
 * Elle declare le colis prepare. Elle ne constate ni l'encaissement — c'est
 * Sankadi qui encaisse — ni la livraison, ni l'expedition : c'est la logistique
 * qui remet le colis, et demain logistics-service qui le constatera.
 *
 * <p>Et elle n'a AUCUNE main sur les retours. C'est tout l'interet de
 * l'intermediaire : un retour accepte par Sankadi ne se refuse pas. La boutique
 * en est informee, pas consultee.
 *
 * <h2>La boutique de l'appelant n'est jamais dans le chemin</h2>
 *
 * Elle est resolue depuis le jeton, via market-service. Un
 * {@code /boutique/{boutiqueId}/commandes} laisserait un vendeur lire les
 * commandes d'un concurrent — ses prix, ses volumes, ses clients.
 */
@RestController
@RequestMapping(CheminsDesCommandes.BOUTIQUE)
public class BoutiqueCommandeController {

    private final ICommandeService commandes;
    private final ResolveurDePortee portees;

    public BoutiqueCommandeController(ICommandeService commandes, ResolveurDePortee portees) {
        this.commandes = commandes;
        this.portees = portees;
    }

    /**
     * Les commandes de MA boutique.
     *
     * <p>Rend 404 pour un compte sans boutique, et non une liste vide : une
     * liste vide laisserait croire qu'on a une boutique sans commandes, alors
     * qu'on n'a pas de boutique.
     */
    @GetMapping("/commandes")
    public Flux<CommandeResponse> mesCommandesRecues() {
        return portees.courante()
                .flatMapMany(portee -> portee.boutiqueId() == null
                        ? Flux.error(OrderException.introuvable())
                        : commandes.commandesDeLaBoutique(portee.boutiqueId()));
    }

    /**
     * Je declare le colis pret.
     *
     * <p>L'appartenance est verifiee : seule la boutique concernee peut
     * declarer. Sans ce controle, un vendeur ferait avancer la commande d'un
     * autre — et donc son encaissement.
     */
    @PostMapping("/commandes/{commandeId}/preparee")
    public Mono<CommandeResponse> declarerPreparee(@PathVariable UUID commandeId) {
        return portees.courante()
                .flatMap(portee -> portee.boutiqueId() == null
                        ? Mono.error(OrderException.introuvable())
                        : commandes.detail(portee, commandeId)
                                .filter(commande -> portee.boutiqueId()
                                        .equals(commande.boutiqueId()))
                                .switchIfEmpty(Mono.error(OrderException::introuvable))
                                .flatMap(commande -> commandes.fairePasserA(commandeId,
                                        StatutCommande.PREPAREE, portee.authUserId(),
                                        RoleDeTransition.BOUTIQUE, null)));
    }
}
