package com.abdatytch.user_service.service;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.dto.request.UserCreationDTO;
import com.abdatytch.user_service.dto.request.UserUpdateDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.dto.response.UserResponseDTO;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.exception.ResourceAlreadyExistsException;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.mapper.UserMapper;
import com.abdatytch.user_service.metrics.UserMetrics;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.impl.UserServiceReactiveImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires pour UserServiceReactiveImpl
 * Suit exactement l'implémentation réelle du service
 */
@ExtendWith(MockitoExtension.class)
// Les quatre stubs de `cache` sont poses dans le setUp commun, et chaque test
// n'en sollicite qu'une partie. En mode strict, Mockito signale les autres
// comme inutiles — ce qui dirait seulement que les tests ne font pas tous la
// meme chose.
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserServiceReactiveImpl - Tests Unitaires")
class UserServiceReactiveImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserProfileRepository profileRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private CacheDesComptes cache;

    @Mock
    private UserMetrics userMetrics;

    @InjectMocks
    private UserServiceReactiveImpl userService;

    private User testUser;
    private UserProfile testProfile;
    private UserCreationDTO testCreationDTO;
    private UserResponseDTO testResponseDTO;
    private UserDetailDTO testDetailDTO;
    private UUID testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();

        // User entity (R2DBC - pas de setters Lombok, juste des champs)
        testUser = new User();
        testUser.setId(testUserId);
        testUser.setAuthUserId("auth-123");
        testUser.setDateOfBirth(LocalDate.of(1990, 1, 1));
        testUser.setStatus(UserStatus.ACTIVE);
        testUser.setCreatedAt(LocalDateTime.now());
        testUser.setUpdatedAt(LocalDateTime.now());

        // UserProfile entity
        testProfile = new UserProfile();
        testProfile.setId(UUID.randomUUID());
        testProfile.setUserId(testUserId);
        testProfile.setBio("Test bio");
        testProfile.setCity("Bamako");

        // UserCreationDTO (record)
        testCreationDTO = new UserCreationDTO(
            "auth-123",
            "test@example.com",
            "+22300000000",
            "FR",
            "John",
            "Doe",
            UserRole.USER,
            null,
            null,
            null,
            null,
            null,
            null
        );

        // UserResponseDTO (record)
        testResponseDTO = new UserResponseDTO(
            testUserId,
            "auth-123",
            "test@example.com",
            "+22300000000",
            "John",
            "Doe",
            UserStatus.ACTIVE,
            UserRole.USER,
            LocalDateTime.now(),
            LocalDateTime.now()
        );

        // UserDetailDTO (record)
        when(cache.cleParIdentifiant(any(UUID.class)))
            .thenAnswer(inv -> "user:" + inv.getArgument(0));
        when(cache.cleParAuthUserId(anyString()))
            .thenAnswer(inv -> "user:auth_" + inv.getArgument(0));
        when(cache.ecrire(anyString(), any(UserDetailDTO.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(1)));
        when(cache.invaliderPuisRendre(any(), any(), any()))
            .thenAnswer(inv -> Mono.just(inv.getArgument(2)));

        testDetailDTO = new UserDetailDTO(
            testUserId,
            "auth-123",
            "test@example.com",
            "+22300000000",
            "ML",
            "John",
            "Doe",
            LocalDate.of(1990, 1, 1),
            null,
            UserStatus.ACTIVE,
            UserRole.USER,
            "Test bio",
            "123 Main St",
            "Bamako",
            "Mali",
            "75001",
            null,
            LocalDateTime.now(),
            LocalDateTime.now(),
            null
        );

    }

    // ==========================================
    // TESTS createUser
    // ==========================================

    @Test
    @DisplayName("createUser - Doit créer un utilisateur avec succès")
    void createUser_Success() {
        when(userRepository.existsByAuthUserId(testCreationDTO.authUserId()))
            .thenReturn(Mono.just(false));
        when(userMapper.toEntity(testCreationDTO)).thenReturn(testUser);
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(testUser));
        when(profileRepository.save(any(UserProfile.class))).thenReturn(Mono.just(testProfile));
        when(userMapper.toResponseDTO(testUser)).thenReturn(Mono.just(testResponseDTO));

        StepVerifier.create(userService.createUser(testCreationDTO))
            .expectNext(testResponseDTO)
            .verifyComplete();

        verify(userRepository).existsByAuthUserId(testCreationDTO.authUserId());
        verify(userRepository).save(any(User.class));
        verify(profileRepository).save(any(UserProfile.class));
        verify(userMapper).toResponseDTO(testUser);
    }

    @Test
    @DisplayName("createUser - Doit échouer si utilisateur existe déjà")
    void createUser_UserAlreadyExists() {
        when(userRepository.existsByAuthUserId(testCreationDTO.authUserId()))
            .thenReturn(Mono.just(true));

        StepVerifier.create(userService.createUser(testCreationDTO))
            .expectError(ResourceAlreadyExistsException.class)
            .verify();

        verify(userRepository).existsByAuthUserId(testCreationDTO.authUserId());
        verify(userRepository, never()).save(any(User.class));
        verify(profileRepository, never()).save(any(UserProfile.class));
    }

    // ==========================================
    // TESTS getUserById
    // ==========================================

    @Test
    @DisplayName("getUserById - Doit retourner utilisateur depuis cache")
    void getUserById_FromCache() {
        String cacheKey = "user:" + testUserId;

        when(cache.lire(cacheKey)).thenReturn(Mono.just(testDetailDTO));

        StepVerifier.create(userService.getUserById(testUserId))
            .expectNext(testDetailDTO)
            .verifyComplete();

        verify(cache).lire(cacheKey);
        // Le depot n'est PAS sollicite sur un succes de cache.
        //
        // Il l'etait : `switchIfEmpty` evalue son argument a l'ASSEMBLAGE du
        // flux, donc `findById` etait appele a chaque lecture, succes de cache
        // compris. Spring Data construisait la requete sans l'executer — sans
        // consequence visible, mais le test devait stuber un appel qui n'aurait
        // jamais du avoir lieu. Le `Mono.defer` le repousse a l'abonnement.
        verify(userRepository, never()).findById(testUserId);
    }

    @Test
    @DisplayName("getUserById - Doit charger depuis DB et mettre en cache")
    void getUserById_FromDatabase() {
        String cacheKey = "user:" + testUserId;

        when(cache.lire(cacheKey)).thenReturn(Mono.empty());
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userMapper.toDetailDTO(testUser)).thenReturn(Mono.just(testDetailDTO));

        StepVerifier.create(userService.getUserById(testUserId))
            .expectNext(testDetailDTO)
            .verifyComplete();

        verify(cache).lire(cacheKey);
        verify(userRepository).findById(testUserId);
        verify(userMapper).toDetailDTO(testUser);
        verify(cache).ecrire(cacheKey, testDetailDTO);
    }

    @Test
    @DisplayName("getUserById - Doit échouer si utilisateur non trouvé")
    void getUserById_NotFound() {
        String cacheKey = "user:" + testUserId;

        when(cache.lire(cacheKey)).thenReturn(Mono.empty());
        when(userRepository.findById(testUserId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.getUserById(testUserId))
            .expectError(ResourceNotFoundException.class)
            .verify();

        verify(userRepository).findById(testUserId);
    }

    // ==========================================
    // TESTS getUserByAuthUserId
    // ==========================================

    @Test
    @DisplayName("getUserByAuthUserId - Doit retourner utilisateur")
    void getUserByAuthUserId_Success() {
        when(cache.lire(anyString())).thenReturn(Mono.empty());
        when(userRepository.findByAuthUserId("auth-123")).thenReturn(Mono.just(testUser));
        when(userMapper.toDetailDTO(testUser)).thenReturn(Mono.just(testDetailDTO));

        StepVerifier.create(userService.getUserByAuthUserId("auth-123"))
            .expectNext(testDetailDTO)
            .verifyComplete();

        verify(userRepository).findByAuthUserId("auth-123");
        verify(userMapper).toDetailDTO(testUser);
    }

    @Test
    @DisplayName("getUserByAuthUserId - Doit échouer si utilisateur non trouvé")
    void getUserByAuthUserId_NotFound() {
        when(cache.lire(anyString())).thenReturn(Mono.empty());
        when(userRepository.findByAuthUserId("auth-999")).thenReturn(Mono.empty());

        StepVerifier.create(userService.getUserByAuthUserId("auth-999"))
            .expectError(ResourceNotFoundException.class)
            .verify();

        verify(userRepository).findByAuthUserId("auth-999");
    }

    // ==========================================
    // TESTS updateUser
    // ==========================================

    @Test
    @DisplayName("updateUser - Doit mettre à jour utilisateur et invalider cache")
    void updateUser_Success() {
        // Sept champs, et non neuf : firstName et lastName ont ete retires du
        // contrat — ils etaient acceptes puis jetes, l'identite vivant dans
        // auth-service. `preferences` est apparu : elles etaient stockees,
        // rendues et effacees, mais jamais modifiables.
        UserUpdateDTO updateDTO = new UserUpdateDTO(
            LocalDate.of(1995, 5, 15),
            "Updated bio",
            "456 New St",
            "Segou",
            "Mali",
            "69001",
            java.util.Map.of("theme", "sombre")
        );

        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(testUser));
        when(profileRepository.findByUserId(testUserId)).thenReturn(Mono.just(testProfile));
        when(profileRepository.save(any(UserProfile.class))).thenReturn(Mono.just(testProfile));
        when(userMapper.toDetailDTO(testUser)).thenReturn(Mono.just(testDetailDTO));

        StepVerifier.create(userService.updateUser(testUserId, updateDTO))
            .expectNext(testDetailDTO)
            .verifyComplete();

        verify(userRepository).findById(testUserId);
        verify(userRepository).save(any(User.class));
        verify(profileRepository).save(any(UserProfile.class));
        // Les DEUX cles sont invalidees : la verification de ce fait appartient
        // a CacheDesComptesTest. Ici on verifie que ce service la SOLLICITE,
        // avec l'identifiant d'authentification — le passer a null laisserait
        // la cle de GET /users/me servir l'etat d'avant.
        verify(cache).invaliderPuisRendre(eq(testUserId), eq("auth-123"), any());
    }

    // ==========================================
    // TESTS deleteUser
    // ==========================================

    @Test
    @DisplayName("deleteUser - Doit effectuer soft delete")
    void deleteUser_Success() {
        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(testUser));

        StepVerifier.create(userService.deleteUser(testUserId))
            .verifyComplete();

        verify(userRepository).findById(testUserId);
        verify(userRepository).save(argThat(user -> user.getDeletedAt() != null));
        // Les DEUX cles sont invalidees : la verification de ce fait appartient
        // a CacheDesComptesTest. Ici on verifie que ce service la SOLLICITE,
        // avec l'identifiant d'authentification — le passer a null laisserait
        // la cle de GET /users/me servir l'etat d'avant.
        verify(cache).invaliderPuisRendre(eq(testUserId), eq("auth-123"), any());
    }

    // ==========================================
    // TESTS getAllUsers
    // ==========================================

    @Test
    @DisplayName("getAllUsers - Doit retourner tous les utilisateurs")
    void getAllUsers_Success() {
        User user2 = new User();
        user2.setId(UUID.randomUUID());
        user2.setAuthUserId("auth-456");

        UserDetailDTO detail2 = new UserDetailDTO(
            user2.getId(),
            "auth-456",
            "user2@example.com",
            "+22300000002",
            "ML",
            "Jane",
            "Smith",
            null,
            null,
            UserStatus.ACTIVE,
            UserRole.USER,
            null,
            null,
            null,
            null,
            null,
            null,
            LocalDateTime.now(),
            LocalDateTime.now(),
            null
        );

        when(userRepository.findAllActive(20, 0)).thenReturn(Flux.just(testUser, user2));
        // Le mapper enrichit desormais le flux entier : l'enchainement est
        // reproduit ici pour que les deux stubs unitaires restent utiles.
        when(userMapper.toDetailDTOs(any())).thenAnswer(invocation -> {
            Flux<User> source = invocation.getArgument(0);
            return source.flatMapSequential(userMapper::toDetailDTO);
        });
        when(userMapper.toDetailDTO(testUser)).thenReturn(Mono.just(testDetailDTO));
        when(userMapper.toDetailDTO(user2)).thenReturn(Mono.just(detail2));

        StepVerifier.create(userService.getAllUsers(20, 0))
            .expectNext(testDetailDTO)
            .expectNext(detail2)
            .verifyComplete();

        verify(userRepository).findAllActive(20, 0);
    }

    // ==========================================
    // TESTS preferences
    // ==========================================

    /**
     * Les préférences sont écrites.
     *
     * <p>Elles étaient stockées, rendues dans la réponse, effacées par le RGPD —
     * et jamais modifiables. Le seul appel à {@code setPreferences} de tout le
     * service était celui de l'effacement.
     */
    @Test
    @DisplayName("updateUser - écrit les préférences fournies")
    void updateUser_ecritLesPreferences() {
        UserUpdateDTO dto = new UserUpdateDTO(null, null, null, null, null, null,
            java.util.Map.of("theme", "sombre", "langue", "fr"));

        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(testUser));
        when(profileRepository.findByUserId(testUserId)).thenReturn(Mono.just(testProfile));
        when(profileRepository.save(any(UserProfile.class))).thenReturn(Mono.just(testProfile));
        when(userMapper.toDetailDTO(testUser)).thenReturn(Mono.just(testDetailDTO));

        StepVerifier.create(userService.updateUser(testUserId, dto))
            .expectNextCount(1)
            .verifyComplete();

        verify(profileRepository).save(argThat(profil ->
            profil.getPreferences() != null
                && "sombre".equals(profil.getPreferences().get("theme"))
                && "fr".equals(profil.getPreferences().get("langue"))));
    }

    @Test
    @DisplayName("updateUser - un champ preferences absent ne touche pas l'existant")
    void updateUser_preferencesAbsentesNeTouchentRien() {
        testProfile.setPreferences(new java.util.HashMap<>(java.util.Map.of("deja", "la")));
        UserUpdateDTO dto = new UserUpdateDTO(null, "bio", null, null, null, null, null);

        when(userRepository.findById(testUserId)).thenReturn(Mono.just(testUser));
        when(userRepository.save(any(User.class))).thenReturn(Mono.just(testUser));
        when(profileRepository.findByUserId(testUserId)).thenReturn(Mono.just(testProfile));
        when(profileRepository.save(any(UserProfile.class))).thenReturn(Mono.just(testProfile));
        when(userMapper.toDetailDTO(testUser)).thenReturn(Mono.just(testDetailDTO));

        StepVerifier.create(userService.updateUser(testUserId, dto))
            .expectNextCount(1)
            .verifyComplete();

        verify(profileRepository).save(argThat(profil ->
            "la".equals(profil.getPreferences().get("deja"))));
    }

    /**
     * Une valeur hors bornes est refusée <b>avant</b> toute lecture en base.
     *
     * <p>{@code @Size} contraint le nombre d'entrées de la carte, pas la
     * longueur des clefs ni des valeurs — et aucune annotation standard ne
     * l'exprime. Sans ce contrôle, un seul appel écrit des mégaoctets dans une
     * colonne {@code TEXT}, que chaque lecture du profil relit ensuite.
     */
    @Test
    @DisplayName("updateUser - une valeur de préférence trop longue est refusée sans lire la base")
    void updateUser_preferenceHorsBornesRefusee() {
        String tropLong = "x".repeat(UserUpdateDTO.MAX_LONGUEUR_VALEUR + 1);
        UserUpdateDTO dto = new UserUpdateDTO(null, null, null, null, null, null,
            java.util.Map.of("cle", tropLong));

        StepVerifier.create(userService.updateUser(testUserId, dto))
            .expectError(IllegalArgumentException.class)
            .verify();

        verify(userRepository, never()).findById(testUserId);
        verify(profileRepository, never()).save(any(UserProfile.class));
    }

    @Test
    @DisplayName("updateUser - une clé de préférence trop longue est refusée")
    void updateUser_cleHorsBornesRefusee() {
        String cleTropLongue = "k".repeat(UserUpdateDTO.MAX_LONGUEUR_CLEF + 1);
        UserUpdateDTO dto = new UserUpdateDTO(null, null, null, null, null, null,
            java.util.Map.of(cleTropLongue, "valeur"));

        StepVerifier.create(userService.updateUser(testUserId, dto))
            .expectError(IllegalArgumentException.class)
            .verify();

        verify(userRepository, never()).findById(testUserId);
    }
}
