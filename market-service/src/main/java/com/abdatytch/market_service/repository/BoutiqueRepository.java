package com.abdatytch.market_service.repository;

import com.abdatytch.market_service.entities.Boutique;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Les boutiques et leur file de moderation. */
@Repository
public interface BoutiqueRepository extends ReactiveCrudRepository<Boutique, UUID> {

    /**
     * La boutique d'un compte, s'il en a une.
     *
     * <p>C'est l'appel le plus chaud du service : il est fait une fois par
     * requete pour calculer la portee du catalogue. Son resultat est donc mis
     * en cache — voir {@code CacheDeBoutique}.
     */
    Mono<Boutique> findByAuthUserId(String authUserId);

    /** La vitrine publique d'une boutique approuvee. */
    @Query("SELECT * FROM boutique WHERE id = :id AND statut = 'APPROUVEE'")
    Mono<Boutique> parIdentifiantSiApprouvee(UUID id);

    @Query("SELECT * FROM boutique WHERE statut = 'EN_ATTENTE' "
            + "ORDER BY created_at LIMIT :limite OFFSET :decalage")
    Flux<Boutique> aModerer(int limite, int decalage);

    /**
     * Les boutiques declarant le meme RCCM.
     *
     * <p>Le RCCM n'est pas unique en base : la contrainte appartient a
     * l'organisme qui le delivre, et l'imposer ici refuserait une saisie
     * legitime sur la faute de frappe d'autrui. C'est la MODERATION qui tranche,
     * et cette requete est ce qu'elle regarde.
     */
    @Query("SELECT * FROM boutique WHERE rccm = :rccm AND id <> :exclue")
    Flux<Boutique> memeRccm(String rccm, UUID exclue);

    /** Le nombre de boutiques en attente, pour la metrique de file. */
    @Query("SELECT COUNT(*) FROM boutique WHERE statut = 'EN_ATTENTE'")
    Mono<Long> compterAModerer();
}
