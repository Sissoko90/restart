package com.abdatytch.order_service.web;

import com.abdatytch.order_service.constants.CheminsDesCommandes;
import com.abdatytch.order_service.dto.request.AjoutAuPanierRequest;
import com.abdatytch.order_service.dto.response.PanierResponse;
import com.abdatytch.order_service.security.ResolveurDePortee;
import com.abdatytch.order_service.service.IPanierService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Le panier de l'acheteur courant.
 *
 * <h2>Aucun identifiant d'acheteur dans les chemins</h2>
 *
 * Tout est relatif a {@code /panier} : l'acheteur est le porteur du jeton, lu
 * par {@code ResolveurDePortee}. Un chemin comme
 * {@code /panier/{acheteurId}} laisserait quiconque consulter et modifier le
 * panier d'un autre, et il faudrait verifier l'appartenance a chaque endpoint —
 * donc l'oublier une fois.
 */
@RestController
@RequestMapping(CheminsDesCommandes.PANIER)
public class PanierController {

    private final IPanierService panier;
    private final ResolveurDePortee portees;

    public PanierController(IPanierService panier, ResolveurDePortee portees) {
        this.panier = panier;
        this.portees = portees;
    }

    /** Le panier, avec les prix et la disponibilite du jour. */
    @GetMapping
    public Mono<PanierResponse> afficher() {
        return portees.courante().flatMap(panier::afficher);
    }

    /** Ajoute un article, ou augmente sa quantite s'il y est deja. */
    @PostMapping("/lignes")
    public Mono<PanierResponse> ajouter(@Valid @RequestBody AjoutAuPanierRequest demande) {
        return portees.courante().flatMap(portee -> panier.ajouter(portee, demande));
    }

    /**
     * Fixe la quantite d'une ligne. Une quantite nulle la retire.
     *
     * <p>Un PUT et non un PATCH : la quantite est REMPLACEE, pas incrementee.
     * Un client qui rejoue sa requete apres un delai reseau doublerait la
     * quantite avec un incrementeur, et c'est ce qui arrive sur un reseau
     * mobile.
     */
    @PutMapping("/lignes/{ligneId}/quantite/{quantite}")
    public Mono<PanierResponse> fixerQuantite(@PathVariable UUID ligneId,
                                              @PathVariable int quantite) {
        return portees.courante()
                .flatMap(portee -> panier.fixerQuantite(portee, ligneId, quantite));
    }

    /** Vide le panier sans le fermer. */
    @DeleteMapping("/lignes")
    public Mono<PanierResponse> vider() {
        return portees.courante().flatMap(panier::vider);
    }
}
