package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.BiometricRegistrationRequest;
import com.abdatytch.auth_service.dto.request.BiometricVerificationRequest;
import com.abdatytch.auth_service.dto.response.BiometricCredentialResponse;
import com.abdatytch.auth_service.dto.response.BiometricRegistrationResponse;
import com.abdatytch.auth_service.dto.response.BiometricVerificationResponse;
import com.abdatytch.auth_service.enums.BiometricType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Authentification biométrique (WebAuthn/FIDO2).
 * Seules des clés publiques sont stockées, jamais de données biométriques brutes.
 */
public interface IBiometricAuthService {

    Mono<BiometricRegistrationResponse> initiateRegistration(String userId, BiometricType type);

    /**
     * Finalise l'enregistrement et rend la <b>reponse</b>, et non l'entite.
     *
     * <p>Elle rendait {@code BiometricCredential}, que le controleur traduisait
     * en DTO. L'entite porte {@code publicKey}, {@code aaguid} et
     * {@code counter} : la laisser franchir la frontiere du service, c'est
     * confier a chaque appelant le soin de ne pas les divulguer. Un seul
     * appelant l'a fait correctement ; le suivant n'avait aucune raison de le
     * savoir.
     */
    Mono<BiometricRegistrationResponse> completeRegistration(
            String userId, BiometricRegistrationRequest request);

    Mono<BiometricVerificationResponse> initiateAuthentication(String userId);

    /**
     * Debut d'authentification pour un appelant <b>non encore connecte</b>, qui
     * se designe par un telephone ou une adresse.
     *
     * <p>La resolution de l'identifiant etait faite dans le controleur, qui
     * injectait {@code UserRepository} pour cela — et elle etait la
     * <b>troisieme</b> copie de la regle, avec un comportement different des
     * deux autres : elle interrogeait le telephone puis l'adresse en repli, au
     * lieu de trancher sur la presence d'une arobase. Une requete de trop a
     * chaque appel, et une regle de plus a maintenir d'accord.
     *
     * <p>Un identifiant inconnu et un compte sans authentificateur reçoivent la
     * <b>meme</b> erreur : des reponses distinctes enumereraient les comptes sur
     * un endpoint public.
     */
    Mono<BiometricVerificationResponse> initiateAuthenticationParIdentifiant(String identifiant);

    Mono<BiometricVerificationResponse> verifyAuthentication(BiometricVerificationRequest request);

    /** Les authentificateurs de l'appelant, en DTO : voir {@link #completeRegistration}. */
    Flux<BiometricCredentialResponse> getUserCredentials(String userId);

    Mono<Void> deleteCredential(String userId, String credentialId);

    Mono<Void> deleteAllUserCredentials(String userId);

    Mono<Boolean> hasActiveCredentials(String userId);
}
