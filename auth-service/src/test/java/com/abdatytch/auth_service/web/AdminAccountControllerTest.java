package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.service.IAccountLockoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoints d'administration des verrouillages de compte.
 *
 * <h2>Le defaut corrige</h2>
 *
 * {@code POST /admin/accounts/{identifier}/lock} « simulait » un verrouillage en
 * appelant {@code recordFailedAttempt} <b>cinq fois de suite</b>. Deux problemes
 * distincts, qui se renforcaient :
 *
 * <ul>
 *   <li>le nombre d'appels etait couple a {@code auth.lockout.max-attempts}. Il
 *       vaut 5 aujourd'hui, donc cela fonctionnait — par coincidence. Porte a 10,
 *       le compte n'aurait plus ete verrouille du tout, et la reponse aurait
 *       <b>quand meme</b> annonce « Compte verrouille manuellement » ;</li>
 *   <li>le parametre {@code durationMinutes} etait <b>ignore</b>. Le verrouillage
 *       prenait la duree de configuration — 30 minutes — tandis que la reponse
 *       rapportait la duree demandee. Un administrateur demandant 24 heures
 *       obtenait 30 minutes et lisait « 1440 minutes ».</li>
 * </ul>
 *
 * <p>Le second point est le plus genant : une API qui affirme avoir fait une
 * chose et en fait une autre est pire qu'une API qui echoue, parce que personne
 * ne va verifier.
 */
@DisplayName("AdminAccountController - verrouillages")
class AdminAccountControllerTest {

    private static final String IDENTIFIANT = "+22374427759";

    private IAccountLockoutService lockoutService;
    private com.abdatytch.auth_service.service.IAdminAuthService adminAuthService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        lockoutService = Mockito.mock(IAccountLockoutService.class);
        adminAuthService = Mockito.mock(
                com.abdatytch.auth_service.service.IAdminAuthService.class);
        client = WebTestClient.bindToController(
                new AdminAccountController(lockoutService, adminAuthService)).build();
    }

    // ==================================================================
    @Nested
    @DisplayName("Verrouillage manuel")
    class Verrouillage {

        @Test
        @DisplayName("la duree demandee est reellement appliquee")
        void dureeAppliquee() {
            when(lockoutService.lockAccount(anyString(), anyInt())).thenReturn(Mono.empty());

            client.post().uri("/admin/accounts/" + IDENTIFIANT + "/lock?durationMinutes=1440")
                    .exchange()
                    .expectStatus().isOk();

            ArgumentCaptor<Integer> duree = ArgumentCaptor.forClass(Integer.class);
            verify(lockoutService).lockAccount(eq(IDENTIFIANT), duree.capture());

            assertThat(duree.getValue())
                    .withFailMessage("la duree demandee n'est pas transmise au service : "
                            + "la reponse annoncerait une duree non appliquee")
                    .isEqualTo(1440);
        }

        @Test
        @DisplayName("le verrouillage ne passe plus par des tentatives echouees simulees")
        void plusDeSimulation() {
            // La simulation dependait de auth.lockout.max-attempts valant
            // exactement 5. Ce test echouerait si quelqu'un la reintroduisait.
            when(lockoutService.lockAccount(anyString(), anyInt())).thenReturn(Mono.empty());

            client.post().uri("/admin/accounts/" + IDENTIFIANT + "/lock")
                    .exchange()
                    .expectStatus().isOk();

            verify(lockoutService, never()).recordFailedAttempt(anyString());
        }

        @Test
        @DisplayName("sans parametre, la duree par defaut est transmise explicitement")
        void dureeParDefaut() {
            when(lockoutService.lockAccount(anyString(), anyInt())).thenReturn(Mono.empty());

            client.post().uri("/admin/accounts/" + IDENTIFIANT + "/lock")
                    .exchange()
                    .expectStatus().isOk();

            verify(lockoutService).lockAccount(eq(IDENTIFIANT), eq(30));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Deverrouillage et remise a zero")
    class Deverrouillage {

        @Test
        @DisplayName("POST /unlock libere le compte")
        void deverrouillage() {
            when(lockoutService.unlockAccount(IDENTIFIANT)).thenReturn(Mono.empty());

            client.post().uri("/admin/accounts/" + IDENTIFIANT + "/unlock")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.message").exists();

            verify(lockoutService).unlockAccount(IDENTIFIANT);
        }

        @Test
        @DisplayName("POST /reset-attempts remet le compteur a zero")
        void remiseAZero() {
            when(lockoutService.resetFailedAttempts(IDENTIFIANT)).thenReturn(Mono.empty());

            client.post().uri("/admin/accounts/" + IDENTIFIANT + "/reset-attempts")
                    .exchange()
                    .expectStatus().isOk();

            verify(lockoutService).resetFailedAttempts(IDENTIFIANT);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Consultation")
    class Consultation {

        @Test
        @DisplayName("GET /lockout-status rend l'etat du compte")
        void etatDuCompte() {
            var user = new com.abdatytch.auth_service.entities.User();
            user.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(30));
            when(lockoutService.etatDuCompte(IDENTIFIANT)).thenReturn(Mono.just(
                    com.abdatytch.auth_service.dto.response.EtatVerrouillageResponse.de(
                            IDENTIFIANT, user, true, 0)));

            client.get().uri("/admin/accounts/" + IDENTIFIANT + "/lockout-status")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.identifier").isEqualTo(IDENTIFIANT)
                    .jsonPath("$.isLocked").isEqualTo(true)
                    // Present MEME pour un compte verrouille. L'ancienne version
                    // l'omettait dans ce cas : la reponse changeait de forme
                    // selon l'etat, et un client ecrit en lisant une reponse de
                    // compte verrouille ne voyait jamais ce champ.
                    .jsonPath("$.remainingAttempts").isEqualTo(0)
                    .jsonPath("$.lockedUntil").exists();
        }

        @Test
        @DisplayName("une seule lecture de la base pour l'etat complet")
        void uneSeuleLecture() {
            // Le controleur appelait isAccountLocked puis, selon le resultat,
            // getRemainingAttempts — deux allers-retours vers la base pour une
            // question dont l'entite porte les deux reponses.
            when(lockoutService.etatDuCompte(IDENTIFIANT)).thenReturn(Mono.just(
                    com.abdatytch.auth_service.dto.response.EtatVerrouillageResponse.de(
                            IDENTIFIANT, null, false, 5)));

            client.get().uri("/admin/accounts/" + IDENTIFIANT + "/lockout-status")
                    .exchange()
                    .expectStatus().isOk();

            verify(lockoutService).etatDuCompte(IDENTIFIANT);
            verify(lockoutService, never()).isAccountLocked(anyString());
            verify(lockoutService, never()).getRemainingAttempts(anyString());
        }

        @Test
        @DisplayName("GET /remaining-attempts rend le nombre de tentatives restantes")
        void tentativesRestantes() {
            when(lockoutService.etatDuCompte(IDENTIFIANT)).thenReturn(Mono.just(
                    com.abdatytch.auth_service.dto.response.EtatVerrouillageResponse.de(
                            IDENTIFIANT, null, false, 3)));

            client.get().uri("/admin/accounts/" + IDENTIFIANT + "/remaining-attempts")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.remainingAttempts").isEqualTo(3);
        }
    }
}
