package com.abdatytch.interface_service.erasure;

import com.abdatytch.interface_service.entity.Project;
import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.repository.ProjectRepository;
import com.abdatytch.interface_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Effacement du compte développeur (RGPD art. 17).
 *
 * <p>Les développeurs du portail forment une population distincte des
 * utilisateurs finaux : leur identité vit ici, pas dans auth-service. Ils ne
 * disposaient d'<b>aucun</b> moyen d'exercer ce droit — pas même d'une
 * suppression de compte. Leur adresse, leur empreinte de mot de passe et leur
 * secret TOTP restaient indéfiniment.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DeveloperErasureService — effacement du compte développeur")
class DeveloperErasureServiceTest {

    private static final UUID DEVELOPPEUR = UUID.randomUUID();

    @Mock private UserRepository userRepository;
    @Mock private ProjectRepository projectRepository;

    private DeveloperErasureService service;
    private User developpeur;

    @BeforeEach
    void setUp() {
        service = new DeveloperErasureService(userRepository, projectRepository);

        developpeur = new User();
        developpeur.setId(DEVELOPPEUR);
        developpeur.setUsername("awa.dev");
        developpeur.setEmail("awa.traore@restart.test");
        developpeur.setPhoneNumber("+22370000000");
        developpeur.setFirstName("Awa");
        developpeur.setLastName("Traoré");
        developpeur.setPasswordHash("$2a$10$empreinte.reelle");
        developpeur.setRecoverySecretHash("secret-de-recuperation");
        developpeur.setTotpSecret("GEZDGNBVGY3TQOJQ");
        developpeur.setTotpEnabled(true);
        developpeur.setRefreshToken("jeton-de-rafraichissement");
        developpeur.setRefreshTokenHash("empreinte-du-jeton");
        developpeur.setEmailOtpCode("123456");
        developpeur.setPasswordResetToken("jeton-de-reinitialisation");
        developpeur.setEmailVerified(true);
        developpeur.setMarketingConsent(true);
        developpeur.setLastLoginAt(LocalDateTime.now());
        developpeur.setIsActive(true);

        when(userRepository.findById(DEVELOPPEUR)).thenReturn(Mono.just(developpeur));
        when(userRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(projectRepository.findByUserId(DEVELOPPEUR)).thenReturn(Flux.empty());
        when(projectRepository.delete(any())).thenReturn(Mono.empty());
    }

    private User apresEffacement() {
        StepVerifier.create(service.erase(DEVELOPPEUR.toString()))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<User> capture = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(capture.capture());
        return capture.getValue();
    }

    // ==================================================================
    @Nested
    @DisplayName("Identité")
    class Identite {

        @Test
        @DisplayName("adresse, téléphone et nom sont effacés")
        void identiteEffacee() {
            User apres = apresEffacement();

            assertThat(apres.getEmail()).isNull();
            assertThat(apres.getPhoneNumber()).isNull();
            assertThat(apres.getFirstName()).isNotEqualTo("Awa");
            assertThat(apres.getLastName()).isNotEqualTo("Traoré");
        }

        @Test
        @DisplayName("le nom d'utilisateur devient unique et non identifiant")
        void nomUtilisateurNeutralise() {
            // Il porte une contrainte d'unicité : le mettre à null ferait échouer
            // un second effacement. Une valeur dérivée de l'identifiant reste
            // unique sans désigner personne.
            User apres = apresEffacement();

            assertThat(apres.getUsername())
                    .isNotEqualTo("awa.dev")
                    .contains(DEVELOPPEUR.toString());
        }

        @Test
        @DisplayName("le compte est désactivé")
        void compteDesactive() {
            User apres = apresEffacement();

            assertThat(apres.getIsActive()).isFalse();
            assertThat(apres.getEmailVerified()).isFalse();
            assertThat(apres.getMarketingConsent()).isFalse();
            assertThat(apres.getLastLoginAt()).isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Secrets")
    class Secrets {

        @Test
        @DisplayName("le secret TOTP est effacé et le second facteur désactivé")
        void secretTotpEfface() {
            // Un secret TOTP conservé permettrait de générer indéfiniment des
            // codes valides pour un compte censé ne plus exister.
            User apres = apresEffacement();

            assertThat(apres.getTotpSecret())
                    .withFailMessage("le secret TOTP subsiste : des codes valides "
                            + "resteraient calculables pour un compte effacé")
                    .isNull();
            assertThat(apres.getTotpEnabled()).isFalse();
        }

        @Test
        @DisplayName("empreinte du mot de passe et secret de récupération sont effacés")
        void empreintesEffacees() {
            User apres = apresEffacement();

            assertThat(apres.getPasswordHash()).doesNotContain("empreinte.reelle");
            assertThat(apres.getRecoverySecretHash()).isNull();
        }

        @Test
        @DisplayName("jetons et codes en cours sont effacés")
        void jetonsEtCodesEffaces() {
            User apres = apresEffacement();

            assertThat(apres.getRefreshToken()).isNull();
            assertThat(apres.getRefreshTokenHash()).isNull();
            assertThat(apres.getEmailOtpCode()).isNull();
            assertThat(apres.getPasswordResetToken()).isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Projets et cas limites")
    class ProjetsEtCasLimites {

        @Test
        @DisplayName("les projets du développeur sont supprimés et comptés")
        void projetsSupprimes() {
            // Des identifiants distincts : sans eux, deux projets aux champs
            // nuls sont considérés comme égaux, et la vérification devient
            // ambiguë.
            Project projet1 = new Project();
            projet1.setId(UUID.randomUUID());
            Project projet2 = new Project();
            projet2.setId(UUID.randomUUID());
            when(projectRepository.findByUserId(DEVELOPPEUR))
                    .thenReturn(Flux.just(projet1, projet2));

            StepVerifier.create(service.erase(DEVELOPPEUR.toString()))
                    // deux projets + le compte
                    .expectNext(3)
                    .verifyComplete();

            verify(projectRepository).delete(projet1);
            verify(projectRepository).delete(projet2);
        }

        @Test
        @DisplayName("un identifiant qui n'est pas un développeur donne zéro")
        void identifiantEtranger() {
            // Les demandes d'effacement d'utilisateurs finaux transitent par la
            // même file : ce service doit les ignorer sans erreur.
            StepVerifier.create(service.erase("pas-un-uuid"))
                    .expectNext(0)
                    .verifyComplete();

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("un développeur inconnu donne zéro, pas une erreur")
        void developpeurInconnu() {
            UUID inconnu = UUID.randomUUID();
            when(projectRepository.findByUserId(inconnu)).thenReturn(Flux.empty());
            when(userRepository.findById(inconnu)).thenReturn(Mono.empty());

            StepVerifier.create(service.erase(inconnu.toString()))
                    .expectNext(0)
                    .verifyComplete();
        }
    }
}
