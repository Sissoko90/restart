package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.*;
import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.dto.response.VerificationResponse;
import com.abdatytch.auth_service.entities.User;
import reactor.core.publisher.Mono;

/**
 * Authentification : connexion, inscription, réinitialisation, profil.
 *
 * <p>Toutes les opérations sont réactives : la persistance (R2DBC), la signature
 * du token (interface-service) et la géolocalisation (gateway) sont non bloquantes.
 * Les seuls appels bloquants restants (SMS Twilio, SMTP) sont isolés sur
 * {@code Schedulers.boundedElastic()} dans leurs services respectifs.
 */
public interface IAuthService {

    Mono<AuthResponse> login(LoginRequest request, String ipAddress, String userAgent);

    Mono<Void> logout(String refreshToken, String userId, String ipAddress, String userAgent);

    Mono<AuthResponse> refreshToken(String refreshToken, String ipAddress, String userAgent);

    Mono<VerificationResponse> initiateForgotPassword(String phoneNumber);

    Mono<Void> resetPassword(ResetPasswordRequest request,
                             com.abdatytch.auth_service.dto.ContexteDeSecurite contexte);

    /**
     * Change le mot de passe d'une personne <b>deja connectee</b>.
     *
     * <h2>Pourquoi ce parcours manquait, et pourquoi il compte</h2>
     *
     * Seul {@link #resetPassword} existait, et il passe par un code envoye par
     * SMS. Une personne connectee qui connait son mot de passe devait donc
     * reclamer un SMS pour en changer : un cout d'envoi, une dependance au
     * fournisseur SMS, et un parcours qui echoue si son numero a change. Le
     * motif habituel — la preuve de l'ancien mot de passe — n'etait nulle part.
     *
     * <h2>L'ancien mot de passe est exige, et ce n'est pas une formalite</h2>
     *
     * L'appelant est deja authentifie. S'en contenter rendrait un jeton d'acces
     * vole suffisant pour <b>prendre</b> le compte : l'attaquant changerait le
     * mot de passe et la personne ne pourrait plus entrer. Exiger l'ancien
     * limite le vol d'un jeton a la duree de ce jeton.
     *
     * <p>Toutes les sessions sont fermees ensuite, celle de l'appelant comprise,
     * comme le fait deja {@link #resetPassword}. C'est le point : si le
     * changement est motive par un soupçon, laisser vivre les autres sessions
     * annulerait l'operation.
     */
    Mono<Void> changerMotDePasse(String userId, ChangePasswordRequest request,
                                 com.abdatytch.auth_service.dto.ContexteDeSecurite contexte);

    /**
     * Renvoie le code d'inscription, par le canal de l'inscription.
     *
     * <h2>Ce que faisait {@code /auth/resend-code}</h2>
     *
     * Il appelait {@code initiatePhoneRegister}, <b>toujours</b>, et prenait un
     * DTO qui n'accepte qu'un numero. L'endpoint portait un nom generique et ne
     * servait qu'un seul parcours : qui s'etait inscrit par adresse n'avait
     * aucun moyen de se faire renvoyer son code, et recommencer butait sur
     * {@code EMAIL_ALREADY_EXISTS} puisque l'adresse etait desormais connue.
     *
     * <h2>L'aiguillage est ici et non dans le controleur</h2>
     *
     * Choisir entre deux parcours d'inscription est une regle metier. Dans le
     * controleur, elle aurait rejoint les logiques melangees que ce depot a
     * justement sorties des controleurs.
     */
    Mono<VerificationResponse> renvoyerLeCode(ResendCodeRequest request);

    Mono<AuthResponse> verifyPhoneAuth(PhoneAuthVerifyRequest request, String ipAddress, String userAgent);

    Mono<AuthResponse> verifyEmailAuth(EmailAuthVerifyRequest request, String ipAddress, String userAgent);

    Mono<AuthResponse> getUserProfile(String userId);

    Mono<User> updateProfile(String userId, UpdateProfileRequest request,
                             com.abdatytch.auth_service.dto.ContexteDeSecurite contexte);

    Mono<VerificationResponse> initiatePhoneAuth(PhoneAuthRequest request);

    Mono<VerificationResponse> initiatePhoneRegister(PhoneAuthRequest request);

    Mono<AuthResponse> verifyPhoneRegister(PhoneRegisterVerifyRequest request, String ipAddress, String userAgent);

    Mono<VerificationResponse> initiateEmailAuth(EmailAuthRequest request);

    Mono<VerificationResponse> initiateEmailRegister(EmailOtpRequest request);

    Mono<AuthResponse> verifyEmailRegister(EmailRegisterVerifyRequest request, String ipAddress, String userAgent);
}
