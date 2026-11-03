package com.abdatytch.user_service.service.impl;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.client.ClientIdentiteAuth;
import com.abdatytch.user_service.constants.AdminConstants;
import com.abdatytch.user_service.constants.AppConstants;
import com.abdatytch.user_service.constants.AuditConstants;
import com.abdatytch.user_service.dto.request.AdminActionDTO;
import com.abdatytch.user_service.dto.request.AdminUserUpdateDTO;
import com.abdatytch.user_service.dto.request.AuditLogCreateDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.enums.ActionType;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.mapper.UserMapper;
import com.abdatytch.user_service.metrics.UserMetrics;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.AuditLogService;
import com.abdatytch.user_service.service.UserAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Décisions d'administration : une mécanique, cinq mutations.
 *
 * <p>Voir {@link UserAdminService} pour ce qui a été réuni ici et pourquoi les
 * trois réversions n'en font plus qu'une.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAdminServiceImpl implements UserAdminService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserMapper userMapper;
    private final AuditLogService auditLogService;
    private final ClientIdentiteAuth clientIdentiteAuth;
    private final UserMetrics userMetrics;
    private final CacheDesComptes cache;

    // ==================================================================
    // Les cinq décisions
    // ==================================================================

    @Override
    public Mono<UserDetailDTO> blockUser(UUID userId, AdminActionDTO action) {
        return decider(userId, AdminConstants.DECISION_BLOCAGE, action.reason(), compte -> {
            compte.setStatus(UserStatus.BLOCKED);
            compte.setBlockedReason(action.reason());
            compte.setBlockedAt(LocalDateTime.now());
            // Pas d'echeance : c'est ce qui distingue un blocage d'une
            // suspension. Et on efface celle d'une suspension precedente,
            // sinon elle reviendrait relever le compte toute seule.
            compte.setSuspendedUntil(null);
        }).flatMap(userMapper::toDetailDTO);
    }

    @Override
    public Mono<UserDetailDTO> banUser(UUID userId, AdminActionDTO action) {
        return decider(userId, AdminConstants.DECISION_BANNISSEMENT, action.reason(), compte -> {
            compte.setStatus(UserStatus.BANNED);
            compte.setBlockedReason(action.reason());
            compte.setBlockedAt(LocalDateTime.now());
            compte.setSuspendedUntil(null);
        }).flatMap(userMapper::toDetailDTO);
    }

    @Override
    public Mono<UserDetailDTO> suspendUser(UUID userId, AdminActionDTO action) {
        // L'echeance est ce qui fait la suspension : sans elle, la decision est
        // un blocage deguise dont rien ne provoquera la levee.
        if (action.until() == null) {
            return Mono.error(new IllegalArgumentException(
                    "Une suspension exige une echeance (champ 'until')"));
        }
        return decider(userId, AdminConstants.DECISION_SUSPENSION, action.reason(), compte -> {
            compte.setStatus(UserStatus.SUSPENDED);
            compte.setBlockedReason(action.reason());
            compte.setSuspendedUntil(action.until());
            compte.setBlockedAt(LocalDateTime.now());
        }).flatMap(userMapper::toDetailDTO);
    }

    @Override
    public Mono<UserDetailDTO> revokeUser(UUID userId) {
        return revoquer(userId).flatMap(userMapper::toDetailDTO);
    }

    /**
     * Révocation, sans composition de la réponse.
     *
     * <p>{@link #closeAccount(UUID)} passe par ici : composer un
     * {@code UserDetailDTO} appellerait auth-service pour une réponse que
     * personne ne lit — la fermeture rend 204.
     */
    private Mono<User> revoquer(UUID userId) {
        return decider(userId, AdminConstants.DECISION_REVOCATION,
                AppConstants.REASON_REVOKED_BY_ADMIN, compte -> {
                    compte.setStatus(UserStatus.DELETED);
                    compte.setDeletedAt(LocalDateTime.now());
                    compte.setBlockedReason(AppConstants.REASON_REVOKED_BY_ADMIN);
                });
    }

    @Override
    public Mono<UserDetailDTO> restoreUser(UUID userId) {
        return decider(userId, AdminConstants.DECISION_RETABLISSEMENT, null, compte -> {
            if (compte.getStatus() == UserStatus.BANNED) {
                // Un bannissement est annonce comme definitif : le lever reste
                // possible, parce qu'une decision administrative peut etre une
                // erreur, mais cela doit se voir dans les journaux.
                log.warn(AdminConstants.LOG_WARN_BANNISSEMENT_LEVE, compte.getId());
            }
            // Remise a plat COMPLETE, et c'est le correctif des trois anciennes
            // reversions : chacune n'effacait que ses propres traces, donc
            // retablir un compte suspendu puis bloque laissait une echeance de
            // suspension posee sur un compte redevenu actif.
            compte.setStatus(UserStatus.ACTIVE);
            compte.setBlockedReason(null);
            compte.setBlockedAt(null);
            compte.setSuspendedUntil(null);
            compte.setDeletedAt(null);
        }).flatMap(userMapper::toDetailDTO);
    }

    /**
     * Mécanique commune aux cinq décisions.
     *
     * <p>{@code Mono.defer} n'est pas nécessaire ici : rien n'est levé hors du
     * flux, et les contrôles d'argument ont lieu chez l'appelant, avant que le
     * {@code Mono} ne soit construit.
     *
     * @param decision valeur de {@code AdminConstants.DECISION_*}, qui sert à la
     *                 fois d'étiquette de métrique et de libellé de journal
     * @param motif    motif de la décision, pour le journal ; nul pour un
     *                 rétablissement, qui n'en a pas
     * @param mutation la seule chose qui distingue les cinq décisions
     */
    private Mono<User> decider(UUID userId, String decision, String motif,
                               Consumer<User> mutation) {
        log.info(AdminConstants.LOG_DECISION_DEMANDEE, userId, decision);

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(() -> {
                    log.error(AdminConstants.LOG_ERROR_COMPTE_INTROUVABLE, decision, userId);
                    return new ResourceNotFoundException(String.format(
                            AppConstants.EX_USER_NOT_FOUND, userId));
                }))
                .flatMap(compte -> {
                    // L'etat precedent est releve AVANT la mutation : apres, il
                    // n'existe plus nulle part, et c'est lui qui rend la
                    // decision verifiable.
                    UserStatus etatPrecedent = compte.getStatus();
                    mutation.accept(compte);

                    return userRepository.save(compte)
                            .doOnNext(enregistre -> {
                                log.warn(AdminConstants.LOG_DECISION_PRISE,
                                        userId, enregistre.getAuthUserId(),
                                        decision, etatPrecedent, motif);
                                userMetrics.decisionPrise(decision);
                            });
                })
                .flatMap(compte -> cache.invaliderPuisRendre(
                        compte.getId(), compte.getAuthUserId(), compte));
    }

    // ==================================================================
    // Modification de profil
    // ==================================================================

    @Override
    public Mono<UserDetailDTO> updateUserByAdmin(UUID userId, AdminUserUpdateDTO dto) {
        log.info(AdminConstants.LOG_ADMIN_UPDATING_USER, userId);

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException(
                        AdminConstants.ERROR_USER_NOT_FOUND + userId)))
                .flatMap(compte -> {
                    StringBuilder changements =
                            new StringBuilder(AdminConstants.FORMAT_CHANGES_PREFIX);

                    if (dto.dateOfBirth() != null) {
                        changements.append(String.format(
                                AdminConstants.FORMAT_CHANGE_DATE_OF_BIRTH, dto.dateOfBirth()));
                        compte.setDateOfBirth(dto.dateOfBirth());
                    }

                    // Les deux ecritures sont ENCHAINEES, et non menees de front.
                    //
                    // L'ancienne version les composait par `zipWith`, ce qui les
                    // lance en parallele sur deux connexions du pool — et
                    // `zipWith` abandonne l'une si l'autre echoue, en laissant
                    // la premiere deja ecrite. Une modification partielle
                    // repondait alors « echec » tout en ayant change la date de
                    // naissance.
                    return userRepository.save(compte)
                            .flatMap(enregistre -> majDuProfil(userId, dto, changements)
                                    .thenReturn(enregistre))
                            .doOnNext(enregistre -> {
                                log.warn(AdminConstants.LOG_WARN_ADMIN_PROFILE_UPDATED,
                                        userId, enregistre.getAuthUserId(), changements);
                                log.info(AdminConstants.LOG_NOTE_AUTH_SERVICE);
                                userMetrics.compteModifie();
                            })
                            .flatMap(modifie -> cache.invaliderPuisRendre(
                                    modifie.getId(), modifie.getAuthUserId(), modifie))
                            .flatMap(userMapper::toDetailDTO);
                });
    }

    /**
     * Applique les champs de profil fournis, en créant le profil s'il manque.
     *
     * <p>Rend un {@code Mono<Void>} vide quand le corps de la requête ne porte
     * aucun champ de profil : il n'y a alors rien à écrire, et ouvrir une
     * transaction pour n'en rien faire serait du bruit.
     */
    private Mono<Void> majDuProfil(UUID userId, AdminUserUpdateDTO dto,
                                   StringBuilder changements) {
        boolean aucunChampDeProfil = dto.bio() == null && dto.address() == null
                && dto.city() == null && dto.country() == null && dto.postalCode() == null;
        if (aucunChampDeProfil) {
            return Mono.empty();
        }

        return userProfileRepository.findByUserId(userId)
                .switchIfEmpty(Mono.defer(() -> {
                    UserProfile nouveau = new UserProfile();
                    nouveau.setUserId(userId);
                    return Mono.just(nouveau);
                }))
                .flatMap(profil -> {
                    if (dto.bio() != null) {
                        changements.append(AdminConstants.FORMAT_CHANGE_BIO);
                        profil.setBio(dto.bio());
                    }
                    if (dto.address() != null) {
                        changements.append(String.format(
                                AdminConstants.FORMAT_CHANGE_ADDRESS, dto.address()));
                        profil.setAddress(dto.address());
                    }
                    if (dto.city() != null) {
                        changements.append(String.format(
                                AdminConstants.FORMAT_CHANGE_CITY, dto.city()));
                        profil.setCity(dto.city());
                    }
                    if (dto.country() != null) {
                        changements.append(String.format(
                                AdminConstants.FORMAT_CHANGE_COUNTRY, dto.country()));
                        profil.setCountry(dto.country());
                    }
                    if (dto.postalCode() != null) {
                        changements.append(String.format(
                                AdminConstants.FORMAT_CHANGE_POSTAL_CODE, dto.postalCode()));
                        profil.setPostalCode(dto.postalCode());
                    }
                    return userProfileRepository.save(profil);
                })
                .then();
    }

    // ==================================================================
    // Fermeture de compte
    // ==================================================================

    @Override
    public Mono<Void> closeAccount(UUID userId) {
        log.info(AdminConstants.LOG_FERMETURE_DEMANDEE, userId);

        // La partie locale REUTILISE la revocation : il n'existe qu'une
        // implementation du passage a l'etat efface.
        //
        // L'ancienne version faisait ici un `userRepository.delete(user)` — la
        // ligne disparaissait, et le journal d'audit ecrit juste apres
        // referencait un compte dont il ne restait aucune trace. « Effacer n'est
        // pas supprimer la ligne » : un journal qui pointe vers le vide n'atteste
        // de rien.
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException(
                        AdminConstants.ERROR_USER_NOT_FOUND + userId)))
                .flatMap(compte -> {
                    String authUserId = compte.getAuthUserId();
                    String avant = String.format(
                            AdminConstants.FORMAT_USER_DETAILS, userId, authUserId);

                    return revoquer(userId)
                            .then(propagerAAuthService(userId, authUserId))
                            .then(journaliser(userId, avant, null))
                            .onErrorResume(erreur -> {
                                log.error(AdminConstants.LOG_ERROR_FERMETURE_ECHOUEE,
                                        userId, erreur.getMessage());
                                return journaliser(userId, avant, erreur)
                                        .then(Mono.error(new IllegalStateException(
                                                String.format(
                                                        AdminConstants.EX_CASCADE_DELETE_FAILED,
                                                        erreur.getMessage()), erreur)));
                            });
                });
    }

    /**
     * Propage la fermeture à auth-service.
     *
     * <p>L'échec doit <b>remonter</b> : l'appelant s'en sert pour journaliser la
     * fermeture comme ratée et rendre une erreur. {@link ClientIdentiteAuth} ne
     * rattrape rien, ce qui est exactement ce qu'il faut ici — un repli silencieux
     * laisserait un compte révoqué localement mais toujours actif dans
     * auth-service, donc capable de se connecter.
     */
    private Mono<Void> propagerAAuthService(UUID userId, String authUserId) {
        return clientIdentiteAuth.supprimer(authUserId)
                .doOnSuccess(rien -> log.warn(
                        AdminConstants.LOG_FERMETURE_PROPAGEE, userId, authUserId))
                .then();
    }

    /**
     * Consigne la fermeture — ou son échec — dans le journal d'audit.
     *
     * <p>L'identifiant de l'administrateur vient du contexte de sécurité. Il
     * peut manquer : la fermeture appelée hors d'une requête HTTP n'a pas de
     * contexte. {@code defaultIfEmpty} pose alors une valeur explicite, là où
     * l'ancienne version se rabattait sur <b>l'identifiant du compte fermé</b> —
     * le journal attribuait donc la décision à sa propre victime.
     */
    private Mono<Void> journaliser(UUID userId, String avant, Throwable erreur) {
        return ReactiveSecurityContextHolder.getContext()
                .map(contexte -> contexte.getAuthentication().getName())
                .defaultIfEmpty(AuditConstants.ADMIN_INCONNU)
                .flatMap(adminId -> auditLogService.logAction(AuditLogCreateDTO.builder()
                        .userId(userId)
                        .adminId(adminId)
                        .actionType(ActionType.DELETE)
                        .entityType(AuditConstants.ENTITY_TYPE_USER)
                        .entityId(userId.toString())
                        .actionDetails(erreur == null
                                ? AuditConstants.ACTION_PERMANENT_DELETION
                                : AdminConstants.DETAIL_FERMETURE_ECHOUEE)
                        .previousValue(avant)
                        .newValue(erreur == null ? AdminConstants.EX_DELETED_VALUE : null)
                        .status(erreur == null
                                ? AuditConstants.STATUS_SUCCESS
                                : AuditConstants.STATUS_FAILED)
                        .errorMessage(erreur == null ? null : erreur.getMessage())
                        .build()))
                // Un echec d'ecriture du journal ne doit pas masquer le
                // resultat de la fermeture elle-meme, ni la faire paraitre
                // ratee alors qu'elle a abouti.
                .onErrorResume(echec -> {
                    log.error("Journal d'audit non ecrit pour la fermeture du compte {} : {}",
                            userId, echec.getMessage());
                    return Mono.empty();
                });
    }
}
