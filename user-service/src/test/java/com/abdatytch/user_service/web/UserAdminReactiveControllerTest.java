package com.abdatytch.user_service.web;

import com.abdatytch.user_service.dto.request.AdminActionDTO;
import com.abdatytch.user_service.dto.request.AdminUserUpdateDTO;
import com.abdatytch.user_service.dto.response.AuditLogDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.enums.ActionType;
import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.service.AuditLogService;
import com.abdatytch.user_service.service.UserAdminService;
import com.abdatytch.user_service.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Surface d'administration des comptes.
 *
 * <h2>Pourquoi ce fichier a été réécrit</h2>
 *
 * La version précédente simulait un service, <b>appelait le mock</b>, puis
 * vérifiait que le mock avait été appelé :
 *
 * <pre>{@code
 * when(adminService.unblockUser(id)).thenReturn(Mono.just(dto));
 * StepVerifier.create(adminService.unblockUser(id))   // le MOCK, pas le controleur
 *         .expectNext(dto).verifyComplete();
 * verify(adminService).unblockUser(id);
 * }</pre>
 *
 * <p>Le contrôleur était injecté par {@code @InjectMocks} et <b>jamais
 * invoqué</b>. Treize assertions de ce genre existaient entre les deux
 * contrôleurs d'administration : elles vérifiaient que Mockito fonctionne, ce
 * qui n'a jamais été la question. Aucune n'aurait échoué si le contrôleur avait
 * rendu un 500 à chaque appel, ni s'il n'avait pas existé.
 *
 * <p>Ces tests appellent le contrôleur.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserAdminReactiveController — surface d'administration")
class UserAdminReactiveControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserAdminService adminService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserAdminReactiveController controller;

    private UUID compteId;
    private UserDetailDTO compte;

    @BeforeEach
    void setUp() {
        compteId = UUID.randomUUID();
        compte = new UserDetailDTO(
                compteId, "auth-456", "user@example.com", "+22300000000", "ML",
                "John", "Doe", null, null,
                UserStatus.ACTIVE, UserRole.USER, "bio", "123 rue", "Bamako", "Mali",
                "75001", null, LocalDateTime.now(), LocalDateTime.now(), null);
    }

    // ==================================================================
    @Nested
    @DisplayName("Les cinq décisions passent au service")
    class Decisions {

        @Test
        @DisplayName("block transmet le motif et rend 200")
        void blocage() {
            AdminActionDTO action = new AdminActionDTO("Violation des regles", null);
            when(adminService.blockUser(compteId, action)).thenReturn(Mono.just(compte));

            StepVerifier.create(controller.blockUser(compteId, action))
                    .assertNext(reponse -> {
                        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(reponse.getBody()).isEqualTo(compte);
                    })
                    .verifyComplete();

            verify(adminService).blockUser(compteId, action);
        }

        @Test
        @DisplayName("ban est distinct de block : il appelle banUser, pas blockUser")
        void bannissement() {
            // Bannissement et blocage sont deux etats METIER distincts
            // (definitif contre temporaire). Le contrat de ce test est qu'ils ne
            // soient pas redirriges l'un vers l'autre au nom de la
            // factorisation : la mecanique est partagee, la decision non.
            AdminActionDTO action = new AdminActionDTO("Fraude caracterisee", null);
            when(adminService.banUser(compteId, action)).thenReturn(Mono.just(compte));

            StepVerifier.create(controller.banUser(compteId, action))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(adminService).banUser(compteId, action);
            verify(adminService, never()).blockUser(any(), any());
        }

        @Test
        @DisplayName("suspend transmet l'échéance")
        void suspension() {
            LocalDateTime echeance = LocalDateTime.now().plusDays(7);
            AdminActionDTO action = new AdminActionDTO("Comportement suspect", echeance);
            when(adminService.suspendUser(compteId, action)).thenReturn(Mono.just(compte));

            StepVerifier.create(controller.suspendUser(compteId, action))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(adminService).suspendUser(eq(compteId),
                    org.mockito.ArgumentMatchers.argThat(a -> a.until() != null));
        }

        @Test
        @DisplayName("revoke ne propage pas à auth-service : c'est closeAccount qui le fait")
        void revocation() {
            when(adminService.revokeUser(compteId)).thenReturn(Mono.just(compte));

            StepVerifier.create(controller.revokeUser(compteId))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(adminService).revokeUser(compteId);
            verify(adminService, never()).closeAccount(any());
        }

        @Test
        @DisplayName("restore remplace unblock, unban et unsuspend")
        void retablissement() {
            when(adminService.restoreUser(compteId)).thenReturn(Mono.just(compte));

            StepVerifier.create(controller.restoreUser(compteId))
                    .assertNext(reponse ->
                            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK))
                    .verifyComplete();

            verify(adminService).restoreUser(compteId);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Fermeture de compte")
    class Fermeture {

        @Test
        @DisplayName("DELETE rend 204 sans corps")
        void fermeture() {
            when(adminService.closeAccount(compteId)).thenReturn(Mono.empty());

            StepVerifier.create(controller.closeAccount(compteId))
                    .assertNext(reponse -> {
                        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
                        assertThat(reponse.getBody()).isNull();
                    })
                    .verifyComplete();

            verify(adminService).closeAccount(compteId);
        }

        @Test
        @DisplayName("DELETE passe par closeAccount, et non par l'effacement du service client")
        void fermetureNePasseParDeleteUser() {
            // L'ancien endpoint supprimait la LIGNE. Celui-ci revoque et
            // propage : la ligne reste, et le journal d'audit designe un compte
            // qui existe encore.
            when(adminService.closeAccount(compteId)).thenReturn(Mono.empty());

            StepVerifier.create(controller.closeAccount(compteId)).expectNextCount(1)
                    .verifyComplete();

            verifyNoInteractions(userService);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Listes et consultation")
    class Consultation {

        @Test
        @DisplayName("la pagination est traduite en limite et décalage")
        void paginationTraduite() {
            when(userService.getAllUsers(20, 40)).thenReturn(Flux.just(compte));

            ResponseEntity<Flux<UserDetailDTO>> reponse = controller.getAllUsers(2, 20);

            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            StepVerifier.create(reponse.getBody()).expectNext(compte).verifyComplete();
            // Page 2 de 20 commence au 40e : c'est le service qui recoit
            // l'offset, et c'est lui qui le passe en SQL.
            verify(userService).getAllUsers(20, 40);
        }

        @Test
        @DisplayName("la recherche passe le terme tel quel : l'échappement est côté service")
        void rechercheTransmise() {
            when(userService.searchUsers("bamako", 20, 0)).thenReturn(Flux.just(compte));

            StepVerifier.create(controller.searchUsers("bamako", 0, 20).getBody())
                    .expectNext(compte)
                    .verifyComplete();

            verify(userService).searchUsers("bamako", 20, 0);
        }

        @Test
        @DisplayName("un statut inconnu rend 400, et non 500")
        void statutInconnu() {
            // `UserStatus.valueOf` levait une IllegalArgumentException : une
            // faute de frappe dans l'URL passait pour une panne du service.
            ResponseEntity<Flux<UserDetailDTO>> reponse =
                    controller.getUsersByStatus("ACTIVF", 0, 20);

            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(userService);
        }

        @Test
        @DisplayName("un statut est reconnu quelle que soit sa casse")
        void statutInsensibleALaCasse() {
            when(userService.getUsersByStatus(UserStatus.BLOCKED, 20, 0))
                    .thenReturn(Flux.just(compte));

            ResponseEntity<Flux<UserDetailDTO>> reponse =
                    controller.getUsersByStatus("blocked", 0, 20);

            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(userService).getUsersByStatus(UserStatus.BLOCKED, 20, 0);
        }

        @Test
        @DisplayName("la consultation d'un compte rend 200")
        void consultation() {
            when(userService.getUserById(compteId)).thenReturn(Mono.just(compte));

            StepVerifier.create(controller.getUserById(compteId))
                    .assertNext(reponse -> {
                        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(reponse.getBody()).isEqualTo(compte);
                    })
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Modification par un administrateur")
    class Modification {

        @Test
        @DisplayName("PUT délègue au service d'administration, pas au service client")
        void modification() {
            AdminUserUpdateDTO dto = new AdminUserUpdateDTO(
                    "user@example.com", "+22300000000", "ML", null,
                    "bio modifiee", "456 rue", "Segou", "Mali", "69001",
                    null, null, null);
            when(adminService.updateUserByAdmin(compteId, dto)).thenReturn(Mono.just(compte));

            StepVerifier.create(controller.updateUser(compteId, dto))
                    .assertNext(reponse ->
                            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK))
                    .verifyComplete();

            verify(adminService).updateUserByAdmin(compteId, dto);
            // Il existait deux implementations de la modification par
            // administrateur, sous deux prefixes differents. Il n'en reste
            // qu'une, et c'est celle du service d'administration.
            verifyNoInteractions(userService);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Journal d'audit")
    class Journal {

        private AuditLogDTO entree;

        @BeforeEach
        void setUp() {
            entree = AuditLogDTO.builder()
                    .id(UUID.randomUUID())
                    .userId(compteId)
                    .adminId("admin-7")
                    .actionType(ActionType.DELETE)
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        /**
         * Le journal est consultable, et c'était le manque.
         *
         * <p>Les décisions d'administration étaient consignées depuis l'origine
         * et <b>aucun endpoint ne les exposait</b> : les quatre méthodes de
         * lecture du service n'étaient appelées par personne. Un journal d'audit
         * existe pour répondre à « qui a décidé quoi, quand » ; celui-ci
         * accumulait la réponse sans que la question puisse être posée.
         */
        @Test
        @DisplayName("le journal d'un compte est consultable")
        void journalDunCompte() {
            when(auditLogService.journalDuCompte(compteId, 20, 0))
                    .thenReturn(Flux.just(entree));

            ResponseEntity<Flux<AuditLogDTO>> reponse =
                    controller.getAuditLogsOfUser(compteId, 0, 20);

            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            StepVerifier.create(reponse.getBody()).expectNext(entree).verifyComplete();
            verify(auditLogService).journalDuCompte(compteId, 20, 0);
        }

        @Test
        @DisplayName("la pagination du journal est traduite en limite et décalage")
        void paginationDuJournal() {
            when(auditLogService.journalDuCompte(compteId, 50, 100))
                    .thenReturn(Flux.just(entree));

            controller.getAuditLogsOfUser(compteId, 2, 50).getBody().blockLast();

            // Page 2 de 50 commence au 100e. La pagination est en SQL : la
            // version precedente du service chargeait TOUT l'historique puis
            // triait en memoire.
            verify(auditLogService).journalDuCompte(compteId, 50, 100);
        }

        @Test
        @DisplayName("le filtre par administrateur l'emporte sur celui par nature")
        void filtreParAdministrateur() {
            when(auditLogService.journalDunAdministrateur("admin-7", 20, 0))
                    .thenReturn(Flux.just(entree));

            controller.getAuditLogs("admin-7", "DELETE", 0, 20).getBody().blockLast();

            verify(auditLogService).journalDunAdministrateur("admin-7", 20, 0);
            verify(auditLogService, never()).journalComplet(any(), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt());
        }

        @Test
        @DisplayName("sans filtre, le journal complet est rendu")
        void journalComplet() {
            when(auditLogService.journalComplet(null, 20, 0)).thenReturn(Flux.just(entree));

            controller.getAuditLogs(null, null, 0, 20).getBody().blockLast();

            verify(auditLogService).journalComplet(null, 20, 0);
        }

        @Test
        @DisplayName("une nature de décision inconnue rend 400, et non 500")
        void natureInconnue() {
            ResponseEntity<Flux<AuditLogDTO>> reponse =
                    controller.getAuditLogs(null, "SUPPRIMER", 0, 20);

            assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("une nature est reconnue quelle que soit sa casse")
        void natureInsensibleALaCasse() {
            when(auditLogService.journalComplet(ActionType.DELETE, 20, 0))
                    .thenReturn(Flux.just(entree));

            controller.getAuditLogs(null, "delete", 0, 20).getBody().blockLast();

            verify(auditLogService).journalComplet(ActionType.DELETE, 20, 0);
        }
    }
}
