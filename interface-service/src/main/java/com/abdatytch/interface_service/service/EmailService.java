package com.abdatytch.interface_service.service;

import reactor.core.publisher.Mono;

/**
 * Service d'envoi d'emails
 */
public interface EmailService {

    /**
     * Envoie un email de vérification
     */
    Mono<Void> sendVerificationEmail(String email, String token, String firstName);

    /**
     * Envoie un email de réinitialisation de mot de passe
     */
    Mono<Void> sendPasswordResetEmail(String email, String token, String firstName);

    /**
     * Envoie un code OTP par email
     */
    Mono<Void> sendEmailOtp(String email, String otpCode, String firstName);

    /**
     * Envoie un code OTP de réinitialisation de mot de passe par email
     */
    Mono<Void> sendPasswordResetOtp(String email, String otpCode, String firstName);

    /**
     * Envoie une alerte admin par email en cas de tentative incorrecte
     */
    Mono<Void> sendAdminAlertEmail(String adminEmail, String subject, String message);

    /**
     * Envoie une alerte critique par email (dernière couche de sécurité)
     */
    Mono<Void> sendCriticalAlertEmail(String criticalEmail, String subject, String message);
}
