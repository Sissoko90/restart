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
     * Trouve un utilisateur par refresh token
     */
    Mono<UserResponse> findByRefreshToken(String refreshToken);

    /**
     * Met à jour le refresh token d'un utilisateur
     */
    Mono<Void> updateRefreshToken(UUID userId, String refreshToken);

    /**
     * Invalide un refresh token
     */
    Mono<Void> invalidateRefreshToken(String refreshToken);
}
