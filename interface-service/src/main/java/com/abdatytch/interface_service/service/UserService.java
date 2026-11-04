package com.abdatytch.interface_service.service;

import com.abdatytch.interface_service.dto.request.LoginRequest;
import com.abdatytch.interface_service.dto.request.RegisterRequest;
import com.abdatytch.interface_service.dto.request.ResetPasswordWithOtpRequest;
import com.abdatytch.interface_service.dto.request.VerifyEmailRequest;
import com.abdatytch.interface_service.dto.request.VerifyPhoneRequest;
import com.abdatytch.interface_service.dto.response.UserResponse;
import com.abdatytch.interface_service.entity.User;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Interface du service utilisateur
 * Interface-service gère uniquement les utilisateurs
 */
public interface UserService {

    /**
     * Inscrit un nouvel utilisateur
     */
    Mono<UserResponse> register(RegisterRequest request);

    /**
     * Authentifie un utilisateur
     */
    Mono<Map<String, Object>> login(LoginRequest request);

    /**
     * Trouve un utilisateur par ID
     */
    Mono<UserResponse> findById(UUID userId);

    /**
     * Trouve un utilisateur par email
     */
    Mono<User> findByEmail(String email);

    /**
     * Met à jour le profil utilisateur
     */
    Mono<UserResponse> updateProfile(UUID userId, UserResponse userUpdate);

    /**
     * Vérifie l'email d'un utilisateur avec code OTP
     */
    Mono<Void> verifyEmail(VerifyEmailRequest request);

    /**
     * Vérifie le téléphone d'un utilisateur avec code OTP
     */
    Mono<Void> verifyPhone(VerifyPhoneRequest request);

    /**
     * Demande un nouveau code OTP pour l'email
     */
    Mono<Void> requestEmailOtp(String email);

    /**
     * Demande un nouveau code OTP pour le téléphone
     */
    Mono<Void> requestPhoneOtp(String phoneNumber);

    /**
     * Demande une réinitialisation de mot de passe
     */
    Mono<Void> requestPasswordReset(String email);

    /**
     * Réinitialise le mot de passe
     */
    Mono<Void> resetPassword(String token, String newPassword);

    /**
     * Réinitialise le mot de passe avec OTP
     */
    Mono<Void> resetPasswordWithOtp(ResetPasswordWithOtpRequest request);

    /**
     * Met à jour la dernière connexion
     */
    Mono<Void> updateLastLogin(UUID userId);

    /**
     * Trouve un utilisateur par son jeton de rafraichissement.
     *
     * <p>Le jeton est transforme en EMPREINTE avant la recherche : la base n'en
     * detient que l'empreinte depuis la migration V9.
     */
    Mono<UserResponse> findByRefreshToken(String refreshToken);

    /**
     * Echange un jeton de rafraichissement contre une nouvelle paire.
     *
     * <p>La frappe des jetons vit ICI et non dans le controleur, qui signait
     * les siens en HMAC256 avec un secret symetrique — illisibles par les huit
     * services, qui valident du RS256 par JWKS. La connexion emettait deja ses
     * jetons par cette voie ; le rafraichissement la rejoint.
     *
     * <p>Rend la meme forme que {@code login} : {@code accessToken},
     * {@code refreshToken}, {@code expiresIn}, {@code tokenType}.
     *
     * @throws com.abdatytch.interface_service.exception.BusinessException si le
     *         jeton est inconnu, perime, ou si le compte est inactif
     */
    Mono<java.util.Map<String, Object>> refreshTokens(String refreshToken);

    /**
     * Met à jour le refresh token d'un utilisateur
     */
    Mono<Void> updateRefreshToken(UUID userId, String refreshToken);

    /**
     * Invalide un refresh token
     */
    Mono<Void> invalidateRefreshToken(String refreshToken);
}
