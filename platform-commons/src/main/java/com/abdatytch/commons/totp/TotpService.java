package com.abdatytch.commons.totp;

import reactor.core.publisher.Mono;

/**
 * Service pour la gestion de l'authentification TOTP (Time-based One-Time Password)
 * Utilisé pour l'authentification à deux facteurs des administrateurs
 */
public interface TotpService {

    /**
     * Génère un nouveau secret TOTP pour un utilisateur
     * @return Le secret TOTP encodé en Base32
     */
    String generateTotpSecret();

    /**
     * Génère l'URL QR Code pour configurer l'application d'authentification
     * @param userEmail Email de l'utilisateur
     * @param totpSecret Secret TOTP
     * @param issuer Nom de l'application (ex: "Abdaty Interface")
     * @return URL au format otpauth://
     */
    String generateQrCodeUrl(String userEmail, String totpSecret, String issuer);

    /**
     * Valide un code TOTP fourni par l'utilisateur
     * @param totpSecret Secret TOTP de l'utilisateur
     * @param totpCode Code à 6 chiffres fourni par l'utilisateur
     * @return true si le code est valide, false sinon
     */
    Mono<Boolean> validateTotpCode(String totpSecret, String totpCode);

    /**
     * Génère un code TOTP pour un secret donné (pour les tests)
     * @param totpSecret Secret TOTP
     * @return Code TOTP à 6 chiffres
     */
    String generateTotpCode(String totpSecret);
}
