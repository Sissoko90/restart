package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.BiometricVerificationRequest;
import com.abdatytch.auth_service.entities.BiometricCredential;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.BiometricCredentialRepository;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.security.webauthn.WebAuthnVerifier;
import com.abdatytch.auth_service.service.impl.BiometricAuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Interrupteur d'exploitation du parcours biometrique.
 *
 * <h2>Ce que ce test couvre, et ce qu'il ne couvre plus</h2>
 *
 * Ce fichier demontrait auparavant l'usurpation permise par l'ancienne
 * verification — un {@code Base64} de concatenation que tout appelant
 * connaissant la cle publique pouvait reproduire. Cette demonstration a ete
 * <b>retiree parce que la faille est corrigee</b> : la verification passe
 * desormais par {@code webauthn4j}, et
 * {@code WebAuthnVerifierTest.signatureForgeeRefusee} verifie precisement que
 * l'ancienne attaque echoue, avec un authentificateur emule.
 *
 * <p>Ce qui reste ici est l'interrupteur lui-meme : quand le parcours est coupe
 * par configuration, il doit refuser <b>avant</b> toute lecture en base — sans
 * quoi un appelant apprendrait quels {@code credentialId} existent en comparant
 * les temps de reponse ou les messages.
 */
@DisplayName("Parcours biometrique - interrupteur d'exploitation")
class BiometricAuthVerificationTest {

    private static final String ID_CREDENTIAL = "credential-1";

    private BiometricCredentialRepository credentialRepository;
    private UserRepository userRepository;
    private IJwtService jwtService;
    private WebAuthnVerifier webAuthnVerifier;
    private IRefreshTokenService refreshTokenService;
    private BiometricAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        credentialRepository = Mockito.mock(BiometricCredentialRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        jwtService = Mockito.mock(IJwtService.class);
        webAuthnVerifier = Mockito.mock(WebAuthnVerifier.class);
        refreshTokenService = Mockito.mock(IRefreshTokenService.class);

        service = new BiometricAuthServiceImpl(
                credentialRepository, userRepository, jwtService, null,
                webAuthnVerifier, refreshTokenService);
    }

    private void activer(boolean actif) {
        ReflectionTestUtils.setField(service, "authentificationActivee", actif);
    }

    private BiometricVerificationRequest requete() {
        BiometricVerificationRequest r = new BiometricVerificationRequest();
        r.setCredentialId(ID_CREDENTIAL);
        r.setAuthenticatorData("donnees");
        r.setClientDataJSON("{}");
        r.setChallenge("challenge");
        r.setSignature("signature");
        return r;
    }

    // ==================================================================
    @Nested
    @DisplayName("Parcours coupe")
    class Coupe {

        @Test
        @DisplayName("le refus precede toute lecture en base")
        void refusAvantAccesBase() {
            // L'ordre compte : refuser apres avoir cherche la credential
            // apprendrait a l'appelant si l'identifiant existe.
            activer(false);

            StepVerifier.create(service.verifyAuthentication(requete()))
                    .expectError(AuthException.class)
                    .verify();

            verify(credentialRepository, never()).findByCredentialId(anyString());
            verify(webAuthnVerifier, never()).verifierAssertion(
                    anyString(), anyLong(), anyString(), any(),
                    anyString(), anyString(), anyString(), anyString());
            verify(jwtService, never()).generateToken(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Parcours actif")
    class Actif {

        @Test
        @DisplayName("une credential inconnue et une credential desactivee donnent le MEME refus")
        void refusUniforme() {
            // L'ancienne version distinguait « credential non trouvee » de
            // « credential desactivee » : un oracle qui apprend quels
            // identifiants existent. Les deux rendent maintenant le meme
            // message.
            activer(true);

            when(credentialRepository.findByCredentialId(ID_CREDENTIAL))
                    .thenReturn(Mono.empty());

            String messageInconnue = messageDuRefus();

            BiometricCredential desactivee = new BiometricCredential();
            desactivee.setCredentialId(ID_CREDENTIAL);
            desactivee.setIsActive(false);
            when(credentialRepository.findByCredentialId(ID_CREDENTIAL))
                    .thenReturn(Mono.just(desactivee));

            String messageDesactivee = messageDuRefus();

            org.assertj.core.api.Assertions.assertThat(messageInconnue)
                    .withFailMessage("les deux refus different : l'appelant apprend "
                            + "si le credentialId existe")
                    .isEqualTo(messageDesactivee);
        }

        private String messageDuRefus() {
            try {
                service.verifyAuthentication(requete()).block();
                throw new AssertionError("aucun refus");
            } catch (AuthException e) {
                return e.getMessage();
            }
        }
    }
}
