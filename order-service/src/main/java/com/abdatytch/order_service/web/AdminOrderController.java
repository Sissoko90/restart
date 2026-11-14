package com.abdatytch.order_service.web;

import com.abdatytch.order_service.constants.CheminsDesCommandes;
import com.abdatytch.order_service.dto.response.CommandeResponse;
import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.security.ResolveurDePortee;
import com.abdatytch.order_service.service.ICommandeService;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * L'exploitation et l'ARBITRAGE, cote Sankadi.
 *
 * <h2>LA COUTURE DES DEUX SERVICES A VENIR</h2>
 *
 * {@code /commandes/{id}/payee} sera appele par payment-service quand il
 * existera ; {@code /expediee} et {@code /livree} par logistics-service. En
 * attendant, c'est l'administration Sankadi qui s'en sert : la place de marche
 * est exploitable a la main des le premier jour.
 *
 * <p>Ces endpoints ne sont PAS ecrits en avance pour des appelants qui
 * n'existent pas — ils ont un appelant reel aujourd'hui. C'est la difference
 * avec le code mort que ce depot a plusieurs fois paye.
 *
 * <h2>Le role inscrit au registre est ADMIN</h2>
 *
 * Et il deviendra {@code SYSTEME} le jour ou un service appellera. Le registre
 * garde donc la trace de ce qui a ete decide par un humain et de ce qui a ete
 * constate par une machine — deux responsabilites differentes.
 */
@RestController
@RequestMapping(CheminsDesCommandes.ADMIN_COMMANDES)
public class AdminOrderController {

    private final ICommandeService commandes;
    private final ResolveurDePortee portees;

    public AdminOrderController(ICommandeService commandes, ResolveurDePortee portees) {
        this.commandes = commandes;
        this.portees = portees;
    }

    /** Les commandes dans un etat donne : la file de travail de Sankadi. */
    @GetMapping
    public Flux<CommandeResponse> parStatut(@RequestParam StatutCommande statut) {
        return commandes.parStatut(statut);
    }

    /**
     * Sankadi constate l'encaissement.
     *
     * <p>Le point que payment-service appellera. Aujourd'hui, un agent qui a vu
     * l'argent arriver.
     */
    @PostMapping("/{commandeId}/payee")
    public Mono<CommandeResponse> constaterLePaiement(@PathVariable UUID commandeId) {
        return transiter(commandeId, StatutCommande.PAYEE, null);
    }

    /** Sankadi constate l'expedition. Le point que logistics-service appellera. */
    @PostMapping("/{commandeId}/expediee")
    public Mono<CommandeResponse> constaterLExpedition(@PathVariable UUID commandeId) {
        return transiter(commandeId, StatutCommande.EXPEDIEE, null);
    }

    /**
     * Sankadi constate la livraison. OUVRE LA FENETRE DE RETOUR.
     *
     * <p>C'est la transition la plus consequente du lot : elle demarre le delai
     * pendant lequel l'acheteur peut retourner, et elle rend la commande
     * cloturable — donc payable a la boutique.
     */
    @PostMapping("/{commandeId}/livree")
    public Mono<CommandeResponse> constaterLaLivraison(@PathVariable UUID commandeId) {
        return transiter(commandeId, StatutCommande.LIVREE, null);
    }

    /**
     * Sankadi annule, et le stock est RENDU.
     *
     * <p>Un motif est exige : une annulation administrative sans motif est
     * indefendable devant un vendeur, et c'est Sankadi qui devra la defendre.
     */
    @PostMapping("/{commandeId}/annulation")
    public Mono<CommandeResponse> annuler(@PathVariable UUID commandeId,
                                          @RequestParam @Size(min = 3, max = 500) String motif) {
        return transiter(commandeId, StatutCommande.ANNULEE, motif);
    }

    private Mono<CommandeResponse> transiter(UUID commandeId, StatutCommande vers,
                                             String motif) {
        return portees.courante()
                .flatMap(portee -> commandes.fairePasserA(commandeId, vers,
                        portee.authUserId(), RoleDeTransition.ADMIN, motif));
    }
}
