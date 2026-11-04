package com.abdatytch.auth_service.security.webauthn;

import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAttachment;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.authenticator.webauthn.PackedAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verification WebAuthn reelle, exercee avec un authentificateur emule.
 *
 * <h2>Pourquoi ces tests existent sous cette forme</h2>
 *
 * La verification precedente etait un placeholder :
 * {@code Base64(publicKey + authenticatorData + clientDataJSON + challenge)}.
 * Des tests qui n'auraient verifie que des refus l'auraient laissee passer —
 * elle refusait bien les signatures quelconques. Ce qu'il faut demontrer, c'est
 * qu'une <b>vraie</b> assertion est acceptee et qu'une assertion <b>presque</b>
 * valide est refusee.
 *
 * <p>{@code webauthn4j-test} fournit un authentificateur emule qui produit de
 * vraies attestations et assertions, signees par une vraie cle. Les tests
 * ci-dessous passent donc par le meme chemin qu'un navigateur : on ne simule
 * pas la cryptographie, on l'exerce.
 */
@DisplayName("WebAuthnVerifier - verification reelle")
class WebAuthnVerifierTest {

    private static final String RP_ID = "exemple.test";
    private static final String ORIGINE = "https://exemple.test";

    private WebAuthnVerifier verifier;
    private ClientPlatform navigateur;

    @BeforeEach
    void setUp() {
        verifier = new WebAuthnVerifier();
        ReflectionTestUtils.setField(verifier, "rpId", RP_ID);
        ReflectionTestUtils.setField(verifier, "origin", ORIGINE);

        // Un authentificateur NEUF par test. EmulatorUtil.PACKED_AUTHENTICATOR
        // est une instance statique partagee : les credentials enregistrees par
        // un test resteraient visibles des suivants, et un echec dependrait de
        // l'ordre d'execution.
        navigateur = new ClientPlatform(new Origin(ORIGINE),
                new WebAuthnAuthenticatorAdaptor(new PackedAuthenticator()));
    }

    // ==================================================================

    private static Challenge nouveauChallenge() {
        byte[] octets = new byte[32];
        new SecureRandom().nextBytes(octets);
        return new DefaultChallenge(octets);
    }

    /**
     * Criteres de selection de l'authentificateur.
     *
     * <p>L'emulateur les exige non nuls : il lit {@code getResidentKey()} sans
     * controle de nullite. Les valeurs retenues correspondent a celles du
     * verificateur — presence utilisateur requise, verification locale non
     * exigee.
     */
    private static AuthenticatorSelectionCriteria criteres() {
        return new AuthenticatorSelectionCriteria(
                AuthenticatorAttachment.PLATFORM,
                ResidentKeyRequirement.DISCOURAGED,
                UserVerificationRequirement.DISCOURAGED);
    }

    private static String base64url(byte[] valeur) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(valeur);
    }

    /** Enregistre une credential via l'authentificateur emule. */
    private Enregistrement enregistrer(Challenge challenge) {
        PublicKeyCredentialCreationOptions options = new PublicKeyCredentialCreationOptions(
                new PublicKeyCredentialRpEntity(RP_ID, "Restart"),
                new PublicKeyCredentialUserEntity(
                        "utilisateur-1".getBytes(), "utilisateur-1", "Awa Traore"),
                challenge,
                List.of(new PublicKeyCredentialParameters(
                        PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                null, null, criteres(), AttestationConveyancePreference.DIRECT, null);

        PublicKeyCredential<AuthenticatorAttestationResponse, RegistrationExtensionClientOutput> credential =
                navigateur.create(options);

        return new Enregistrement(
                base64url(credential.getAuthenticatorResponse().getAttestationObject()),
                base64url(credential.getAuthenticatorResponse().getClientDataJSON()));
    }

    /**
     * Produit une assertion via l'authentificateur emule.
     *
     * <p>La credential est designee par {@code allowCredentials} : la cle n'est
     * pas decouvrable ({@code ResidentKeyRequirement.DISCOURAGED}), donc
     * l'authentificateur ne peut pas la retrouver seul — il rendrait
     * « No matching authenticator found ».
     */
    private PublicKeyCredential<AuthenticatorAssertionResponse, AuthenticationExtensionClientOutput>
            authentifier(Challenge challenge, String credentialIdBase64Url) {
        // UserVerificationRequirement explicite : l'adaptateur de l'emulateur
        // leve NotImplementedException sur une valeur nulle.
        PublicKeyCredentialRequestOptions options = new PublicKeyCredentialRequestOptions(
                challenge, null, RP_ID,
                List.of(new PublicKeyCredentialDescriptor(
                        PublicKeyCredentialType.PUBLIC_KEY,
                        Base64.getUrlDecoder().decode(credentialIdBase64Url),
                        null)),
                UserVerificationRequirement.PREFERRED, null);
        return navigateur.get(options);
    }

    private record Enregistrement(String attestationObject, String clientDataJSON) {
    }

    // ==================================================================
    @Nested
    @DisplayName("Enregistrement")
    class EnregistrementTest {

        @Test
        @DisplayName("une attestation produite par un authentificateur reel est acceptee")
        void attestationValideAcceptee() {
            Challenge challenge = nouveauChallenge();
            Enregistrement e = enregistrer(challenge);

            var resultat = verifier.verifierEnregistrement(
                    e.attestationObject(), e.clientDataJSON(), base64url(challenge.getValue()));

            // Le credentialId et le materiel de cle viennent de l'attestation
            // verifiee, pas de la requete : c'est tout l'objet du changement.
            assertThat(resultat.credentialId()).isNotBlank();
            assertThat(resultat.donneesCredential()).isNotBlank();
            assertThat(resultat.compteurInitial()).isNotNegative();
        }

        @Test
        @DisplayName("un challenge different de celui enregistre fait echouer")
        void mauvaisChallengeRefuse() {
            // La preuve de fraicheur. Sans cette verification, une attestation
            // capturee resterait rejouable.
            Enregistrement e = enregistrer(nouveauChallenge());

            assertThatThrownBy(() -> verifier.verifierEnregistrement(
                    e.attestationObject(), e.clientDataJSON(),
                    base64url(nouveauChallenge().getValue())))
                    .isInstanceOf(WebAuthnVerificationException.class);
        }

        @Test
        @DisplayName("une attestation d'une autre origine est refusee")
        void mauvaiseOrigineRefusee() {
            // C'est ce qui empeche un site tiers de collecter une attestation
            // chez lui et de la presenter ici.
            Challenge challenge = nouveauChallenge();
            ClientPlatform autreSite = new ClientPlatform(
                    new Origin("https://attaquant.test"),
                    new WebAuthnAuthenticatorAdaptor(EmulatorUtil.PACKED_AUTHENTICATOR));

            PublicKeyCredentialCreationOptions options = new PublicKeyCredentialCreationOptions(
                    new PublicKeyCredentialRpEntity(RP_ID, "Restart"),
                    new PublicKeyCredentialUserEntity(
                            "utilisateur-1".getBytes(), "utilisateur-1", "Awa Traore"),
                    challenge,
                    List.of(new PublicKeyCredentialParameters(
                            PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                    null, null, criteres(), AttestationConveyancePreference.DIRECT, null);

            PublicKeyCredential<AuthenticatorAttestationResponse, RegistrationExtensionClientOutput> credential =
                    autreSite.create(options);

            assertThatThrownBy(() -> verifier.verifierEnregistrement(
                    base64url(credential.getAuthenticatorResponse().getAttestationObject()),
                    base64url(credential.getAuthenticatorResponse().getClientDataJSON()),
                    base64url(challenge.getValue())))
                    .isInstanceOf(WebAuthnVerificationException.class);
        }

        @Test
        @DisplayName("des donnees qui ne sont pas une attestation sont refusees")
        void donneesInvalidesRefusees() {
            assertThatThrownBy(() -> verifier.verifierEnregistrement(
                    base64url("pas une attestation".getBytes()),
                    base64url("{}".getBytes()),
                    base64url(nouveauChallenge().getValue())))
                    .isInstanceOf(WebAuthnVerificationException.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Authentification")
    class AuthentificationTest {

        @Test
        @DisplayName("une assertion produite par l'authentificateur enrole est acceptee")
        void assertionValideAcceptee() {
            // LE test qui manquait. Le placeholder refusait bien les signatures
            // quelconques ; ce qu'il ne faisait pas, c'est verifier une vraie
            // signature. Ce test exerce le chemin complet : enregistrement,
            // conservation du materiel de cle, puis assertion signee.
            Challenge challengeEnregistrement = nouveauChallenge();
            Enregistrement e = enregistrer(challengeEnregistrement);
            var enrole = verifier.verifierEnregistrement(
                    e.attestationObject(), e.clientDataJSON(),
                    base64url(challengeEnregistrement.getValue()));

            Challenge challengeAuth = nouveauChallenge();
            var assertion = authentifier(challengeAuth, enrole.credentialId());

            long compteur = verifier.verifierAssertion(
                    enrole.donneesCredential(),
                    enrole.compteurInitial(),
                    base64url(assertion.getRawId()),
                    assertion.getAuthenticatorResponse().getUserHandle() == null
                            ? null : base64url(assertion.getAuthenticatorResponse().getUserHandle()),
                    base64url(assertion.getAuthenticatorResponse().getAuthenticatorData()),
                    base64url(assertion.getAuthenticatorResponse().getClientDataJSON()),
                    base64url(assertion.getAuthenticatorResponse().getSignature()),
                    base64url(challengeAuth.getValue()));

            assertThat(compteur)
                    .withFailMessage("le compteur rendu doit progresser : c'est ce qui "
                            + "permet de detecter un authentificateur clone")
                    .isGreaterThanOrEqualTo(enrole.compteurInitial());
        }

        @Test
        @DisplayName("une signature forgee a la facon de l'ancien placeholder est refusee")
        void signatureForgeeRefusee() {
            // Reproduit exactement l'attaque que l'ancien code permettait :
            // Base64(publicKey + authenticatorData + clientDataJSON + challenge).
            // Elle doit maintenant echouer.
            Challenge challengeEnregistrement = nouveauChallenge();
            Enregistrement e = enregistrer(challengeEnregistrement);
            var enrole = verifier.verifierEnregistrement(
                    e.attestationObject(), e.clientDataJSON(),
                    base64url(challengeEnregistrement.getValue()));

            Challenge challengeAuth = nouveauChallenge();
            var assertion = authentifier(challengeAuth, enrole.credentialId());

            String signatureForgee = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (enrole.donneesCredential()
                            + base64url(assertion.getAuthenticatorResponse().getAuthenticatorData())
                            + base64url(assertion.getAuthenticatorResponse().getClientDataJSON())
                            + base64url(challengeAuth.getValue())).getBytes());

            assertThatThrownBy(() -> verifier.verifierAssertion(
                    enrole.donneesCredential(), enrole.compteurInitial(),
                    base64url(assertion.getRawId()), null,
                    base64url(assertion.getAuthenticatorResponse().getAuthenticatorData()),
                    base64url(assertion.getAuthenticatorResponse().getClientDataJSON()),
                    signatureForgee,
                    base64url(challengeAuth.getValue())))
                    .isInstanceOf(WebAuthnVerificationException.class);
        }

        @Test
        @DisplayName("un challenge qui n'est pas celui emis est refuse")
        void mauvaisChallengeRefuse() {
            Challenge challengeEnregistrement = nouveauChallenge();
            Enregistrement e = enregistrer(challengeEnregistrement);
            var enrole = verifier.verifierEnregistrement(
                    e.attestationObject(), e.clientDataJSON(),
                    base64url(challengeEnregistrement.getValue()));

            var assertion = authentifier(nouveauChallenge(), enrole.credentialId());

            assertThatThrownBy(() -> verifier.verifierAssertion(
                    enrole.donneesCredential(), enrole.compteurInitial(),
                    base64url(assertion.getRawId()), null,
                    base64url(assertion.getAuthenticatorResponse().getAuthenticatorData()),
                    base64url(assertion.getAuthenticatorResponse().getClientDataJSON()),
                    base64url(assertion.getAuthenticatorResponse().getSignature()),
                    base64url(nouveauChallenge().getValue()))) // challenge sans rapport
                    .isInstanceOf(WebAuthnVerificationException.class);
        }

        @Test
        @DisplayName("un compteur deja atteint est refuse")
        void compteurNonProgressantRefuse() {
            // Detection d'authentificateur clone : si le compteur presente n'est
            // pas superieur au dernier connu, l'assertion vient probablement
            // d'une copie. L'ancien code incrementait un compteur local sans
            // jamais comparer celui de l'assertion.
            Challenge challengeEnregistrement = nouveauChallenge();
            Enregistrement e = enregistrer(challengeEnregistrement);
            var enrole = verifier.verifierEnregistrement(
                    e.attestationObject(), e.clientDataJSON(),
                    base64url(challengeEnregistrement.getValue()));

            Challenge challengeAuth = nouveauChallenge();
            var assertion = authentifier(challengeAuth, enrole.credentialId());

            assertThatThrownBy(() -> verifier.verifierAssertion(
                    enrole.donneesCredential(),
                    Long.MAX_VALUE - 1, // compteur stocke anormalement eleve
                    base64url(assertion.getRawId()), null,
                    base64url(assertion.getAuthenticatorResponse().getAuthenticatorData()),
                    base64url(assertion.getAuthenticatorResponse().getClientDataJSON()),
                    base64url(assertion.getAuthenticatorResponse().getSignature()),
                    base64url(challengeAuth.getValue())))
                    .isInstanceOf(WebAuthnVerificationException.class);
        }
    }
}
