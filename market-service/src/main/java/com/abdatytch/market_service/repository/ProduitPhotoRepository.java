package com.abdatytch.market_service.repository;

import com.abdatytch.market_service.entities.ProduitPhoto;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Les photos d'un produit, par reference a media-service. */
@Repository
public interface ProduitPhotoRepository extends ReactiveCrudRepository<ProduitPhoto, UUID> {

    Flux<ProduitPhoto> findByProduitIdOrderByPositionAsc(UUID produitId);

    Mono<Void> deleteByProduitId(UUID produitId);

    /**
     * Retire la marque principale de toutes les photos d'un produit.
     *
     * <p>A appeler AVANT de poser la nouvelle : l'index unique partiel
     * refuserait sinon la seconde, et le message parlerait d'une contrainte
     * au lieu de l'intention.
     */
    @Query("UPDATE produit_photo SET principale = FALSE WHERE produit_id = :produitId")
    Mono<Void> retirerLaPrincipale(UUID produitId);
}
