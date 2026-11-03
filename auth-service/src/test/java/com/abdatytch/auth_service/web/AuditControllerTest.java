package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.entities.AuthenticationLog;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import com.abdatytch.auth_service.service.IAuthenticationLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoints d'audit.
 *
 * <h2>Ce que ces endpoints manipulent</h2>
 *
 * Le journal d'authentification est a la fois la trace qui permet de reperer une
 * attaque et une piece du registre de preuve exige par l'article 5.2 du RGPD.
 * Les endpoints de lecture exposent donc des donnees personnelles, et l'endpoint
 * de purge peut detruire cette trace.
 *
 * <p>La recherche par numero de telephone est passee d'un
 * {@code GET /logs/phone/{phoneNumber}} a un {@code POST /logs/search} : le
 * numero est desormais dans le corps, hors des journaux d'acces. Voir
 * {@code journauxParNumero} pour le raisonnement.
 *
 * <p>La matrice d'autorisation est figee par {@code SecurityConfigTest} : ces
 * chemins sont sous {@code /admin/**} et exigent le role ADMIN. Ces tests-ci
 * portent sur le comportement du controleur une fois l'appel autorise.
 */
@DisplayName("AuditController - audit et purge")
class AuditControllerTest {

    private IAuthenticationLogService logService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        logService = Mockito.mock(IAuthenticationLogService.class);
        client = WebTestClient.bindToController(new AuditController(logService)).build();
    }

    private AuthenticationLog journal() {
        AuthenticationLog j = new AuthenticationLog();
        j.setId("journal-1");
        j.setEventType(EventType.LOGIN);
        j.setStatus(Status.SUCCESS);
        return j;
    }

    // ==================================================================
    @Nested
    @DisplayName("Lecture des journaux")
    class Lecture {

        @Test
        @DisplayName("GET /admin/audit/logs rend les journaux recents")
        void journauxRecents() {
            when(logService.getAllLogs()).thenReturn(Flux.just(journal()));

            client.get().uri("/admin/audit/logs")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$[0].id").isEqualTo("journal-1");
        }

        @Test
        @DisplayName("GET /logs/user/{userId} filtre par utilisateur")
        void journauxParUtilisateur() {
            when(logService.getLogsByUserId("utilisateur-1")).thenReturn(Flux.just(journal()));

            client.get().uri("/admin/audit/logs/user/utilisateur-1")
                    .exchange()
                    .expectStatus().isOk();

            verify(logService).getLogsByUserId("utilisateur-1");
        }

        @Test
        @DisplayName("POST /logs/search filtre par numero, numero dans le CORPS")
        void journauxParNumero() {
            // DECISION APPLIQUEE. L'endpoint etait
            // GET /logs/phone/{phoneNumber} : le numero figurait dans le chemin
            // de l'URL, donc dans les journaux d'acces du Gateway, ceux de tout
            // proxy, les traces de supervision et l'historique du navigateur.
            //
            // Le point decisif n'est pas la divulgation mais l'ineffacabilite :
            // AccountErasureService sait ecraser une colonne, il ne sait pas
            // retirer un numero des journaux d'acces d'un proxy. Une donnee
            // personnelle versee dans une URL sort definitivement du perimetre
            // de l'effacement RGPD.
            when(logService.getLogsByPhoneNumber("+22374427759"))
                    .thenReturn(Flux.just(journal()));

            client.post().uri("/admin/audit/logs/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"phoneNumber\":\"+22374427759\"}")
                    .exchange()
                    .expectStatus().isOk();

            verify(logService).getLogsByPhoneNumber("+22374427759");
        }

        @Test
        @DisplayName("l'ancienne route avec le numero dans l'URL n'existe plus")
        void ancienneRouteRetiree() {
            // Verrouille la decision : laisser l'ancienne route en place « pour
            // la compatibilite » aurait annule la correction, puisque les
            // appelants existants auraient continue a verser le numero dans
            // l'URL.
            client.get().uri("/admin/audit/logs/phone/+22374427759")
                    .exchange()
                    .expectStatus().value(code -> org.assertj.core.api.Assertions
                            .assertThat(code)
                            .withFailMessage("l'ancienne route répond encore %s : "
                                    + "le numéro continue d'arriver dans les "
                                    + "journaux d'accès", code)
                            .isNotEqualTo(200));
        }

        @Test
        @DisplayName("un numero manquant est refuse, pas traite comme une recherche vide")
        void numeroManquant() {
            // Sans validation, un corps vide rendrait la totalite des journaux
            // sous l'apparence d'une recherche ciblee.
            client.post().uri("/admin/audit/logs/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().value(code -> org.assertj.core.api.Assertions
                            .assertThat(code).isNotEqualTo(200));

            verify(logService, never()).getLogsByPhoneNumber(anyString());
        }

        @Test
        @DisplayName("une absence de journaux rend une liste vide, pas une erreur")
        void aucunJournal() {
            // Le piege reactif habituel : un Flux vide collecte en liste donne
            // une liste vide, pas un Mono vide. Si le controleur rendait un Mono
            // vide, WebFlux repondrait 200 sans corps et l'appelant ne pourrait
            // pas distinguer l'absence d'une panne.
            when(logService.getAllLogs()).thenReturn(Flux.empty());

            client.get().uri("/admin/audit/logs")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$").isArray();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Statistiques")
    class Statistiques {

        @Test
        @DisplayName("GET /failed-attempts rend un decompte")
        void tentativesEchouees() {
            when(logService.getLogsByEventTypeAndStatus(any(), any()))
                    .thenReturn(Flux.just(journal(), journal()));

            client.get().uri("/admin/audit/failed-attempts")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.count").isEqualTo(2);
        }

        @Test
        @DisplayName("GET /security-stats agrege plusieurs sources")
        void statistiques() {
            when(logService.getLogsByEventTypeAndStatus(any(), any()))
                    .thenReturn(Flux.just(journal()));
            when(logService.getAllLogs()).thenReturn(Flux.just(journal(), journal()));

            client.get().uri("/admin/audit/security-stats")
                    .exchange()
                    .expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Purge des journaux")
    class Purge {

        @Test
        @DisplayName("DELETE /logs/cleanup transmet la duree de conservation")
        void purgeAvecDuree() {
            when(logService.cleanupOldLogs(anyInt())).thenReturn(Mono.empty());

            client.delete().uri("/admin/audit/logs/cleanup?days=180")
                    .exchange()
                    .expectStatus().isOk();

            ArgumentCaptor<Integer> jours = ArgumentCaptor.forClass(Integer.class);
            verify(logService).cleanupOldLogs(jours.capture());
            assertThat(jours.getValue()).isEqualTo(180);
        }

        @Test
        @DisplayName("sans parametre, la duree par defaut est 90 jours")
        void dureeParDefaut() {
            when(logService.cleanupOldLogs(anyInt())).thenReturn(Mono.empty());

            client.delete().uri("/admin/audit/logs/cleanup")
                    .exchange()
                    .expectStatus().isOk();

            verify(logService).cleanupOldLogs(90);
        }

        @ParameterizedTest(name = "days={0}")
        @ValueSource(ints = {0, -1, -365})
        @DisplayName("une duree nulle ou negative est REFUSEE")
        void dureeNulleOuNegativeRefusee(int jours) {
            // LE point de ce bloc. `days=0` demandait de supprimer tout journal
            // de plus de zero jour — c'est-a-dire TOUS les journaux, registre de
            // preuve RGPD compris, et la trace qui permet de reperer une attaque.
            //
            // Aucune borne inferieure n'etait posee. Un chiffre mal saisi par un
            // administrateur, ou un appel construit a dessein, effacait l'audit
            // sans que rien ne s'y oppose. Et un effacement d'audit est, par
            // nature, ce qu'on ne peut pas constater apres coup.
            client.delete().uri("/admin/audit/logs/cleanup?days=" + jours)
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(logService, never()).cleanupOldLogs(anyInt());
        }
    }
}
