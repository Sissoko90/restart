package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.CodeDeSecours;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface CodeDeSecoursRepository extends ReactiveCrudRepository<CodeDeSecours, String> {

    /**
     * Codes encore utilisables du compte.
     *
     * <p>C'est la requete du chemin de connexion, servie par l'index partiel
     * {@code idx_code_secours_utilisables}. Sans le predicat, elle parcourrait
     * tous les lots regeneres depuis la creation du compte.
     */
    Flux<CodeDeSecours> findByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(String userId);

    /**
     * Tous les codes du compte, consommes et invalides compris.
     *
     * <p>Sert a l'affichage — la personne doit voir lesquels ont servi — et a
     * l'effacement RGPD.
     */
    Flux<CodeDeSecours> findByUserId(String userId);

    Mono<Long> countByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(String userId);
}
