package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.dto.request.BiometricAuthInitiateRequest;
import com.abdatytch.auth_service.dto.request.BiometricRegistrationRequest;
import com.abdatytch.auth_service.dto.request.BiometricVerificationRequest;
import com.abdatytch.auth_service.dto.response.BiometricCredentialResponse;
import com.abdatytch.auth_service.dto.response.BiometricRegistrationResponse;
import com.abdatytch.auth_service.dto.response.BiometricVerificationResponse;
import com.abdatytch.auth_service.enums.BiometricType;
import com.abdatytch.auth_service.service.IBiometricAuthService;
import com.abdatytch.auth_service.util.AppelantAuthentifie;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Authentification biometrique (WebAuthn/FIDO2 : Face ID, empreinte, Windows Hello).
 *
 * <h2>Ce que ce controleur ne fait plus</h2>
 *
 * Il injectait {@code IJwtService} et {@code UserRepository}, et portait trois
 * choses qui n'etaient pas de son ressort :
 *
 * <ol>
 *   <li><b>un second decodage du jeton</b> — {@code extractUserIdFromToken}
 *       relisait l'en-tete {@code Authorization} et redemandait l'identifiant a
 *       {@code IJwtService}, alors que {@code JwtAuthenticationWebFilter} vient
 *       de le faire et a pose l'identite dans le contexte de securite. Deux
 *       sources pour une meme identite, et la seconde levait une
 *       {@code IllegalArgumentException} — donc <b>400</b> la ou l'absence de
 *       jeton doit rendre 401 ;</li>
 *   <li><b>la resolution d'un identifiant</b> — troisieme copie de la regle dans
 *       le depot, et la seule a interroger le telephone puis l'adresse en repli
 *       plutot qu'a trancher sur l'arobase. Une requete de trop a chaque appel ;</li>
 *   <li><b>la traduction de l'entite en DTO</b> — l'entite porte
 *       {@code publicKey}, {@code aaguid} et {@code counter}. Le controleur les
 *       retirait correctement, mais rien ne l'y obligeait : c'est le service qui
 *       doit rendre un DTO.</li>
 * </ol>
 *
 * <h2>Pourquoi authenticate/** est public et register/** non</h2>
 *
 * La biometrie est une <b>methode de connexion</b> : exiger un jeton pour
 * l'emprunter demandait d'etre deja authentifie pour s'authentifier, ce qui
 * rendait le parcours entierement inutilisable. {@code register/**} garde le
 * jeton : on y ajoute un authentificateur a un compte existant, ce qui suppose
 * legitimement une session.
 *
 * <h2>Ce qui n'est PAS resolu, et qu'il faut savoir</h2>
 *
 * Un identifiant inconnu et un compte sans authentificateur reçoivent la meme
 * erreur. Mais un compte qui <b>a</b> des authentificateurs reçoit un defi :
 * l'existence d'une inscription biometrique reste donc observable. La fermer
 * demanderait d'emettre un defi plausible pour un compte inconnu, ce qui n'est
 * pas fait ici.
 */
@Slf4j
@RestController
@RequestMapping("/auth/biometric")
@RequiredArgsConstructor
public class BiometricAuthController {

    private final IBiometricAuthService biometricAuthService;

    @PostMapping("/register/initiate")
    public Mono<ResponseEntity<BiometricRegistrationResponse>> initiateRegistration(
            @RequestParam BiometricType type) {
        log.info("Initiation de l'enregistrement biometrique {}", type);

        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> biometricAuthService.initiateRegistration(userId, type))
                .map(ResponseEntity::ok);
    }

    @PostMapping("/register/complete")
    public Mono<ResponseEntity<BiometricRegistrationResponse>> completeRegistration(
            @Valid @RequestBody BiometricRegistrationRequest request) {
        log.info("Finalisation de l'enregistrement biometrique {}", request.getType());

        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> biometricAuthService.completeRegistration(userId, request))
                .map(ResponseEntity::ok);
    }

    /**
     * Debut d'une authentification biometrique, pour un appelant <b>non encore
     * connecte</b>.
     */
    @PostMapping("/authenticate/initiate")
    public Mono<ResponseEntity<BiometricVerificationResponse>> initiateAuthentication(
            @Valid @RequestBody BiometricAuthInitiateRequest request) {
        log.info("Initiation de l'authentification biometrique");

        return biometricAuthService
                .initiateAuthenticationParIdentifiant(request.getIdentifier())
                .map(ResponseEntity::ok);
    }

    @PostMapping("/authenticate/verify")
    public Mono<ResponseEntity<BiometricVerificationResponse>> verifyAuthentication(
            @Valid @RequestBody BiometricVerificationRequest request) {
        log.info("Verification de l'authentification biometrique");

        return biometricAuthService.verifyAuthentication(request).map(ResponseEntity::ok);
    }

    @GetMapping("/credentials")
    public Mono<ResponseEntity<List<BiometricCredentialResponse>>> getUserCredentials() {
        log.info("Recuperation des authentificateurs biometriques de l'appelant");

        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> biometricAuthService.getUserCredentials(userId).collectList())
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/credentials/{credentialId}")
    public Mono<ResponseEntity<Void>> deleteCredential(@PathVariable String credentialId) {
        log.info("Suppression de l'authentificateur biometrique {}", credentialId);

        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> biometricAuthService.deleteCredential(userId, credentialId))
                .thenReturn(ResponseEntity.ok().<Void>build());
    }

    @DeleteMapping("/credentials")
    public Mono<ResponseEntity<Void>> deleteAllCredentials() {
        log.info("Suppression de tous les authentificateurs biometriques de l'appelant");

        return AppelantAuthentifie.identifiant()
                .flatMap(biometricAuthService::deleteAllUserCredentials)
                .thenReturn(ResponseEntity.ok().<Void>build());
    }

    @GetMapping("/has-credentials")
    public Mono<ResponseEntity<Boolean>> hasActiveCredentials() {
        log.info("Verification de l'existence d'authentificateurs biometriques actifs");

        return AppelantAuthentifie.identifiant()
                .flatMap(biometricAuthService::hasActiveCredentials)
                .map(ResponseEntity::ok);
    }
}
