package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import reactor.core.publisher.Mono;

/**
 * Gestion des refresh tokens.
 */
public interface IRefreshTokenService {

    Mono<RefreshToken> createRefreshToken(User user, String ipAddress, String userAgent);

    /**
     * Retourne le token s'il existe, n'est pas révoqué et n'est pas expiré.
     * Émet une {@code AuthException} sinon.
     */
    Mono<RefreshToken> validateAndGetRefreshToken(String token);

    Mono<Void> revokeToken(String token);

    Mono<Void> revokeAllUserTokens(User user);

    Mono<Void> cleanupExpiredTokens();
}
