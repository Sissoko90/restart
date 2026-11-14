package com.abdatytch.order_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Un refus de ce service, avec son statut.
 *
 * <h2>Le motif n'est PAS rendu a l'appelant</h2>
 *
 * {@code GlobalExceptionHandler} rend le libelle du statut, jamais
 * {@code getReason()} : un message interne nomme des classes, des tables,
 * parfois un identifiant. Le motif reel va au journal.
 *
 * <p>L'exception est {@link RefusExplique}, pour les motifs REDIGES a
 * l'intention de celui qui a fait la demande.
 */
public class OrderException extends ResponseStatusException {

    public OrderException(HttpStatus statut, String motif) {
        super(statut, motif);
    }

    /**
     * Introuvable — ou refuse, et c'est le meme code.
     *
     * <h3>404 et jamais 403, et ce n'est pas de la pudeur</h3>
     *
     * Un produit reserve aux professionnels ne doit pas etre distinguable d'un
     * produit inexistant. Un 403 confirmerait que la reference existe, et une
     * reference confirmee se revend — c'est la regle que ce depot applique
     * deja sur quotas-service et setting-service.
     */
    public static OrderException introuvable() {
        return new OrderException(HttpStatus.NOT_FOUND, "Ressource introuvable ou hors portee");
    }

    /**
     * Un article du panier n'est plus disponible.
     *
     * <h3>409, et surtout PAS compte comme une panne</h3>
     *
     * L'acheteur a vu l'article : il l'avait dans son panier. Un 404 lui
     * laisserait croire qu'il a disparu du catalogue, alors qu'il suffit
     * d'attendre un reapprovisionnement.
     *
     * <p>Et c'est une REPONSE, pas un incident : une rupture de stock sur un
     * article populaire ne doit pas ouvrir le disjoncteur et fermer le passage
     * en caisse pour tout le monde. {@code DisjoncteurSortantConfig} ne compte
     * pas les 4xx.
     */
    public static OrderException stockIndisponible() {
        return new OrderException(HttpStatus.CONFLICT,
                "Un article du panier n'est plus disponible dans la quantite demandee");
    }

    /**
     * Le pays de l'acheteur n'a pas pu etre resolu.
     *
     * <h3>Pourquoi refuser plutot que degrader</h3>
     *
     * Le pays est inscrit sur la commande, sert au controle de visibilite chez
     * market-service, et determinera la livraison. Une commande passee avec un
     * pays faux devra etre annulee apres avoir reserve du stock et peut-etre
     * encaisse — et l'acheteur l'aura vue aboutir.
     *
     * <p>La degradation silencieuse est acceptable sur une lecture ; pas sur
     * une ecriture qui engage de l'argent.
     */
    public static OrderException paysInconnu() {
        return new OrderException(HttpStatus.SERVICE_UNAVAILABLE,
                "Pays de l'acheteur non resolu : aucune commande ne peut etre creee");
    }

    /**
     * La transition demandee n'est pas permise depuis l'etat courant.
     *
     * <h3>409 et non 400</h3>
     *
     * La demande est bien formee ; c'est l'ETAT qui ne s'y prete pas, et il peut
     * avoir change depuis que l'appelant l'a lu. Un 400 lui dirait de corriger
     * sa requete, alors qu'il doit relire l'etat.
     */
    public static OrderException transitionRefusee(String de, String vers) {
        return new OrderException(HttpStatus.CONFLICT,
                "Transition refusee : " + de + " vers " + vers);
    }

    /** La dependance interrogee n'a pas repondu. Compte par le disjoncteur. */
    public static OrderException dependanceIndisponible(String laquelle) {
        return new OrderException(HttpStatus.SERVICE_UNAVAILABLE,
                "Dependance indisponible : " + laquelle);
    }
}
