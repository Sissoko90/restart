package com.abdatytch.user_service.service;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.client.ClientIdentiteAuth;
import com.abdatytch.user_service.constants.AdminConstants;
import com.abdatytch.user_service.constants.AuditConstants;
import com.abdatytch.user_service.dto.request.AdminActionDTO;
import com.abdatytch.user_service.dto.request.AuditLogCreateDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.mapper.UserMapper;
import com.abdatytch.user_service.metrics.UserMetrics;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.impl.UserAdminServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La mécanique partagée des cinq décisions d'administration.
 *
 * <p>Ce service n'avait aucun test. Les huit décisions étaient recopiées à
 * vingt-cinq lignes l'exemplaire, et les divergences entre copies — celles que
 * ce fichier vérifie désormais — ne pouvaient donc se voir qu'en lisant les
 * huit méthodes côte à côte.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserAdminServiceImpl — décisions d'administration")
class UserAdminServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ClientIdentiteAuth clientIdentiteAuth;
    @Mock
    private UserMetrics userMetrics;
    @Mock
    private CacheDesComptes cache;

    @InjectMocks
    private UserAdminServiceImpl service;

    private UUID compteId;
    private User compte;

    @BeforeEach
    void setUp() {
        // Plus de `internalSecret` a injecter par reflexion : le secret est
        // desormais porte par ClientIdentiteAuth, qui le recoit par
        // constructeur. Le service ne le connait plus du tout.
        compteId = UUID.randomUUID();
        compte = new User();
        compte.setId(compteId);
        compte.setAuthUserId("auth-123");
        compte.setStatus(UserStatus.ACTIVE);

        when(userRepository.findById(compteId)).thenReturn(Mono.just(compte));
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(cache.invaliderPuisRendre(any(), any(), any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(2)));
        when(userMapper.toDetailDTO(any(User.class)))
                .thenAnswer(invocation -> Mono.just(detailDe(invocation.getArgument(0))));
        when(auditLogService.logAction(any(AuditLogCreateDTO.class))).thenReturn(Mono.empty());
        // `supprimer` rend un Mono : sans ce stub, Mockito rendrait null et la
        // chaine casserait sur un NullPointerException avant d'atteindre
        // l'assertion, ce qui ferait echouer le test pour la mauvaise raison.
        when(clientIdentiteAuth.supprimer(anyString())).thenReturn(Mono.empty());
    }

    private static UserDetailDTO detailDe(User user) {
        return new UserDetailDTO(
                user.getId(), user.getAuthUserId(), null, null, null, null, null,
                null, null, user.getStatus(), UserRole.USER,
                null, null, null, null, null, null, null, null, null);
    }

    // ==================================================================
    @Nested
    @DisplayName("Le rétablissement remet l'état à plat")
    class Retablissement {

        /**
         * Le correctif central de la réunion des trois réversions.
         *
         * <p>{@code unblockUser} effaçait {@code blockedReason} et
         * {@code blockedAt} sans toucher {@code suspendedUntil} ;
         * {@code unsuspendUser} faisait l'inverse. Un compte suspendu <i>puis</i>
         * bloqué, puis débloqué, redevenait ACTIVE en gardant une échéance de
         * suspension — un champ qui ne veut plus rien dire sur un compte actif,
         * et que la réponse d'API publiait.
         */
        @Test
        @DisplayName("un compte suspendu puis bloqué ne garde aucune échéance après rétablissement")
        void aucunResteApresRetablissement() {
            compte.setStatus(UserStatus.BLOCKED);
            compte.setBlockedReason("motif precedent");
            compte.setBlockedAt(LocalDateTime.now().minusDays(1));
            compte.setSuspendedUntil(LocalDateTime.now().plusDays(3));
            compte.setDeletedAt(LocalDateTime.now().minusDays(2));

            StepVerifier.create(service.restoreUser(compteId))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<User> enregistre = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(enregistre.capture());

            assertThat(enregistre.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(enregistre.getValue().getBlockedReason()).isNull();
            assertThat(enregistre.getValue().getBlockedAt()).isNull();
            assertThat(enregistre.getValue().getSuspendedUntil())
                    .withFailMessage("une echeance de suspension survit a un retablissement : "
                            + "c'est exactement ce que la separation des trois reversions "
                            + "laissait passer")
                    .isNull();
            assertThat(enregistre.getValue().getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("lever un bannissement reste possible, et compte comme un rétablissement")
        void bannissementLevable() {
            compte.setStatus(UserStatus.BANNED);

            StepVerifier.create(service.restoreUser(compteId))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(userMetrics).decisionPrise(AdminConstants.DECISION_RETABLISSEMENT);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les cinq décisions restent distinctes")
    class Distinctes {

        @Test
        @DisplayName("blocage et bannissement mènent à deux états différents")
        void blocageNestPasBannissement() {
            AdminActionDTO action = new AdminActionDTO("motif suffisamment long", null);

            StepVerifier.create(service.blockUser(compteId, action))
                    .assertNext(detail ->
                            assertThat(detail.status()).isEqualTo(UserStatus.BLOCKED))
                    .verifyComplete();

            compte.setStatus(UserStatus.ACTIVE);
            StepVerifier.create(service.banUser(compteId, action))
                    .assertNext(detail ->
                            assertThat(detail.status()).isEqualTo(UserStatus.BANNED))
                    .verifyComplete();
        }

        @Test
        @DisplayName("un blocage efface l'échéance d'une suspension précédente")
        void leBlocageEffaceLecheance() {
            // Sinon l'echeance resterait et rien ne l'appliquerait : le compte
            // porterait une date de levee qui ne concerne plus son etat.
            compte.setStatus(UserStatus.SUSPENDED);
            compte.setSuspendedUntil(LocalDateTime.now().plusDays(2));

            StepVerifier.create(service.blockUser(compteId,
                            new AdminActionDTO("motif suffisamment long", null)))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<User> enregistre = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(enregistre.capture());
            assertThat(enregistre.getValue().getSuspendedUntil()).isNull();
        }

        @Test
        @DisplayName("une suspension sans échéance est refusée avant toute écriture")
        void suspensionSansEcheanceRefusee() {
            // Sans echeance, une suspension est un blocage deguise dont rien ne
            // provoquera la levee. Le refus a lieu AVANT la lecture du compte.
            StepVerifier.create(service.suspendUser(compteId,
                            new AdminActionDTO("motif suffisamment long", null)))
                    .expectError(IllegalArgumentException.class)
                    .verify();

            verify(userRepository, never()).save(any(User.class));
            verify(userRepository, never()).findById(any(UUID.class));
        }

        @Test
        @DisplayName("la révocation conserve la ligne")
        void revocationConserveLaLigne() {
            StepVerifier.create(service.revokeUser(compteId))
                    .assertNext(detail ->
                            assertThat(detail.status()).isEqualTo(UserStatus.DELETED))
                    .verifyComplete();

            ArgumentCaptor<User> enregistre = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(enregistre.capture());
            assertThat(enregistre.getValue().getDeletedAt()).isNotNull();
            verify(userRepository, never()).delete(any(User.class));
        }

        @Test
        @DisplayName("chaque décision porte sa propre étiquette de métrique")
        void etiquetteParDecision() {
            StepVerifier.create(service.revokeUser(compteId)).expectNextCount(1).verifyComplete();
            verify(userMetrics).decisionPrise(AdminConstants.DECISION_REVOCATION);
            verify(userMetrics, never()).decisionPrise(AdminConstants.DECISION_BLOCAGE);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Mécanique commune")
    class Mecanique {

        @Test
        @DisplayName("un compte introuvable est refusé, sans écriture")
        void compteIntrouvable() {
            UUID inconnu = UUID.randomUUID();
            when(userRepository.findById(inconnu)).thenReturn(Mono.empty());

            StepVerifier.create(service.restoreUser(inconnu))
                    .expectError(ResourceNotFoundException.class)
                    .verify();

            verify(userRepository, never()).save(any(User.class));
        }

        /**
         * L'invalidation passe par le détenteur unique du cache.
         *
         * <p>Les deux clés — {@code user:<uuid>} et
         * {@code user:auth_<authUserId>} — sont la responsabilité de
         * {@code CacheDesComptes}, et {@code CacheDesComptesTest} vérifie
         * qu'elles sont bien toutes deux effacées. Ce qui se vérifie <b>ici</b>,
         * c'est que la décision la sollicite, et avec le bon identifiant
         * d'authentification : la passer à {@code null} effacerait la clé par
         * identifiant et laisserait l'autre — celle que sert
         * {@code GET /users/me} — servir l'état d'avant la décision.
         */
        @Test
        @DisplayName("la décision invalide le cache des DEUX identifiants du compte")
        void leCacheEstInvalide() {
            StepVerifier.create(service.revokeUser(compteId))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(cache).invaliderPuisRendre(
                    org.mockito.ArgumentMatchers.eq(compteId),
                    org.mockito.ArgumentMatchers.eq("auth-123"),
                    any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Fermeture de compte")
    class Fermeture {

        @Test
        @DisplayName("la fermeture révoque, propage à auth-service, et garde la ligne")
        void fermetureComplete() {
            StepVerifier.create(service.closeAccount(compteId))
                    .verifyComplete();

            ArgumentCaptor<User> enregistre = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(enregistre.capture());
            assertThat(enregistre.getValue().getStatus()).isEqualTo(UserStatus.DELETED);

            // La ligne RESTE : l'ancienne version appelait delete(user), et le
            // journal d'audit ecrit juste apres designait un compte dont il ne
            // restait aucune trace.
            verify(userRepository, never()).delete(any(User.class));
            verify(clientIdentiteAuth).supprimer("auth-123");
        }

        /**
         * L'auteur consigné n'est pas le compte fermé.
         *
         * <p>L'ancien repli était
         * {@code .defaultIfEmpty(userId.toString())} : hors contexte de
         * sécurité, le journal attribuait la fermeture au compte qu'elle
         * fermait. Un journal d'audit qui désigne la victime comme auteur est
         * pire que muet, parce qu'il a l'air de répondre.
         */
        @Test
        @DisplayName("sans contexte de sécurité, l'auteur est INCONNU et non le compte fermé")
        void auteurInconnuPlutotQueLaVictime() {
            StepVerifier.create(service.closeAccount(compteId)).verifyComplete();

            ArgumentCaptor<AuditLogCreateDTO> journal =
                    ArgumentCaptor.forClass(AuditLogCreateDTO.class);
            verify(auditLogService).logAction(journal.capture());

            assertThat(journal.getValue().getAdminId())
                    .isEqualTo(AuditConstants.ADMIN_INCONNU)
                    .isNotEqualTo(compteId.toString());
            assertThat(journal.getValue().getStatus())
                    .isEqualTo(AuditConstants.STATUS_SUCCESS);
        }

        @Test
        @DisplayName("avec un contexte, l'auteur est l'administrateur")
        void auteurReleve() {
            StepVerifier.create(service.closeAccount(compteId)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                    new UsernamePasswordAuthenticationToken(
                                            "admin-7", null,
                                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))))
                    .verifyComplete();

            ArgumentCaptor<AuditLogCreateDTO> journal =
                    ArgumentCaptor.forClass(AuditLogCreateDTO.class);
            verify(auditLogService).logAction(journal.capture());
            assertThat(journal.getValue().getAdminId()).isEqualTo("admin-7");
        }

        @Test
        @DisplayName("un échec de propagation est consigné puis propagé")
        void echecDePropagationConsigne() {
            // `supprimer` rend un Mono<Void> : l'echec se declare par un
            // Mono.error, et non par une levee. Un doThrow ferait lever a
            // l'ASSEMBLAGE de la chaine, pas a l'abonnement — ce qui ne
            // ressemble a aucune panne reelle de reseau.
            when(clientIdentiteAuth.supprimer(anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("auth-service indisponible")));

            StepVerifier.create(service.closeAccount(compteId))
                    .expectError(IllegalStateException.class)
                    .verify();

            ArgumentCaptor<AuditLogCreateDTO> journal =
                    ArgumentCaptor.forClass(AuditLogCreateDTO.class);
            verify(auditLogService).logAction(journal.capture());
            assertThat(journal.getValue().getStatus())
                    .isEqualTo(AuditConstants.STATUS_FAILED);
            assertThat(journal.getValue().getErrorMessage()).isNotBlank();
        }

        @Test
        @DisplayName("un journal d'audit en panne ne fait pas échouer une fermeture réussie")
        void journalEnPanneNeMasquePasLeResultat() {
            when(auditLogService.logAction(any(AuditLogCreateDTO.class)))
                    .thenReturn(Mono.error(new IllegalStateException("table indisponible")));

            StepVerifier.create(service.closeAccount(compteId))
                    .verifyComplete();

            verify(clientIdentiteAuth).supprimer("auth-123");
        }
    }
}
