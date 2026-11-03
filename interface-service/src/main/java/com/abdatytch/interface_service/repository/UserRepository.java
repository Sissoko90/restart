package com.abdatytch.interface_service.repository;

import com.abdatytch.interface_service.entity.User;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository pour les utilisateurs
 * Interface-service gère uniquement les utilisateurs et projets de base
 */
@Repository
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    /**
     * Trouve un utilisateur par email
     */
    @Query("SELECT * FROM users WHERE email = :email AND is_active = true")
    Mono<User> findByEmail(String email);

    /**
     * Trouve un utilisateur par username
     */
    @Query("SELECT * FROM users WHERE username = :username AND is_active = true")
    Mono<User> findByUsername(String username);

    /**
     * Trouve un utilisateur par numéro de téléphone
     */
    @Query("SELECT * FROM users WHERE phone_number = :phoneNumber AND is_active = true")
    Mono<User> findByPhoneNumber(String phoneNumber);

    /**
     * Trouve un utilisateur par email ou username
     */
    @Query("SELECT * FROM users WHERE (email = :identifier OR username = :identifier) AND is_active = true")
    Mono<User> findByEmailOrUsername(String identifier);

    /**
     * Vérifie si un email existe déjà
     */
    @Query("SELECT COUNT(*) > 0 FROM users WHERE email = :email AND is_active = true")
    Mono<Boolean> existsByEmail(String email);

    /**
     * Vérifie si un username existe déjà
     */
    @Query("SELECT COUNT(*) > 0 FROM users WHERE username = :username AND is_active = true")
    Mono<Boolean> existsByUsername(String username);

    /**
     * Trouve un utilisateur par token de réinitialisation de mot de passe
     */
    @Query("SELECT * FROM users WHERE password_reset_token = :token AND password_reset_expires_at > NOW() AND is_active = true")
    Mono<User> findByPasswordResetToken(String token);

    /**
     * Trouve un utilisateur par token de vérification d'email
     */
    @Query("SELECT * FROM users WHERE email_verification_token = :token AND is_active = true")
    Mono<User> findByEmailVerificationToken(String token);

    /**
     * Trouve un utilisateur par refresh token
     */
    @Query("SELECT * FROM users WHERE refresh_token = :refreshToken AND refresh_token_expires_at > NOW() AND is_active = true")
    Mono<User> findByRefreshToken(String refreshToken);

    /**
     * Trouve un utilisateur par token de confirmation admin
     */
    @Query("SELECT * FROM users WHERE admin_confirmation_token = :token AND admin_confirmation_expires_at > NOW() AND is_active = true")
    Mono<User> findByAdminConfirmationToken(String token);

    /**
     * Trouve un utilisateur par token de maintenance critique
     */
    @Query("SELECT * FROM users WHERE critical_maintenance_token = :token AND critical_maintenance_expires_at > NOW() AND is_active = true")
    Mono<User> findByCriticalMaintenanceToken(String token);
}
