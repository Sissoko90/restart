package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.AppareilDeConfiance;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface AppareilDeConfianceRepository
        extends ReactiveCrudRepository<AppareilDeConfiance, String> {

    /**
     * Recherche par empreinte : la requete du chemin de connexion.
     *
     * <p>Par empreinte et non par identifiant, parce que le client presente un
     * jeton, pas un identifiant. L'empreinte est unique en base : deux lignes de
     * meme empreinte signifieraient qu'un meme jeton ouvre deux appareils, et la
     * recherche en rendrait une au hasard.
     *
     * <p>Les appareils revoques sont <b>inclus</b> : la revocation se juge en
     * Java, par {@code estDeConfiance}, avec l'echeance. Les filtrer en SQL
     * ferait dependre le resultat de l'horloge du serveur de base plutot que de
     * celle du service.
     */
    Mono<AppareilDeConfiance> findByEmpreinteJeton(String empreinteJeton);

    /** Appareils non revoques du compte, echus compris. */
    Flux<AppareilDeConfiance> findByUserIdAndRevoqueLeIsNull(String userId);

    /** Tous les appareils du compte, pour l'effacement RGPD. */
    Flux<AppareilDeConfiance> findByUserId(String userId);
}
