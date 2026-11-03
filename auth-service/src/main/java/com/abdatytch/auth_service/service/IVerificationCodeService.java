package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.entities.VerificationCode;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import reactor.core.publisher.Mono;

/**
 * Codes de vérification à usage unique (OTP), par SMS ou email.
 */
public interface IVerificationCodeService {

    Mono<VerificationCode> generateAndSendCode(String phoneNumber, String email, VerificationCodeType type);

    Mono<VerificationCode> generateAndSendCode(String phoneNumber, String email, VerificationCodeType type,
                                               String firstName, String lastName, String countryCode);

    /**
     * Valide un code par téléphone et le marque comme utilisé.
     * Émet une {@code AuthException} si le code est inconnu, déjà utilisé,
     * expiré, ou si le nombre de tentatives est dépassé.
     */
    Mono<Void> validateCode(String phoneNumber, String code, VerificationCodeType type);

    /** Idem, par adresse email. */
    Mono<Void> validateCodeByEmail(String email, String code, VerificationCodeType type);

    Mono<Void> cleanupExpiredCodes();

    Mono<Void> incrementAttempts(String phoneNumber, VerificationCodeType type);

    /**
     * Dernier code émis pour ce couple téléphone/type.
     * Mono vide si aucun code n'existe (l'absence n'est pas une erreur).
     */
    Mono<VerificationCode> getLatestCode(String phoneNumber, VerificationCodeType type);
}
