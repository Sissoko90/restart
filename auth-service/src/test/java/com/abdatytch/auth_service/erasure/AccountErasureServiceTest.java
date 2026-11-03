package com.abdatytch.auth_service.erasure;

import com.abdatytch.auth_service.entities.*;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.repository.*;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent.ErasureReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Effacement des données d'identité (RGPD art. 17).
 *
 * <p>Ces tests énumèrent, champ par champ, ce qui doit disparaître. C'est
 * volontairement fastidieux : un champ oublié dans le code de production le
 * serait aussi dans une relecture, et resterait en base — dans les sauvegardes,
 * et dans la portée d'une fuite — sans que rien ne le signale.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AccountErasureService — effacement de l'identité")
class AccountErasureServiceTest {

    private static final String PORTEUR = "11111111-2222-3333-4444-555555555555";

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private VerificationCodeRepository verificationCodeRepository;
    @Mock private BiometricCredentialRepository biometricCredentialRepository;
    @Mock private OAuthAccountRepository oAuthAccountRepository;
    @Mock private AuthenticationLogRepository authenticationLogRepository;
    @Mock private UserRoleAssignmentRepository roleAssignmentRepository;
    @Mock private ErasureRequestRepository erasureRequestRepository;
    @Mock private com.abdatytch.auth_service.repository.ErasureAcknowledgementRepository
            erasureAcknowledgementRepository;
    @Mock private ErasurePublisher erasurePublisher;

    private AccountErasureService service;
    private User utilisateur;

    @BeforeEach
    void setUp() {
        service = new AccountErasureService(userRepository, refreshTokenRepository,
                verificationCodeRepository, biometricCredentialRepository,
                oAuthAccountRepository,
                // Depot d'appareils de confiance simule : ces tests mesurent
                // l'effacement local, et la table a ete ajoutee au zip. Un
                // simulacre rendant Flux.empty() suffit — son contenu est
                // mesure par AppareilDeConfianceServiceImplTest.
                depotDAppareilsVide(),
                depotDeCodesVide(),
                authenticationLogRepository,
                roleAssignmentRepository, erasureRequestRepository,
                erasureAcknowledgementRepository, erasurePublisher,
                // Registre VIDE : aucun fournisseur social n'est declare, donc
                // sansVerifierLaConfiguration rend null et la revocation est
                // sautee. C'est l'etat voulu ici — ces tests mesurent
                // l'effacement local, pas la revocation chez un tiers, qui a
                // ses propres tests.
                new com.abdatytch.auth_service.service.oauth2
                        .RegistreDesFournisseursOAuth2(java.util.List.of()));

        utilisateur = new User();
        utilisateur.setId(PORTEUR);
        utilisateur.setEmail("awa.traore@abdatech.ml");
        utilisateur.setPhoneNumber("+22370000000");
        utilisateur.setCountryCode("ML");
        utilisateur.setFirstName("Awa");
        utilisateur.setLastName("Traoré");
        utilisateur.setPasswordHash("$2a$10$empreinte.reelle.du.mot.de.passe");
        utilisateur.setIsActive(true);
        utilisateur.setIsEmailVerified(true);
        utilisateur.setIsPhoneVerified(true);
        utilisateur.setRole(UserRole.USER);

        when(userRepository.findById(PORTEUR)).thenReturn(Mono.just(utilisateur));
        when(userRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(erasureRequestRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(erasurePublisher.publishRequested(any())).thenReturn(Mono.empty());

        when(refreshTokenRepository.findByUserId(PORTEUR)).thenReturn(Flux.empty());
        when(biometricCredentialRepository.findByUserId(PORTEUR)).thenReturn(Flux.empty());
        when(oAuthAccountRepository.findByUserId(PORTEUR)).thenReturn(Flux.empty());
        when(roleAssignmentRepository.findByUserId(PORTEUR)).thenReturn(Flux.empty());
        when(authenticationLogRepository.findByUserIdOrderByTimestampDesc(PORTEUR))
                .thenReturn(Flux.empty());
    }

    private void executer() {
        StepVerifier.create(service.requestErasure(PORTEUR, PORTEUR,
                        ErasureReason.DEMANDE_DE_LA_PERSONNE))
                .expectNextCount(1)
                .verifyComplete();
    }

    // ==================================================================
    @Nested
    @DisplayName("Les champs identifiants disparaissent")
    class ChampsIdentifiants {

        @Test
        @DisplayName("courriel, téléphone, nom, prénom et pays sont effacés")
        void identiteEffacee() {
            executer();

            ArgumentCaptor<User> sauvegarde = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(sauvegarde.capture());
            User apres = sauvegarde.getValue();

            assertThat(apres.getEmail())
                    .withFailMessage("le courriel subsiste : il identifie directement la personne")
                    .isNull();
            assertThat(apres.getPhoneNumber())
                    .withFailMessage("le numéro de téléphone subsiste")
                    .isNull();
            assertThat(apres.getCountryCode()).isNull();
            assertThat(apres.getFirstName()).isNotEqualTo("Awa");
            assertThat(apres.getLastName()).isNotEqualTo("Traoré");
        }

        @Test
        @DisplayName("l'empreinte du mot de passe est remplacée")
        void empreinteRemplacee() {
            // Une empreinte BCrypt reste attaquable hors ligne. La laisser en
            // base après un effacement, c'est laisser de quoi retrouver le mot
            // de passe — souvent réutilisé ailleurs.
            executer();

            ArgumentCaptor<User> sauvegarde = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(sauvegarde.capture());

            assertThat(sauvegarde.getValue().getPasswordHash())
                    .withFailMessage("l'empreinte du mot de passe subsiste")
                    .doesNotContain("$2a$10$empreinte.reelle");
        }

        @Test
        @DisplayName("le compte est désactivé et ses vérifications retirées")
        void compteDesactive() {
            // Un compte effacé mais toujours actif accepterait encore des
            // tentatives de connexion, et son existence resterait observable
            // par énumération.
            executer();

            ArgumentCaptor<User> sauvegarde = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(sauvegarde.capture());
            User apres = sauvegarde.getValue();

            assertThat(apres.getIsActive()).isFalse();
            assertThat(apres.getIsEmailVerified()).isFalse();
            assertThat(apres.getIsPhoneVerified()).isFalse();
        }

        @Test
        @DisplayName("l'identifiant technique est CONSERVÉ")
        void identifiantConserve() {
            // Supprimer la ligne casserait les journaux d'audit et les
            // affectations de rôles. L'identifiant devient un jeton opaque :
            // plus aucune table ne permet de remonter à une personne.
            executer();

            ArgumentCaptor<User> sauvegarde = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(sauvegarde.capture());

            assertThat(sauvegarde.getValue().getId()).isEqualTo(PORTEUR);
            verify(userRepository, never()).delete(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Données rattachées")
    class DonneesRattachees {

        @Test
        @DisplayName("jetons, biométrie, comptes OAuth et rôles sont supprimés")
        void donneesRattacheesSupprimees() {
            RefreshToken jeton = new RefreshToken();
            BiometricCredential biometrie = new BiometricCredential();
            OAuthAccount oauth = new OAuthAccount();
            UserRoleAssignment role = new UserRoleAssignment();

            when(refreshTokenRepository.findByUserId(PORTEUR)).thenReturn(Flux.just(jeton));
            when(biometricCredentialRepository.findByUserId(PORTEUR)).thenReturn(Flux.just(biometrie));
            when(oAuthAccountRepository.findByUserId(PORTEUR)).thenReturn(Flux.just(oauth));
            when(roleAssignmentRepository.findByUserId(PORTEUR)).thenReturn(Flux.just(role));
            when(refreshTokenRepository.delete(any())).thenReturn(Mono.empty());
            when(biometricCredentialRepository.delete(any())).thenReturn(Mono.empty());
            when(oAuthAccountRepository.delete(any())).thenReturn(Mono.empty());
            when(roleAssignmentRepository.delete(any())).thenReturn(Mono.empty());

            executer();

            verify(refreshTokenRepository).delete(jeton);
            verify(biometricCredentialRepository).delete(biometrie);
            verify(oAuthAccountRepository).delete(oauth);
            verify(roleAssignmentRepository).delete(role);
        }

        @Test
        @DisplayName("les journaux sont anonymisés, pas supprimés")
        void journauxAnonymises() {
            // Détachés de la personne, ils gardent une valeur antifraude.
            // L'adresse IP et l'agent utilisateur, eux, sont des données
            // personnelles à part entière et doivent partir.
            AuthenticationLog journal = new AuthenticationLog();
            journal.setUserId(PORTEUR);
            journal.setPhoneNumber("+22370000000");
            journal.setIpAddress("203.0.113.7");
            journal.setUserAgent("Mozilla/5.0");
            journal.setDeviceInfo("iPhone 14");

            when(authenticationLogRepository.findByUserIdOrderByTimestampDesc(PORTEUR))
                    .thenReturn(Flux.just(journal));
            when(authenticationLogRepository.save(any()))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            executer();

            ArgumentCaptor<AuthenticationLog> capture =
                    ArgumentCaptor.forClass(AuthenticationLog.class);
            verify(authenticationLogRepository).save(capture.capture());
            AuthenticationLog apres = capture.getValue();

            assertThat(apres.getIpAddress())
                    .withFailMessage("l'adresse IP subsiste : c'est une donnée personnelle")
                    .isNull();
            assertThat(apres.getPhoneNumber()).isNull();
            assertThat(apres.getUserAgent()).isNull();
            assertThat(apres.getDeviceInfo()).isNull();

            verify(authenticationLogRepository, never()).delete(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Traçabilité et propagation")
    class TracabiliteEtPropagation {

        @Test
        @DisplayName("la demande est enregistrée AVANT tout effacement")
        void demandeEnregistreeAvant() {
            // Si le traitement échoue en cours de route, la trace subsiste et la
            // reprise reste possible. Enregistrer après aurait laissé, en cas de
            // panne, des données à moitié effacées sans trace de la demande.
            executer();

            InOrder ordre = inOrder(erasureRequestRepository, userRepository);
            ordre.verify(erasureRequestRepository).save(any(ErasureRequest.class));
            ordre.verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("la demande consigne le porteur, le motif et l'auteur")
        void demandeConsignee() {
            StepVerifier.create(service.requestErasure(PORTEUR, "admin-42",
                            ErasureReason.DECISION_ADMINISTRATIVE))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<ErasureRequest> capture =
                    ArgumentCaptor.forClass(ErasureRequest.class);
            verify(erasureRequestRepository).save(capture.capture());
            ErasureRequest demande = capture.getValue();

            assertThat(demande.getSubjectId()).isEqualTo(PORTEUR);
            assertThat(demande.getRequestedBy()).isEqualTo("admin-42");
            assertThat(demande.getReason()).isEqualTo(ErasureReason.DECISION_ADMINISTRATIVE);
            assertThat(demande.getCompletedAt())
                    .withFailMessage("la demande est marquée terminée avant que les autres "
                            + "services aient accusé réception")
                    .isNull();
        }

        @Test
        @DisplayName("les autres services sont prévenus, avec le même identifiant de demande")
        void propagationAuxAutresServices() {
            executer();

            ArgumentCaptor<UserErasureRequestedEvent> capture =
                    ArgumentCaptor.forClass(UserErasureRequestedEvent.class);
            verify(erasurePublisher).publishRequested(capture.capture());
            UserErasureRequestedEvent evenement = capture.getValue();

            assertThat(evenement.subjectId()).isEqualTo(PORTEUR);

            ArgumentCaptor<ErasureRequest> demande =
                    ArgumentCaptor.forClass(ErasureRequest.class);
            verify(erasureRequestRepository).save(demande.capture());

            assertThat(evenement.requestId().toString())
                    .withFailMessage("l'événement porte un identifiant différent de celui "
                            + "enregistré : les accusés ne pourront pas être rattachés")
                    .isEqualTo(demande.getValue().getId());
        }

        @Test
        @DisplayName("un porteur inconnu ne fait pas échouer la demande")
        void porteurInconnu() {
            // Le service doit rester idempotent : une seconde demande pour une
            // personne déjà effacée ne doit pas produire d'erreur.
            when(userRepository.findById(PORTEUR)).thenReturn(Mono.empty());

            executer();

            verify(erasurePublisher).publishRequested(any());
        }
    }

    /**
     * Depot d'appareils de confiance vide.
     *
     * <p>Mockito plutot qu'une sous-classe anonyme : l'interface est un
     * {@code ReactiveCrudRepository}, dont l'implementation manuelle demanderait
     * une vingtaine de methodes sans rapport avec ce test.
     */
    private static com.abdatytch.auth_service.repository.AppareilDeConfianceRepository
            depotDAppareilsVide() {
        var depot = org.mockito.Mockito.mock(
                com.abdatytch.auth_service.repository.AppareilDeConfianceRepository.class);
        org.mockito.Mockito.when(depot.findByUserId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(reactor.core.publisher.Flux.empty());
        return depot;
    }

    /**
     * Depot de codes de secours vide.
     *
     * <p>Meme raison que {@link #depotDAppareilsVide()} : la table a rejoint le
     * zip de l'effacement, et son contenu est mesure par les tests du service
     * des codes de secours.
     */
    private static com.abdatytch.auth_service.repository.CodeDeSecoursRepository
            depotDeCodesVide() {
        var depot = org.mockito.Mockito.mock(
                com.abdatytch.auth_service.repository.CodeDeSecoursRepository.class);
        org.mockito.Mockito.when(depot.findByUserId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(reactor.core.publisher.Flux.empty());
        return depot;
    }
}
