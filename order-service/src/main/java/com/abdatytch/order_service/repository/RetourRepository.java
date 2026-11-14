package com.abdatytch.order_service.repository;

import com.abdatytch.order_service.entities.Retour;
import com.abdatytch.order_service.enums.StatutRetour;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface RetourRepository extends ReactiveCrudRepository<Retour, UUID> {

    Flux<Retour> findByCommandeIdOrderByCreatedAtDesc(UUID commandeId);

    /**
     * Le retour ouvert d'une commande, s'il y en a un.
     *
     * <p>Au plus un : l'index unique partiel
     * {@code retour_un_seul_ouvert_par_commande} le garantit. Sans lui, un
     * acheteur ouvre dix demandes sur la meme commande et noie la file
     * d'arbitrage de Sankadi.
     */
    @Query("""
            SELECT * FROM retour
             WHERE commande_id = :commandeId
               AND statut IN ('DEMANDE', 'ACCEPTE', 'RECU')
            """)
    Mono<Retour> ouvertDe(UUID commandeId);

    /** Meme verrou optimiste que pour la commande. Voir CommandeRepository. */
        /**
    /**
     * {@code @Modifying} N'EST PAS DECORATIF.
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
     */
    @Modifying
    @Query("""
            UPDATE retour
               SET statut = :vers, updated_at = CURRENT_TIMESTAMP
             WHERE id = :id AND statut = :attendu
            """)
    Mono<Integer> changerStatut(UUID id, StatutRetour attendu, StatutRetour vers);

    @Query("SELECT COUNT(*) FROM retour WHERE statut = 'DEMANDE'")
    Mono<Long> compterEnAttenteDArbitrage();
}
