package com.abdatytch.user_service.service;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.event.OAuth2ProfileCompletedEvent;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.impl.UserProfileSyncServiceImpl;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La synchronisation du profil depuis les événements d'auth-service.
 *
 * <h2>Ce que ce service répare</h2>
 *
 * Quand une personne s'inscrit par Google ou Facebook puis complète son profil —
 * numéro, date de naissance, adresse, biographie —, auth-service publie bien
 * l'information, et user-service ne l'enregistrait <b>jamais</b> : le listener
 * désérialisait tout en {@code UserCreatedEvent}, donc l'événement était lu
 * comme une création, le compte existait déjà, et le traitement s'arrêtait là.
 *
 * <h2>Les deux propriétés qui comptent</h2>
 *
 * <b>L'idempotence</b> : Kafka repropose un message dont le traitement a échoué,
 * et un rejeu ne doit produire aucune écriture ni faire avancer
 * {@code updated_at}.
 *
 * <p><b>Un champ nul ne remplace pas</b> : une complétion complète, elle
 * n'écrase pas. Sans cela, un événement partiel effacerait ce qu'un autre
 * chemin vient d'écrire.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserProfileSyncServiceImpl — completion de profil OAuth2")
class UserProfileSyncServiceImplTest {

    private static final String AUTH_USER_ID = "auth-123";

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserProfileRepository profileRepository;
    @Mock
    private CacheDesComptes cache;

    @InjectMocks
    private UserProfileSyncServiceImpl service;

    private UUID compteId;
    private User compte;
    private UserProfile profil;

    @BeforeEach
    void setUp() {
        compteId = UUID.randomUUID();

        compte = new User();
        compte.setId(compteId);
        compte.setAuthUserId(AUTH_USER_ID);

        profil = new UserProfile();
        profil.setUserId(compteId);

        when(userRepository.findByAuthUserId(AUTH_USER_ID)).thenReturn(Mono.just(compte));
        when(userRepository.save(any(User.class)))
                .thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(profileRepository.findByUserId(compteId)).thenReturn(Mono.just(profil));
        when(profileRepository.save(any(UserProfile.class)))
                .thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(cache.invaliderPuisRendre(any(), any(), any()))
                .thenAnswer(i -> Mono.just(i.getArgument(2)));
    }

    private static OAuth2ProfileCompletedEvent evenement() {
        return OAuth2ProfileCompletedEvent.builder()
                .authUserId(AUTH_USER_ID)
                .phoneNumber("+22370000000")
                .countryCode("ML")
                .dateOfBirth(LocalDate.of(1995, 3, 12))
                .address("Rue 12, Badalabougou")
                .bio("Commercante a Bamako")
                .build();
    }

    // ==================================================================
    @Nested
    @DisplayName("Ce qui est enregistre, et ce qui ne l'est pas")
    class CeQuiEstEnregistre {

        @Test
        @DisplayName("date de naissance et champs declaratifs sont ecrits")
        void champsLocauxEcrits() {
            StepVerifier.create(service.appliquerCompletionOAuth2(evenement()))
                    .verifyComplete();

            ArgumentCaptor<User> enregistre = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(enregistre.capture());
            assertThat(enregistre.getValue().getDateOfBirth())
                    .isEqualTo(LocalDate.of(1995, 3, 12));

            ArgumentCaptor<UserProfile> profilEnregistre =
                    ArgumentCaptor.forClass(UserProfile.class);
            verify(profileRepository).save(profilEnregistre.capture());
            assertThat(profilEnregistre.getValue().getAddress())
                    .isEqualTo("Rue 12, Badalabougou");
            assertThat(profilEnregistre.getValue().getBio())
                    .isEqualTo("Commercante a Bamako");
        }

        /**
         * Le numéro et l'indicatif ne sont <b>pas</b> recopiés.
         *
         * <p>Ils figurent dans l'événement, et auth-service en est la source de
         * vérité. Les enregistrer ici ferait un second magasin qui divergerait à
         * la première modification faite là-bas — le défaut que ce projet
         * connaît sous « deux magasins pour un même état ».
         */
        @Test
        @DisplayName("seul le lien vers auth-service est conserve sur la ligne")
        void identiteNonRecopiee() {
            StepVerifier.create(service.appliquerCompletionOAuth2(evenement()))
                    .verifyComplete();

            ArgumentCaptor<User> enregistre = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(enregistre.capture());
            assertThat(enregistre.getValue().getAuthUserId()).isEqualTo(AUTH_USER_ID);
        }

        /** La photo du fournisseur ne remplace pas celle que la personne a envoyée. */
        @Test
        @DisplayName("une photo deja presente n'est pas remplacee par celle du fournisseur")
        void photoExistantePreservee() {
            compte.setProfilePictureUrl("http://passerelle/api/v1/files/x/choisie.jpg");
            OAuth2ProfileCompletedEvent avecPhoto = OAuth2ProfileCompletedEvent.builder()
                    .authUserId(AUTH_USER_ID)
                    .profilePicture("http://google/photo.jpg")
                    .build();

            StepVerifier.create(service.appliquerCompletionOAuth2(avecPhoto))
                    .verifyComplete();

            assertThat(compte.getProfilePictureUrl())
                    .withFailMessage("la photo choisie par la personne a ete ecrasee "
                            + "par celle du fournisseur OAuth2")
                    .isEqualTo("http://passerelle/api/v1/files/x/choisie.jpg");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Idempotence : un rejeu n'ecrit rien")
    class Idempotence {

        @Test
        @DisplayName("un evenement rejoue ne declenche aucune ecriture")
        void rejeuSansEcriture() {
            StepVerifier.create(service.appliquerCompletionOAuth2(evenement()))
                    .verifyComplete();
            verify(userRepository).save(any(User.class));
            verify(profileRepository).save(any(UserProfile.class));

            // Second passage avec le MEME evenement : les valeurs sont deja
            // celles-la, donc rien a ecrire. Sans ce controle, Kafka reproposant
            // un message ferait avancer `updated_at` et invaliderait le cache
            // pour rien.
            org.mockito.Mockito.clearInvocations(userRepository, profileRepository);
            StepVerifier.create(service.appliquerCompletionOAuth2(evenement()))
                    .verifyComplete();

            verify(userRepository, never()).save(any(User.class));
            verify(profileRepository, never()).save(any(UserProfile.class));
        }

        @Test
        @DisplayName("un champ nul n'efface pas l'existant")
        void champNulNEffacePas() {
            profil.setBio("biographie deja ecrite");
            OAuth2ProfileCompletedEvent partiel = OAuth2ProfileCompletedEvent.builder()
                    .authUserId(AUTH_USER_ID)
                    .address("Rue 12")
                    .build();

            StepVerifier.create(service.appliquerCompletionOAuth2(partiel))
                    .verifyComplete();

            assertThat(profil.getBio())
                    .withFailMessage("une completion partielle a efface la biographie : "
                            + "elle doit completer, pas remplacer")
                    .isEqualTo("biographie deja ecrite");
            assertThat(profil.getAddress()).isEqualTo("Rue 12");
        }

        @Test
        @DisplayName("un evenement sans aucun champ local n'ecrit rien du tout")
        void evenementVide() {
            OAuth2ProfileCompletedEvent vide = OAuth2ProfileCompletedEvent.builder()
                    .authUserId(AUTH_USER_ID)
                    .phoneNumber("+22370000000")
                    .build();

            StepVerifier.create(service.appliquerCompletionOAuth2(vide))
                    .verifyComplete();

            verify(userRepository, never()).save(any(User.class));
            verify(profileRepository, never()).save(any(UserProfile.class));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Compte absent et cache")
    class CompteAbsentEtCache {

        /**
         * Un compte absent fait <b>échouer</b> le traitement.
         *
         * <p>Et non « passer en silence ». La clef Kafka étant l'{@code authUserId},
         * création et complétion vont dans la même partition et sont donc
         * ordonnées : le cas ne devrait pas se produire. S'il se produit quand
         * même, l'erreur fait que Kafka repropose — s'en accommoder perdrait le
         * profil que la personne vient de saisir.
         */
        @Test
        @DisplayName("un compte absent fait echouer, pour que Kafka repropose")
        void compteAbsentEchoue() {
            when(userRepository.findByAuthUserId(AUTH_USER_ID)).thenReturn(Mono.empty());

            StepVerifier.create(service.appliquerCompletionOAuth2(evenement()))
                    .expectError(ResourceNotFoundException.class)
                    .verify();

            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("le cache est invalide avec les DEUX identifiants du compte")
        void cacheInvalide() {
            StepVerifier.create(service.appliquerCompletionOAuth2(evenement()))
                    .verifyComplete();

            // La cle indexee par authUserId est celle que sert GET /users/me :
            // sans elle, la personne continuerait de voir son profil d'avant la
            // completion pendant tout le quart d'heure de retention.
            verify(cache).invaliderPuisRendre(eq(compteId), eq(AUTH_USER_ID), any());
        }
    }
}
