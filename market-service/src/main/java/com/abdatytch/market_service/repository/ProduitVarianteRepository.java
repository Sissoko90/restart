package com.abdatytch.market_service.repository;

import com.abdatytch.market_service.entities.ProduitVariante;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Les declinaisons d'un produit : couleur, taille, prix, stock. */
@Repository
public interface ProduitVarianteRepository extends ReactiveCrudRepository<ProduitVariante, UUID> {

    Flux<ProduitVariante> findByProduitId(UUID produitId);

    Mono<Void> deleteByProduitId(UUID produitId);

    /**
     * Retire du stock, si et seulement s'il y en a assez.
     *
     * <h3>La base arbitre, pas le Java</h3>
     *
     * La condition {@code stock >= :quantite} est DANS l'instruction. Lire le
     * stock, verifier en Java, puis ecrire laisserait une fenetre entre la
     * lecture et l'ecriture : deux acheteurs du dernier article y liraient tous
     * deux « stock 1 » et le prendraient tous deux. C'est le defaut le plus
     * classique d'une place de marche, et le seul moyen de l'eviter est de ne
     * jamais separer la verification de l'ecriture.
     *
     * <p>{@code actif = TRUE} est dans la meme condition : une variante
     * desactivee pendant que l'acheteur remplissait son panier ne doit pas etre
     * vendue.
     *
     * @return 1 si la reservation a eu lieu, 0 si le stock etait insuffisant ou
     *         la variante inactive. Jamais d'exception : l'indisponibilite
     *         n'est pas une panne, c'est une reponse
     */
    @Query("""
            UPDATE produit_variante
               SET stock = stock - :quantite, updated_at = CURRENT_TIMESTAMP
             WHERE id = :varianteId AND actif = TRUE AND stock >= :quantite
            """)
    Mono<Integer> reserver(UUID varianteId, int quantite);

    /**
     * Rend du stock : annulation d'une commande impayee, ou retour recu.
     *
     * <p>Aucune condition de plafond, et c'est voulu. Rendre ne peut pas
     * echouer : si on refusait de rendre, le stock serait definitivement perdu
     * pour la boutique. Le pire cas est un stock plus eleve que le reel, qui se
     * corrige par un inventaire ; refuser produirait une perte seche.
     *
     * <p>Pas de condition {@code actif = TRUE} non plus : une variante
     * desactivee entre-temps doit quand meme recuperer son stock, sinon la
     * reactiver la montrerait vide.
     */
    @Query("""
            UPDATE produit_variante
               SET stock = stock + :quantite, updated_at = CURRENT_TIMESTAMP
             WHERE id = :varianteId
            """)
    Mono<Integer> rendre(UUID varianteId, int quantite);
}
