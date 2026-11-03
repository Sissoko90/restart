package com.abdatytch.user_service.listener;

import com.abdatytch.user_service.dto.request.UserCreationDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.dto.response.UserResponseDTO;
import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.event.OAuth2ProfileCompletedEvent;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.metrics.UserMetrics;
import com.abdatytch.user_service.service.UserProfileSyncService;
import com.abdatytch.user_service.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L'aiguillage des événements du topic {@code user-events}.
 *
 * <h2>Pourquoi ce fichier a été réécrit</h2>
 *
 * La version précédente ne testait <b>pas</b> le listener. Ses deux tests
 * s'appelaient « Service - Doit créer un utilisateur si n'existe pas » et
 * appelaient {@code userService.createUser(...)} — le simulacre — puis
 * vérifiaient que le simulacre avait été appelé. Elles n'auraient pas échoué si
 * ce listener n'avait pas existé.
 *
 * <h2>Ce qui est couvert maintenant</h2>
 *
 * auth-service publie <b>trois</b> types sur le même topic, avec la même clef et
 * des charges utiles qui ne diffèrent que par la présence de champs. Le listener
 * désérialisait tout en {@code UserCreatedEvent} : la complétion de profil
 * OAuth2 était donc lue comme une création, le compte existait déjà, le
 * traitement s'arrêtait là — et le profil saisi après une inscription Google
 * n'arrivait jamais.
 *
 * <p>Ces tests fixent l'aiguillage par en-tête, l'enregistrement du compte à
 * partir de l'identifiant publié, et le fait qu'un type inconnu ne casse rien.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserEventListener — aiguillage des evenements de compte")
class UserEventListenerTest {

    private static final String AUTH_USER_ID = "auth-123";

    @Mock
    private UserService userService;
    @Mock
    private UserProfileSyncService profileSyncService;
    @Mock
    private UserMetrics userMetrics;

    private UserEventListener listener;

    @BeforeEach
    void setUp() {
        // `JavaTimeModule` est indispensable, et c'est une fidelite au
        // montage reel, pas une commodite de test : `OAuth2ProfileCompletedEvent`
        // porte un `LocalDate`, et un ObjectMapper nu ne sait pas le lire.
        //
        // Spring Boot enregistre ce module par auto-configuration, donc la
        // production va bien. Mais la consequence du contraire merite d'etre
        // connue : la completion OAuth2 deviendrait « illisible », donc
        // acquittee et ABANDONNEE — le profil de la personne serait perdu en
        // silence, et seul le compteur d'echecs Kafka le dirait.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        listener = new UserEventListener(userService, profileSyncService,
                Map.of(), mapper, userMetrics);
    }

    // ==================================================================
    @Nested
    @DisplayName("Création de compte : l'identifiant publié est enregistré")
    class CompteCree {

        private static final String CHARGE = """
                {"authUserId":"auth-123","email":"a@b.c","phoneNumber":"+22300000000",
                 "firstName":"Awa","lastName":"Traore","eventId":"evt-1"}""";

        /**
         * Le contrat central entre les deux services : auth-service crée
         * l'identité et publie son identifiant, user-service crée la ligne.
         */
        @Test
        @DisplayName("un compte absent est créé depuis l'identifiant publié")
        void compteAbsentEstCree() {
            when(userService.getUserByAuthUserId(AUTH_USER_ID))
                    .thenReturn(Mono.error(new ResourceNotFoundException("absent")));
            when(userService.createUser(any(UserCreationDTO.class)))
                    .thenReturn(Mono.just(reponse()));

            StepVerifier.create(listener.aiguiller(
                            UserEventListener.TYPE_COMPTE_CREE, CHARGE))
                    .verifyComplete();

            ArgumentCaptor<UserCreationDTO> cree =
                    ArgumentCaptor.forClass(UserCreationDTO.class);
            verify(userService).createUser(cree.capture());
            assertThat(cree.getValue().authUserId()).isEqualTo(AUTH_USER_ID);
        }

        /**
         * Seul l'identifiant est repris, et c'est délibéré.
         *
         * <p>L'événement porte aussi courriel, numéro, nom et rôle. Les recopier
         * ferait un second magasin pour un état qu'auth-service détient, qui
         * divergerait à la première modification faite là-bas.
         */
        @Test
        @DisplayName("seul l'identifiant est repris, pas l'identité publiée avec")
        void seulLidentifiantEstRepris() {
            when(userService.getUserByAuthUserId(AUTH_USER_ID))
                    .thenReturn(Mono.error(new ResourceNotFoundException("absent")));
            when(userService.createUser(any(UserCreationDTO.class)))
                    .thenReturn(Mono.just(reponse()));

            StepVerifier.create(listener.aiguiller(
                            UserEventListener.TYPE_COMPTE_CREE, CHARGE)).verifyComplete();

            ArgumentCaptor<UserCreationDTO> cree =
                    ArgumentCaptor.forClass(UserCreationDTO.class);
            verify(userService).createUser(cree.capture());
            assertThat(cree.getValue().email())
                    .withFailMessage("le courriel publie a ete recopie dans user-service : "
                            + "auth-service en est la source de verite")
                    .isNull();
            assertThat(cree.getValue().firstName()).isNull();
            assertThat(cree.getValue().role()).isNull();
        }

        /** Idempotence : un événement rejoué ne doit pas créer de doublon. */
        @Test
        @DisplayName("un compte déjà présent n'est pas recréé")
        void compteDejaPresent() {
            when(userService.getUserByAuthUserId(AUTH_USER_ID))
                    .thenReturn(Mono.just(detail()));

            StepVerifier.create(listener.aiguiller(
                            UserEventListener.TYPE_COMPTE_CREE, CHARGE)).verifyComplete();

            verify(userService, never()).createUser(any());
        }

        @Test
        @DisplayName("une création sans authUserId est refusée, et ne tente rien")
        void sansIdentifiant() {
            StepVerifier.create(listener.aiguiller(UserEventListener.TYPE_COMPTE_CREE,
                            "{\"eventId\":\"evt-1\"}"))
                    .expectError()
                    .verify();

            verify(userService, never()).createUser(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Complétion de profil OAuth2")
    class ProfilOAuth2 {

        private static final String CHARGE = """
                {"authUserId":"auth-123","phoneNumber":"+22370000000","countryCode":"ML",
                 "dateOfBirth":"1995-03-12","address":"Rue 12","bio":"Commercante",
                 "eventId":"evt-2"}""";

        /**
         * Le défaut que cet aiguillage ferme.
         *
         * <p>Sans discrimination par en-tête, cette charge utile était lue comme
         * une création : le compte existait déjà, le traitement s'arrêtait, et le
         * profil que la personne venait de saisir n'arrivait <b>jamais</b>.
         */
        @Test
        @DisplayName("la complétion va au service de synchronisation, pas à la création")
        void vaAuServiceDeSync() {
            when(profileSyncService.appliquerCompletionOAuth2(
                    any(OAuth2ProfileCompletedEvent.class))).thenReturn(Mono.empty());

            StepVerifier.create(listener.aiguiller(
                            UserEventListener.TYPE_PROFIL_OAUTH2_COMPLETE, CHARGE))
                    .verifyComplete();

            ArgumentCaptor<OAuth2ProfileCompletedEvent> recu =
                    ArgumentCaptor.forClass(OAuth2ProfileCompletedEvent.class);
            verify(profileSyncService).appliquerCompletionOAuth2(recu.capture());
            assertThat(recu.getValue().getAuthUserId()).isEqualTo(AUTH_USER_ID);
            assertThat(recu.getValue().getBio()).isEqualTo("Commercante");

            verify(userService, never()).createUser(any());
        }

        @Test
        @DisplayName("un échec de synchronisation remonte, pour que Kafka repropose")
        void echecRemonte() {
            when(profileSyncService.appliquerCompletionOAuth2(any()))
                    .thenReturn(Mono.error(new ResourceNotFoundException("compte absent")));

            // L'erreur doit SORTIR de l'aiguillage : c'est `traiter` qui decide
            // de ne pas acquitter, donc de laisser Kafka represente le message.
            // L'avaler ici perdrait le profil saisi par la personne.
            StepVerifier.create(listener.aiguiller(
                            UserEventListener.TYPE_PROFIL_OAUTH2_COMPLETE, CHARGE))
                    .expectError(ResourceNotFoundException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les types sans effet local")
    class SansEffet {

        @Test
        @DisplayName("une connexion ne déclenche aucune écriture")
        void connexion() {
            StepVerifier.create(listener.aiguiller(UserEventListener.TYPE_CONNEXION,
                            "{\"authUserId\":\"auth-123\",\"eventId\":\"evt-3\"}"))
                    .verifyComplete();

            // La date de derniere connexion appartient a auth-service. Le
            // traitement vide est VOULU, et distinct d'une absence de
            // traitement : la methode privee precedente n'etait jamais appelee.
            verifyNoInteractions(userService, profileSyncService);
        }

        /**
         * Un type inconnu est accepté sans erreur.
         *
         * <p>Le refuser bloquerait la partition sur un message qui appartient
         * probablement à un consommateur ajouté depuis. Un topic partagé porte
         * normalement des messages qui ne nous concernent pas.
         */
        @Test
        @DisplayName("un type inconnu ne casse rien et ne déclenche rien")
        void typeInconnu() {
            StepVerifier.create(listener.aiguiller("user.quelque-chose-de-neuf",
                            "{\"peu\":\"importe\"}"))
                    .verifyComplete();

            verifyNoInteractions(userService, profileSyncService);
        }

        /**
         * En-tête absent : traité comme un type inconnu, pas comme une création.
         *
         * <p>C'est exactement l'ancien comportement par défaut — tout était lu
         * comme un {@code UserCreatedEvent} — et c'est ce qu'il ne faut plus
         * faire. Un message sans en-tête vient d'un producteur qui ne respecte
         * pas le contrat ; le deviner serait reprendre le défaut.
         */
        @Test
        @DisplayName("un en-tête absent n'est PAS deviné comme une création")
        void enTeteAbsentNestPasDevine() {
            StepVerifier.create(listener.aiguiller(null,
                            "{\"authUserId\":\"auth-123\",\"eventId\":\"evt-4\"}"))
                    .verifyComplete();

            verify(userService, never()).createUser(any());
            verify(userService, never()).getUserByAuthUserId(any());
        }
    }

    // ==================================================================
    @Test
    @DisplayName("une charge illisible devient une erreur du flux, pas une levee a l'assemblage")
    void chargeIllisible() {
        // `Mono.defer` est necessaire dans l'aiguillage : sans lui, la levee de
        // `lire(...)` remonterait a l'appelant au lieu de devenir un signal du
        // flux — donc hors de portee du `onErrorResume` qui decide d'acquitter.
        StepVerifier.create(listener.aiguiller(
                        UserEventListener.TYPE_COMPTE_CREE, "{ceci n'est pas du json"))
                .expectError()
                .verify();
    }

    private static UserResponseDTO reponse() {
        return new UserResponseDTO(UUID.randomUUID(), AUTH_USER_ID, null, null,
                null, null, UserStatus.ACTIVE, UserRole.USER,
                LocalDateTime.now(), null);
    }

    private static UserDetailDTO detail() {
        return new UserDetailDTO(
                UUID.randomUUID(), AUTH_USER_ID, null, null, null, null, null, null, null,
                UserStatus.ACTIVE, UserRole.USER, null, null, null, null, null, null,
                LocalDateTime.now(), null, null);
    }
}
