package com.abdatytch.user_service.service;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.impl.UserProfileUpdateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le retrait de la photo de profil.
 *
 * <h2>Le manque que cela comble</h2>
 *
 * On pouvait envoyer une photo et <b>jamais la retirer</b> : aucun verbe ne
 * l'exposait. La seule façon de s'en débarrasser était d'en envoyer une autre —
 * et l'ancienne restait sur le disque.
 *
 * <h2>Trois emplacements, comme l'effacement RGPD</h2>
 *
 * La colonne, le fichier, l'entrée de cache. Effacer l'URL sans supprimer le
 * fichier laisse une photographie d'identité accessible à qui connaît le chemin,
 * et c'est l'oubli le plus courant : la donnée n'est plus en base, donc on la
 * croit partie.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserProfileUpdateServiceImpl — retrait de la photo de profil")
class UserProfileUpdateServiceImplTest {

    private static final String URL = "http://passerelle/api/v1/files/auth-123/photo.jpg";
    private static final String AUTH_USER_ID = "auth-123";

    @Mock
    private UserRepository userRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private CacheDesComptes cache;

    @InjectMocks
    private UserProfileUpdateServiceImpl service;

    private UUID compteId;
    private User compte;

    @BeforeEach
    void setUp() {
        compteId = UUID.randomUUID();
        compte = new User();
        compte.setId(compteId);
        compte.setAuthUserId(AUTH_USER_ID);
        compte.setProfilePictureUrl(URL);

        when(userRepository.findById(compteId)).thenReturn(Mono.just(compte));
        when(userRepository.save(any(User.class)))
                .thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(cache.invalider(any(), any())).thenReturn(Mono.empty());
    }

    /**
     * Le fichier est supprimé, pas seulement l'URL.
     *
     * <p>C'est l'assertion qui compte : une version qui se contenterait de vider
     * la colonne passerait tous les autres tests et laisserait la photographie
     * accessible.
     */
    @Test
    @DisplayName("l'URL est effacee ET le fichier supprime du disque")
    void urlEtFichier() {
        StepVerifier.create(service.supprimerPhotoDeProfil(compteId)).verifyComplete();

        assertThat(compte.getProfilePictureUrl())
                .withFailMessage("l'URL subsiste en base")
                .isNull();
        verify(fileStorageService).deleteProfilePicture(URL);
    }

    @Test
    @DisplayName("les DEUX cles de cache sont invalidees")
    void cacheInvalide() {
        StepVerifier.create(service.supprimerPhotoDeProfil(compteId)).verifyComplete();

        // Sans cela, GET /users/me/profile-picture — qui passe par la cle
        // indexee par authUserId — continuerait de rendre l'ancienne URL pendant
        // le quart d'heure de retention.
        verify(cache).invalider(eq(compteId), eq(AUTH_USER_ID));
    }

    @Test
    @DisplayName("un compte sans photo rend un succes, sans rien toucher")
    void sansPhoto() {
        compte.setProfilePictureUrl(null);

        StepVerifier.create(service.supprimerPhotoDeProfil(compteId)).verifyComplete();

        // Idempotent : refuser « il n'y en avait pas » n'apprend rien a
        // l'appelant qui voulait justement qu'il n'y en ait plus.
        verify(userRepository, never()).save(any(User.class));
        verify(fileStorageService, never()).deleteProfilePicture(any());
    }

    /**
     * Un échec de suppression du fichier n'interrompt pas l'opération.
     *
     * <p>La base est déjà à jour quand il survient. Mieux vaut un fichier
     * orphelin signalé qu'un retrait à moitié fait, dont l'appelant ne saurait
     * pas s'il doit réessayer.
     */
    @Test
    @DisplayName("un echec de suppression du fichier n'interrompt pas le retrait")
    void echecFichierTolere() {
        doThrow(new RuntimeException("disque en lecture seule"))
                .when(fileStorageService).deleteProfilePicture(URL);

        StepVerifier.create(service.supprimerPhotoDeProfil(compteId)).verifyComplete();

        assertThat(compte.getProfilePictureUrl()).isNull();
        verify(cache).invalider(eq(compteId), eq(AUTH_USER_ID));
    }

    @Test
    @DisplayName("un compte introuvable est refuse")
    void compteIntrouvable() {
        UUID inconnu = UUID.randomUUID();
        when(userRepository.findById(inconnu)).thenReturn(Mono.empty());

        StepVerifier.create(service.supprimerPhotoDeProfil(inconnu))
                .expectError(ResourceNotFoundException.class)
                .verify();
    }
}
