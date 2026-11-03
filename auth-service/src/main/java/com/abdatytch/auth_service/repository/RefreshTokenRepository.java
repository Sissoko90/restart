package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.RefreshToken;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface RefreshTokenRepository extends ReactiveCrudRepository<RefreshToken, String> {

    /**
     * Recherche par EMPREINTE, et non par le jeton presente.
     *
     * <p>La colonne ne porte plus le secret : {@code findByToken} ne pouvait
     * donc plus exister sans mentir sur ce qu'elle compare. L'appelant calcule
     * l'empreinte du jeton recu — {@code EmpreinteDeSecret.empreinte} — et la
     * passe ici.
     *
     * <p>La recherche reste <b>indexee</b>, ce qui est la raison pour laquelle
     * l'empreinte est deterministe : une fonction a sel obligerait a parcourir
     * la table en comparant ligne par ligne, a chaque rafraichissement.
     */
    Mono<RefreshToken> findByEmpreinte(String empreinte);

    // R2DBC ne gère pas les relations : on interroge par clé étrangère,
    // là où l'API JPA exposait findByUser(User).
    Flux<RefreshToken> findByUserId(String userId);

    Flux<RefreshToken> findByUserIdAndIsRevokedFalse(String userId);

    Mono<Void> deleteByExpiresAtBefore(LocalDateTime dateTime);

    Mono<Void> deleteByUserId(String userId);
}
