package com.abdatytch.user_service.service.impl;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.constants.AppConstants;
import com.abdatytch.user_service.constants.ValidationMessages;
import com.abdatytch.user_service.dto.request.UserCreationDTO;
import com.abdatytch.user_service.dto.request.UserUpdateDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.dto.response.UserResponseDTO;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.exception.ResourceAlreadyExistsException;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.mapper.UserMapper;
import com.abdatytch.user_service.metrics.UserMetrics;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.UserService;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Implémentation réactive du service utilisateur.
 *
 * <h2>Les listes paginent en SQL</h2>
 *
 * {@code searchUsers} et {@code getAllUsers} enchaînaient
 * {@code skip(offset).take(limit)} sur une requête <b>sans</b> {@code LIMIT} :
 * toute la table traversait le pilote et le convertisseur pour que vingt lignes
 * survivent. La pagination est passée dans les requêtes du dépôt.
 *
 * <h2>Le cache est désormais mesuré</h2>
 *
 * Les deux lectures par identifiant passent par Redis, et le tableau de bord
 * porte un panneau « taux de succès du cache » depuis l'origine. Les compteurs
 * qu'il interroge existaient — dans une classe que personne n'injectait. Ils sont
 * incrémentés ici, aux deux seuls endroits où la question se pose.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceReactiveImpl implements UserService {

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final UserMapper userMapper;
    private final CacheDesComptes cache;
    private final UserMetrics userMetrics;

    @Override
    public Mono<UserResponseDTO> createUser(UserCreationDTO dto) {
        log.info(AppConstants.LOG_CREATING_USER, dto.authUserId());

        // La mesure est ouverte AVANT l'abonnement et close au succes : la
        // duree de creation couvre l'insertion du compte et celle du profil,
        // qui sont ce que l'appelant attend.
        Timer.Sample mesure = userMetrics.debutDeCreation();

        return userRepository.existsByAuthUserId(dto.authUserId())
                .flatMap(existe -> {
                    if (Boolean.TRUE.equals(existe)) {
                        log.warn(AppConstants.LOG_WARN_USER_CREATION_FAILED, dto.authUserId());
                        return Mono.error(new ResourceAlreadyExistsException(String.format(
                                AppConstants.EX_USER_ALREADY_EXISTS, dto.authUserId())));
                    }

                    // Note: email et phone sont verifies dans auth-service
                    // (source de verite).
                    User user = userMapper.toEntity(dto);
                    return userRepository.save(user)
                            .flatMap(enregistre -> {
                                // Le profil est cree en meme temps que le compte :
                                // la relation est geree a la main en R2DBC.
                                //
                                // Les champs declaratifs fournis par l'appelant y
                                // sont ECRITS. Ils etaient acceptes puis jetes :
                                // POST /internal/users rendait 201 pour un profil
                                // reste vide.
                                UserProfile profil = new UserProfile();
                                profil.setUserId(enregistre.getId());
                                profil.setAddress(dto.address());
                                profil.setBio(dto.bio());
                                return profileRepository.save(profil).thenReturn(enregistre);
                            })
                            .doOnNext(enregistre -> {
                                log.info(AppConstants.SUCCESS_USER_CREATED, enregistre.getId());
                                userMetrics.compteCree();
                                userMetrics.finDeCreation(mesure);
                            })
                            .flatMap(userMapper::toResponseDTO);
                });
    }

    @Override
    public Mono<UserDetailDTO> getUserById(UUID id) {
        log.debug(AppConstants.LOG_DEBUG_FETCHING_USER_BY_ID, id);
        String cle = cache.cleParIdentifiant(id);

        return cache.lire(cle)
                .switchIfEmpty(Mono.defer(() -> userRepository.findById(id)
                        .switchIfEmpty(Mono.defer(() -> {
                            log.error(AppConstants.LOG_ERROR_USER_NOT_FOUND_BY_ID, id);
                            return Mono.error(new ResourceNotFoundException(String.format(
                                    AppConstants.EX_USER_NOT_FOUND, id)));
                        }))
                        .flatMap(userMapper::toDetailDTO)
                        .flatMap(detail -> cache.ecrire(cle, detail))));
    }

    @Override
    public Mono<UserDetailDTO> getUserByAuthUserId(String authUserId) {
        log.debug(AppConstants.LOG_DEBUG_FETCHING_USER_BY_AUTH_ID, authUserId);
        String cle = cache.cleParAuthUserId(authUserId);

        return cache.lire(cle)
                .switchIfEmpty(Mono.defer(() -> userRepository.findByAuthUserId(authUserId)
                        .switchIfEmpty(Mono.defer(() -> {
                            log.error(AppConstants.LOG_ERROR_USER_NOT_FOUND_BY_AUTH_ID, authUserId);
                            return Mono.error(new ResourceNotFoundException(String.format(
                                    AppConstants.EX_USER_NOT_FOUND_AUTH_ID, authUserId)));
                        }))
                        .flatMap(userMapper::toDetailDTO)
                        .flatMap(detail -> cache.ecrire(cle, detail))));
    }

    @Override
    public Mono<UserDetailDTO> updateUser(UUID id, UserUpdateDTO dto) {
        log.info(AppConstants.LOG_UPDATING_USER, id);

        // Bornes des preferences, verifiees AVANT toute lecture en base.
        //
        // `@Size` contraint le nombre d'entrees, pas la longueur des clefs ni
        // des valeurs — et aucune annotation standard ne l'exprime. Sans ce
        // controle, une carte libre venue du client s'ecrit telle quelle dans
        // une colonne TEXT, et chaque lecture du profil la relit ensuite.
        if (!dto.preferencesDansLesBornes()) {
            return Mono.error(new IllegalArgumentException(
                    ValidationMessages.PREFERENCES_HORS_BORNES));
        }

        return userRepository.findById(id)
                .switchIfEmpty(Mono.defer(() -> {
                    log.error(AppConstants.LOG_ERROR_USER_NOT_FOUND_FOR_UPDATE, id);
                    return Mono.error(new ResourceNotFoundException(String.format(
                            AppConstants.EX_USER_NOT_FOUND, id)));
                }))
                .flatMap(user -> {
                    // Note: firstName et lastName sont dans auth-service.
                    if (dto.dateOfBirth() != null) {
                        user.setDateOfBirth(dto.dateOfBirth());
                    }
                    // profilePictureUrl est gere par POST /users/me/profile-picture.
                    return userRepository.save(user)
                            .flatMap(enregistre -> majDuProfil(enregistre, dto));
                })
                .flatMap(compte -> cache.invaliderPuisRendre(
                        compte.getId(), compte.getAuthUserId(), compte))
                .doOnNext(enregistre -> {
                    log.info(AppConstants.SUCCESS_USER_UPDATED, id);
                    userMetrics.compteModifie();
                })
                .flatMap(userMapper::toDetailDTO);
    }

    private Mono<User> majDuProfil(User compte, UserUpdateDTO dto) {
        return profileRepository.findByUserId(compte.getId())
                .switchIfEmpty(Mono.defer(() -> {
                    UserProfile nouveau = new UserProfile();
                    nouveau.setUserId(compte.getId());
                    return Mono.just(nouveau);
                }))
                .flatMap(profil -> {
                    if (dto.bio() != null) {
                        profil.setBio(dto.bio());
                    }
                    if (dto.address() != null) {
                        profil.setAddress(dto.address());
                    }
                    if (dto.city() != null) {
                        profil.setCity(dto.city());
                    }
                    if (dto.country() != null) {
                        profil.setCountry(dto.country());
                    }
                    if (dto.postalCode() != null) {
                        profil.setPostalCode(dto.postalCode());
                    }
                    // Preferences : REMPLACEES en bloc quand le champ est
                    // fourni, et non fusionnees. Une fusion rendrait impossible
                    // le retrait d'une preference — l'appelant n'aurait aucun
                    // moyen de dire « supprime cette clef ». Une carte vide
                    // efface tout ; un champ absent ne touche a rien.
                    if (dto.preferences() != null) {
                        profil.setPreferences(new java.util.HashMap<>(dto.preferences()));
                    }
                    return profileRepository.save(profil);
                })
                .thenReturn(compte);
    }

    @Override
    public Mono<Void> deleteUser(UUID id) {
        log.info(AppConstants.LOG_DELETING_USER, id);
        log.debug(AppConstants.LOG_DEBUG_SOFT_DELETING_USER, id);

        return userRepository.findById(id)
                .switchIfEmpty(Mono.defer(() -> {
                    log.error(AppConstants.LOG_ERROR_USER_NOT_FOUND_FOR_DELETION, id);
                    return Mono.error(new ResourceNotFoundException(String.format(
                            AppConstants.EX_USER_NOT_FOUND, id)));
                }))
                .flatMap(user -> {
                    // Effacement LOGIQUE : la ligne reste, pour que l'audit
                    // sache que le compte a existe.
                    user.setStatus(UserStatus.DELETED);
                    user.setDeletedAt(LocalDateTime.now());
                    return userRepository.save(user);
                })
                .flatMap(compte -> cache.invaliderPuisRendre(
                        compte.getId(), compte.getAuthUserId(), compte))
                .doOnNext(efface -> log.info(AppConstants.SUCCESS_USER_DELETED, id))
                .then();
    }

    @Override
    public Flux<UserDetailDTO> searchUsers(String query, int limit, int offset) {
        log.debug(AppConstants.LOG_DEBUG_SEARCHING_USERS, query, limit, offset);
        return userMapper.toDetailDTOs(
                userRepository.searchUsers(motifDeRecherche(query), limit, offset));
    }

    /**
     * Encadre le terme de recherche de {@code %}, après neutralisation.
     *
     * <p>{@code %}, {@code _} et {@code \} sont les métacaractères de
     * {@code LIKE}. Sans échappement, une recherche sur {@code %} rendrait
     * <b>tous</b> les comptes et {@code _} n'importe quel caractère : le
     * paramètre lié protège de l'injection SQL, pas de l'injection de motif.
     *
     * <p>L'antislash doit être traité en premier, sinon il échapperait les
     * antislashes qu'on vient d'ajouter.
     */
    static String motifDeRecherche(String query) {
        if (query == null || query.isBlank()) {
            // Motif qui ne correspond a rien : une recherche vide ne doit pas
            // devenir « tout lister ».
            return "";
        }
        String neutralise = query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + neutralise + "%";
    }

    @Override
    public Flux<UserDetailDTO> getUsersByStatus(UserStatus status, int limit, int offset) {
        log.debug("Liste des comptes au statut {} (limite {}, decalage {})", status, limit, offset);
        return userMapper.toDetailDTOs(
                userRepository.findByStatusWithPagination(status, limit, offset));
    }

    @Override
    public Flux<UserDetailDTO> getAllUsers(int limit, int offset) {
        log.debug("Liste des comptes vivants (limite {}, decalage {})", limit, offset);
        return userMapper.toDetailDTOs(userRepository.findAllActive(limit, offset));
    }
}
