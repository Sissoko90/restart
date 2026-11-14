package com.abdatytch.order_service.repository;

import com.abdatytch.order_service.entities.PanierLigne;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface PanierLigneRepository extends ReactiveCrudRepository<PanierLigne, UUID> {

    Flux<PanierLigne> findByPanierIdOrderByCreatedAtAsc(UUID panierId);

    Mono<PanierLigne> findByPanierIdAndVarianteId(UUID panierId, UUID varianteId);

    Mono<Void> deleteByPanierId(UUID panierId);

    /**
     * Ajoute une quantite a une ligne existante, ou la cree.
     *
     * <p>Ecrit en SQL et non en « lire, modifier, ecrire » : deux ajouts
     * simultanes du meme article liraient la meme quantite et l'un ecraserait
     * l'autre. {@code ON CONFLICT} laisse la base arbitrer, et la contrainte
     * {@code panier_ligne_variante_unique} est ce qui rend l'arbitrage possible.
     */
    @Query("""
            INSERT INTO panier_ligne (panier_id, produit_id, variante_id, quantite,
                                      created_at, updated_at)
            VALUES (:panierId, :produitId, :varianteId, :quantite,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (panier_id, variante_id) DO UPDATE
                SET quantite = panier_ligne.quantite + EXCLUDED.quantite,
                    updated_at = CURRENT_TIMESTAMP
            RETURNING *
            """)
    Mono<PanierLigne> ajouterOuAugmenter(UUID panierId, UUID produitId,
                                         UUID varianteId, int quantite);
}
