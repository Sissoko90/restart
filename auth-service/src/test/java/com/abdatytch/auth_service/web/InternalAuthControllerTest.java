package com.abdatytch.auth_service.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le secret inter-services, sur les deux endpoints internes d'auth-service.
 *
 * <h2>Le trou que ce test ferme</h2>
 *
 * {@code InternalAuthController} ne lisait <b>aucun</b> en-tête, et
 * {@code SecurityConfig} déclare {@code /internal/**} en {@code permitAll}. Le
 * {@code DELETE} est l'effacement RGPD d'un utilisateur : il n'avait aucune
 * authentification propre.
 *
 * <p>L'écart était propre à auth-service — les endpoints {@code /internal/**}
 * d'interface-service vérifient tous ce secret à temps constant.
 *
 * <p>Et l'appelant faisait sa part : user-service envoyait bien un en-tête de
 * secret. C'est le destinataire qui ne le lisait pas, ce qui est la forme la
 * plus trompeuse du défaut — en lisant le client, on conclut que le chemin est
 * protégé.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalAuthController — le secret inter-services est exigé")
class InternalAuthControllerTest {

    private static final String SECRET = "secret-interne-de-test-suffisamment-long";
    private static final String USER_ID = "ea310099-ee14-489c-9efb-76706aa79593";

    @Mock private com.abdatytch.auth_service.service.IInternalAccountService internalAccountService;

    private WebTestClient client;

    @BeforeEach
    void preparer() {
        InternalAuthController controleur = new InternalAuthController(internalAccountService);
        client = WebTestClient.bindToController(controleur).build();

        // Le champ est pose APRES build(), et l'ordre n'est pas indifferent.
        //
        // `bindToController` applique le post-traitement Spring a l'instance
        // fournie. Faute de PropertySourcesPlaceholderConfigurer, le @Value
        // n'est pas resolu et le champ recoit la chaine LITTERALE
        // « ${security.internal-secret} » — ecrasant une valeur posee avant.
        //
        // Mesure : avec le setField place avant build(), le champ valait
        // « ${security.internal-secret} » et tous les appels, meme avec le bon
        // en-tete, rendaient 404. Le symptome ressemblait a un secret faux.
        ReflectionTestUtils.setField(controleur, "internalSecret", SECRET);

        when(internalAccountService.lireCompte(anyString()))
                .thenReturn(Mono.just(new com.abdatytch.auth_service.dto.response.UserResponse()));
        when(internalAccountService.effacerCompte(anyString(), anyString()))
                .thenReturn(Mono.just(java.util.UUID.randomUUID()));
    }

    @Nested
    @DisplayName("Sans le secret, rien ne passe")
    class SansSecret {

        @Test
        @DisplayName("la lecture d'un utilisateur rend 404")
        void lectureRefusee() {
            client.get().uri("/internal/auth/users/" + USER_ID)
                    .exchange()
                    .expectStatus().isNotFound();
        }

        /**
         * Le point le plus grave : cet endpoint efface les données d'un
         * utilisateur, et il n'avait aucune authentification.
         */
        @Test
        @DisplayName("l'effacement rend 404 et n'efface RIEN")
        void effacementRefuse() {
            client.delete().uri("/internal/auth/users/" + USER_ID)
                    .exchange()
                    .expectStatus().isNotFound();

            // Le statut seul ne suffirait pas : il faut verifier qu'aucun
            // effacement n'a ete demande. Un 404 rendu APRES l'effacement
            // laisserait le defaut entier.
            verify(internalAccountService, never()).effacerCompte(anyString(), anyString());
        }

        @ParameterizedTest(name = "secret = « {0} »")
        @ValueSource(strings = {"", "mauvais-secret", "secret-interne-de-test-suffisamment-lon"})
        @DisplayName("un secret faux rend 404, jamais 403")
        void secretFaux(String secret) {
            // 403 confirmerait l'existence du point d'entree, et sur un endpoint
            // d'effacement il confirmerait en plus l'existence de
            // l'identifiant demande. Regle du depot : un refus se presente
            // comme une absence.
            client.delete().uri("/internal/auth/users/" + USER_ID)
                    .header("X-Internal-Secret", secret)
                    .exchange()
                    .expectStatus().isNotFound();

            verify(internalAccountService, never()).effacerCompte(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Avec le bon secret, l'appel aboutit")
    class AvecLeBonSecret {

        @Test
        @DisplayName("la lecture passe")
        void lecturePasse() {
            client.get().uri("/internal/auth/users/" + USER_ID)
                    .header("X-Internal-Secret", SECRET)
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("l'effacement est bien demandé")
        void effacementDemande() {
            client.delete().uri("/internal/auth/users/" + USER_ID)
                    .header("X-Internal-Secret", SECRET)
                    .exchange()
                    .expectStatus().is2xxSuccessful();

            verify(internalAccountService).effacerCompte(anyString(), anyString());
        }
    }

    @Test
    @DisplayName("la comparaison du secret reste à temps constant")
    void comparaisonATempsConstant() throws java.io.IOException {
        // Propriete qu'aucun test de comportement ne peut observer : `equals`
        // et `MessageDigest.isEqual` rendent le meme resultat, seul le temps
        // change. Verrouillee a la source, faute de mieux.
        java.nio.file.Path source = java.nio.file.Path.of("src/main/java/com/abdatytch/"
                + "auth_service/web/InternalAuthController.java");
        org.assertj.core.api.Assertions.assertThat(source)
                .withFailMessage("source introuvable : ce garde-fou serait vert sans "
                        + "rien vérifier")
                .exists();
        org.assertj.core.api.Assertions.assertThat(
                        java.nio.file.Files.readString(source))
                .withFailMessage("la comparaison doit passer par SecretComparisonUtil : "
                        + "une comparaison qui s'arrête au premier caractère différent "
                        + "laisse reconstituer le secret par le temps de réponse")
                .contains("SecretComparisonUtil.matches");
    }
}
