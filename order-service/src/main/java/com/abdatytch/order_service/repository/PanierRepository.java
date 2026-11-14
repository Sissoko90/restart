package com.abdatytch.order_service.repository;

import com.abdatytch.order_service.entities.Panier;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface PanierRepository extends ReactiveCrudRepository<Panier, UUID> {

    /**
     * Le panier ouvert d'un acheteur, s'il en a un.
     *
     * <p>Rend au plus une ligne : l'index unique partiel
     * {@code panier_un_seul_ouvert_par_acheteur} le garantit en base, et c'est
     * la seule garantie qui tienne sous concurrence. Un simple
     * {@code findFirst} en Java laisserait deux onglets creer deux paniers.
     */
    @Query("SELECT * FROM panier WHERE auth_user_id = :acheteur AND statut = 'OUVERT'")
    Mono<Panier> ouvertDe(String acheteur);
}
