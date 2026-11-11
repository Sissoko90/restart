package com.abdatytch.market_service.repository;

import com.abdatytch.market_service.entities.Avis;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Les avis clients, moderes avant publication. */
@Repository
public interface AvisRepository extends ReactiveCrudRepository<Avis, UUID> {

    /** Les avis PUBLIES d'un produit. */
    @Query("SELECT * FROM avis WHERE produit_id = :produitId "
            + "AND statut_moderation = 'APPROUVE' "
            + "ORDER BY created_at DESC LIMIT :limite OFFSET :decalage")
    Flux<Avis> publiesPourProduit(UUID produitId, int limite, int decalage);

    /**
     * L'avis d'une personne sur un produit, quel que soit son etat.
     *
     * <p>Sert a rendre son propre avis a son auteur — y compris en attente ou
     * rejete. Un avis qui disparait apres envoi se reecrit, et la contrainte
     * d'unicite le refuse alors sans expliquer pourquoi.
     */
    Mono<Avis> findByProduitIdAndAuthUserId(UUID produitId, String authUserId);

    @Query("SELECT * FROM avis WHERE statut_moderation = 'EN_ATTENTE' "
            + "ORDER BY created_at LIMIT :limite OFFSET :decalage")
    Flux<Avis> aModerer(int limite, int decalage);

    /** Tous les avis d'une personne : pour l'effacement RGPD. */
    Flux<Avis> findByAuthUserId(String authUserId);
}
