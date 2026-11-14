package com.abdatytch.order_service.repository;

import com.abdatytch.order_service.entities.Commande;
import com.abdatytch.order_service.enums.StatutCommande;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public interface CommandeRepository extends ReactiveCrudRepository<Commande, UUID> {

    Flux<Commande> findByAuthUserIdOrderByCreatedAtDesc(String authUserId);

    Flux<Commande> findByBoutiqueIdOrderByCreatedAtDesc(UUID boutiqueId);

    Mono<Commande> findByNumero(String numero);

    /**
     * La transition d'etat, en UNE instruction conditionnelle.
     *
     * <p>Le {@code WHERE statut = :attendu} est le verrou optimiste : deux
     * appels concurrents qui veulent faire avancer la meme commande se
     * presentent avec le meme etat attendu, et un seul le trouve encore. Le
     * second recoit zero ligne modifiee, et l'appelant sait que l'etat a change
     * sous lui.
     *
     * <p>Lire l'etat, verifier en Java, puis ecrire laisserait une fenetre
     * entre la lecture et l'ecriture. Sur une commande, cette fenetre suffit a
     * expedier deux fois ou a rembourser deux fois.
     *
     * <h3>{@code @Modifying} n'est pas decoratif</h3>
     *
     * <p>Sans lui, Spring Data R2DBC traite cette requete comme une LECTURE :
     * elle est bien executee, mais aucun compte de lignes n'est emis et le
     * {@code Mono} est VIDE. Un {@code flatMap} en aval n'est alors jamais
     * appele, et la branche d'erreur est sautee.
     *
     * <p>Mesure du 2026-11-14 sur le service en marche : la reservation rendait
     * « 200 [] » sans rien journaliser, et l'appelant croyait avoir reserve.
     * {@code RequetesModifiantesAnnoteesTest} refuse desormais toute requete
     * UPDATE, INSERT ou DELETE sans cette annotation.
     *
     * @return le nombre de lignes modifiees : 1 si la transition a eu lieu,
     *         0 si l'etat attendu n'etait plus celui de la ligne
     */
    @Modifying
    @Query("""
            UPDATE commande
               SET statut = :vers, updated_at = CURRENT_TIMESTAMP
             WHERE id = :id AND statut = :attendu
            """)
    Mono<Integer> changerStatut(UUID id, StatutCommande attendu, StatutCommande vers);

    /** Les commandes impayees dont l'echeance est passee. Le stock doit etre rendu. */
    @Query("""
            SELECT * FROM commande
             WHERE statut = 'A_PAYER' AND a_payer_jusqu_au < :maintenant
             ORDER BY a_payer_jusqu_au
             LIMIT :plafond
            """)
    Flux<Commande> impayeesEchues(LocalDateTime maintenant, int plafond);

    /** Les commandes livrees dont la fenetre de retour est passee. A cloturer. */
    @Query("""
            SELECT * FROM commande
             WHERE statut = 'LIVREE' AND retour_possible_jusqu_au < :maintenant
             ORDER BY retour_possible_jusqu_au
             LIMIT :plafond
            """)
    Flux<Commande> aCloturer(LocalDateTime maintenant, int plafond);

    @Query("SELECT COUNT(*) FROM commande WHERE statut = :statut")
    Mono<Long> compterParStatut(StatutCommande statut);
}
