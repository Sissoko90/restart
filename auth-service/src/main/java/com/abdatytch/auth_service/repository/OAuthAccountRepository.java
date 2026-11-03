package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.OAuthAccount;
import com.abdatytch.auth_service.enums.OAuthProvider;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * L'identifiant est un String (UUID textuel), comme pour toutes les entités.
 * L'ancienne déclaration (identifiant UUID) était incohérente avec l'entité.
 */
@Repository
public interface OAuthAccountRepository extends ReactiveCrudRepository<OAuthAccount, String> {

    Mono<OAuthAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    Flux<OAuthAccount> findByUserId(String userId);

    Mono<Boolean> existsByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    Mono<OAuthAccount> findByProviderAndEmail(OAuthProvider provider, String email);
}
