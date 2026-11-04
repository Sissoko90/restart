package com.abdatytch.user_service.erasure;

import com.abdatytch.user_service.entities.AuditLog;
import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.repository.AuditLogRepository;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Effacement des données de profil (RGPD art. 17).
 *
 * <p>Le point le plus facile à manquer se trouve ici : <b>la photographie sur le
 * disque</b>. Effacer l'URL en base sans supprimer le fichier laisse une photo
 * d'identité accessible à qui connaît le chemin. La donnée n'étant plus visible
 * dans l'application, on la croit partie.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProfileErasureService — effacement du profil")
class ProfileErasureServiceTest {

    private static final String PORTEUR = "auth-user-42";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String PHOTO = "/files/auth-user-42/photo.jpg";

    @Mock private UserRepository userRepository;
    @Mock private UserProfileRepository profileRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private CacheDesComptes cache;

    private ProfileErasureService service;
    private User utilisateur;
    private UserProfile profil;

    @BeforeEach
    void setUp() {
        service = new ProfileErasureService(userRepository, profileRepository,
                auditLogRepository, fileStorageService, cache);
        when(cache.invaliderPuisRendre(any(), any(), any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(2)));

        utilisateur = new User();
        utilisateur.setId(USER_ID);
        utilisateur.setAuthUserId(PORTEUR);
        utilisateur.setDateOfBirth(LocalDate.of(1995, 3, 12));
        utilisateur.setProfilePictureUrl(PHOTO);
        utilisateur.setStatus(UserStatus.ACTIVE);
        utilisateur.setBlockedReason("motif interne");

        profil = new UserProfile();
        profil.setUserId(USER_ID);
        profil.setBio("Commerçante à Bamako");
        profil.setAddress("Rue 12, Badalabougou");
        profil.setCity("Bamako");
        profil.setCountry("Mali");
        profil.setPostalCode("BP1");
        Map<String, String> preferences = new HashMap<>();
        preferences.put("contact", "awa.traore@restart.test");
        profil.setPreferences(preferences);

        when(userRepository.findByAuthUserId(PORTEUR)).thenReturn(Mono.just(utilisateur));
        when(userRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(profileRepository.findByUserId(USER_ID)).thenReturn(Mono.just(profil));
        when(profileRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(auditLogRepository.anonymiserLeDetailParUserId(USER_ID)).thenReturn(Mono.just(0));
    }

    @Test
    @DisplayName("la photographie est supprimée DU DISQUE, pas seulement son URL")
    void photographieSupprimeeDuDisque() {
        StepVerifier.create(service.erase(PORTEUR)).expectNextCount(1).verifyComplete();

        verify(fileStorageService)
                .deleteProfilePicture(PHOTO);

        ArgumentCaptor<User> capture = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(capture.capture());
        assertThat(capture.getValue().getProfilePictureUrl())
                .withFailMessage("l'URL de la photographie subsiste en base")
                .isNull();
    }

    @Test
    @DisplayName("la date de naissance est effacée")
    void dateDeNaissanceEffacee() {
        StepVerifier.create(service.erase(PORTEUR)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<User> capture = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(capture.capture());

        assertThat(capture.getValue().getDateOfBirth()).isNull();
        assertThat(capture.getValue().getBlockedReason()).isNull();
        assertThat(capture.getValue().getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("biographie, adresse, ville, pays et code postal sont effacés")
    void profilVide() {
        StepVerifier.create(service.erase(PORTEUR)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<UserProfile> capture = ArgumentCaptor.forClass(UserProfile.class);
        verify(profileRepository).save(capture.capture());
        UserProfile apres = capture.getValue();

        assertThat(apres.getBio()).isNull();
        assertThat(apres.getAddress())
                .withFailMessage("l'adresse postale subsiste")
                .isNull();
        assertThat(apres.getCity()).isNull();
        assertThat(apres.getCountry()).isNull();
        assertThat(apres.getPostalCode()).isNull();
    }

    @Test
    @DisplayName("les préférences sont vidées entièrement")
    void preferencesVidees() {
        // Elles acceptent des valeurs saisies librement : ici un courriel s'y
        // était glissé. Tenter d'y faire le tri reviendrait à deviner ce qui est
        // personnel et ce qui ne l'est pas.
        StepVerifier.create(service.erase(PORTEUR)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<UserProfile> capture = ArgumentCaptor.forClass(UserProfile.class);
        verify(profileRepository).save(capture.capture());

        assertThat(capture.getValue().getPreferences())
                .withFailMessage("les préférences subsistent et peuvent contenir "
                        + "n'importe quelle donnée saisie librement")
                .isEmpty();
    }

    /**
     * Le détail des journaux est retiré, les lignes conservées.
     *
     * <p>L'anonymisation se fait en <b>une</b> requête {@code UPDATE}, et non
     * par une lecture suivie d'un enregistrement ligne à ligne : le journal d'un
     * compte ancien en compte beaucoup, et un effacement RGPD a un délai légal à
     * respecter. Ce test vérifie donc la requête et son décompte, et surtout
     * qu'aucune ligne n'est <b>supprimée</b> — « effacer n'est pas supprimer la
     * ligne » vaut au premier chef pour le journal, dont la raison d'être est de
     * survivre au compte.
     */
    @Test
    @DisplayName("le détail des journaux d'audit est retiré, les lignes conservées")
    void journauxAnonymises() {
        when(auditLogRepository.anonymiserLeDetailParUserId(USER_ID)).thenReturn(Mono.just(3));

        StepVerifier.create(service.erase(PORTEUR))
                // 1 compte + 1 profil + 3 entrees de journal
                .expectNext(5)
                .verifyComplete();

        verify(auditLogRepository).anonymiserLeDetailParUserId(USER_ID);
        verify(auditLogRepository, never()).delete(any());
        verify(auditLogRepository, never()).deleteAll(org.mockito.ArgumentMatchers.anyIterable());
    }

    @Test
    @DisplayName("un journal déjà anonymisé rend 0, et non une erreur")
    void secondEffacementSansEntree() {
        // La requete porte `action_details IS NOT NULL` : un second effacement
        // ne reecrit rien, et le decompte dit la verite.
        when(auditLogRepository.anonymiserLeDetailParUserId(USER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(2)
                .verifyComplete();
    }

    @Test
    @DisplayName("un échec de suppression du fichier n'interrompt pas l'effacement en base")
    void echecFichierTolere() {
        // Mieux vaut effacer la base et signaler le fichier restant que de tout
        // laisser en place. L'incident est journalisé pour reprise manuelle.
        doThrow(new RuntimeException("disque en lecture seule"))
                .when(fileStorageService).deleteProfilePicture(PHOTO);

        StepVerifier.create(service.erase(PORTEUR)).expectNextCount(1).verifyComplete();

        verify(profileRepository).save(any(UserProfile.class));
    }

    @Test
    @DisplayName("une personne inconnue donne zéro, pas une erreur")
    void personneInconnue() {
        when(userRepository.findByAuthUserId(PORTEUR)).thenReturn(Mono.empty());

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    @DisplayName("une personne sans photographie ne provoque aucune suppression de fichier")
    void sansPhotographie() {
        utilisateur.setProfilePictureUrl(null);

        StepVerifier.create(service.erase(PORTEUR)).expectNextCount(1).verifyComplete();

        verify(fileStorageService, never()).deleteProfilePicture(any());
    }
}
