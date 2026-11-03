package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.dto.request.CreateUserWithRolesRequest;
import com.abdatytch.auth_service.dto.request.UserCreationDTO;
import com.abdatytch.auth_service.dto.response.UserWithRolesResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.entities.UserRoleAssignment;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.exception.ResourceAlreadyExistsException;
import com.abdatytch.auth_service.exception.ResourceNotFoundException;
import com.abdatytch.auth_service.mappers.UserRoleMapper;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.repository.UserRoleAssignmentRepository;
import com.abdatytch.auth_service.service.IUserRoleManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Gestion des rôles multiples d'un utilisateur, en réactif.
 *
 * <p><b>Feign conservé.</b> {@link UserServiceClient} reste un client Feign, comme
 * décidé pour ne pas toucher aux contrats inter-services. Feign étant bloquant, ses
 * appels sont isolés sur {@code Schedulers.boundedElastic()} : l'EventLoop Netty
 * n'est jamais retenu.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserRoleManagementServiceImpl implements IUserRoleManagementService {

    private final UserRepository userRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final com.abdatytch.auth_service.service.IMotDePasseService motDePasseService;
    private final UserRoleMapper userRoleMapper;

    /**
     * Refuse un role que l'API n'a pas le droit d'attribuer.
     *
     * <p>{@code SUPER_ADMIN} n'existe que par le seeder de deploiement. Sans ce
     * controle, un {@code ADMIN} compromis se promouvrait lui-meme : le pouvoir
     * de distribuer les roles inclurait celui de s'octroyer le plus eleve, et la
     * distinction entre les deux ne servirait plus a rien.
     *
     * @return le {@code Mono} d'erreur a renvoyer, ou {@code null} si le role
     *         est attribuable
     */
    private Mono<UserWithRolesResponse> refuserSiNonAttribuable(UserRole role, String cible) {
        if (role != null && !role.estAttribuableParApi()) {
            log.warn("Attribution du role {} refusee pour {} : non attribuable par l'API",
                    role, cible);
            return Mono.error(new IllegalArgumentException(
                    "Le role " + role + " ne peut etre accorde que par le seeder de deploiement"));
        }
        return null;
    }

    @Override
    @Transactional
    public Mono<UserWithRolesResponse> createUserWithRoles(CreateUserWithRolesRequest request) {
        log.info("Création d'un utilisateur avec rôles: email={}, roles={}",
                request.getEmail(), request.getRoles());

        if (request.getPrimaryRole() != null && !request.getRoles().contains(request.getPrimaryRole())) {
            return Mono.error(new IllegalArgumentException(AppConstants.USER_NOT_FOUND_MESSAGE));
        }

        // LE MEME CONTROLE QUE addRoleToUser, qui manquait ici.
        //
        // Le commentaire de addRoleToUser dit qu'une regle verifiee a un seul
        // endroit est une regle qu'on contourne en ajoutant un second point
        // d'entree. C'est precisement ce qui s'etait produit : cette methode
        // est ce second point d'entree, et elle attribuait sans controle tous
        // les roles presents dans la requete.
        //
        // Un ADMIN pouvait donc creer un compte SUPER_ADMIN par
        // POST /auth-users avec roles: [SUPER_ADMIN], alors que la promotion
        // directe par POST /auth-users/{id}/roles/SUPER_ADMIN etait refusee.
        // Meme escalade de privilege, autre porte.
        for (UserRole role : request.getRoles()) {
            Mono<UserWithRolesResponse> refus = refuserSiNonAttribuable(role, request.getEmail());
            if (refus != null) {
                return refus;
            }
        }

        UserRole primaryRole = request.getPrimaryRole() != null
                ? request.getPrimaryRole()
                : request.getRoles().iterator().next();

        return ensureRolesAvailable(request)
                // Le hachage est porte sur boundedElastic par
                // IMotDePasseService : 241 ms de BCrypt sur un EventLoop
                // Netty immobilisaient un thread de traitement par creation
                // de compte.
                .then(Mono.defer(() -> motDePasseService.chiffrer(request.getPassword())))
                .flatMap(empreinte -> userRepository.save(User.builder()
                        .email(request.getEmail())
                        .phoneNumber(request.getPhoneNumber())
                        .countryCode(request.getCountryCode())
                        .firstName(request.getFirstName())
                        .lastName(request.getLastName())
                        .passwordHash(empreinte)
                        .role(primaryRole)
                        .isPhoneVerified(false)
                        .isEmailVerified(false)
                        .isActive(true)
                        .isLocked(false)
                        .failedLoginAttempts(0)
                        .build()))
                .doOnSuccess(user -> log.info(
                        "Utilisateur créé avec succès dans auth-service: userId={}", user.getId()))
                .flatMap(user -> Flux.fromIterable(request.getRoles())
                        .concatMap(role -> roleAssignmentRepository.save(
                                        assignment(user.getId(), request.getEmail(),
                                                request.getPhoneNumber(), role, role.equals(primaryRole)))
                                .doOnSuccess(saved -> log.info("Rôle {} affecté à l'utilisateur {}",
                                        role.name(), user.getId())))
                        // La synchronisation Feign vers user-service a ete
                        // retiree : elle recopiait courriel, numero, nom et
                        // roles dans un service qui n'a aucune raison de les
                        // conserver. user-service ne garde que l'identifiant du
                        // compte et lit le reste a la demande, par UserMapper.
                        //
                        // Les roles restent donc detenus ICI, et voyagent dans
                        // le jeton.
                        //
                        // `.then()` sans argument est necessaire : la chaine est
                        // un Flux d'affectations, et `thenReturn` n'existe que
                        // sur Mono. C'est `.then(sync(...))` qui assurait
                        // jusqu'ici la bascule Flux -> Mono.
                        .then()
                        .thenReturn(userRoleMapper.toUserWithRolesResponse(
                                user, request.getRoles(), primaryRole)));
    }

    /**
     * Vérifie qu'aucun couple (email, rôle) ou (téléphone, rôle) demandé n'existe déjà.
     */
    private Mono<Void> ensureRolesAvailable(CreateUserWithRolesRequest request) {
        return Flux.fromIterable(request.getRoles())
                .concatMap(role -> roleAssignmentRepository
                        .existsByEmailAndRole(request.getEmail(), role)
                        .flatMap(emailTaken -> {
                            if (Boolean.TRUE.equals(emailTaken)) {
                                return Mono.error(new ResourceAlreadyExistsException(String.format(
                                        "Un utilisateur avec l'email %s et le rôle %s existe déjà",
                                        request.getEmail(), role.name())));
                            }
                            if (request.getPhoneNumber() == null) {
                                return Mono.empty();
                            }
                            return roleAssignmentRepository
                                    .existsByPhoneNumberAndRole(request.getPhoneNumber(), role)
                                    .flatMap(phoneTaken -> Boolean.TRUE.equals(phoneTaken)
                                            ? Mono.error(new ResourceAlreadyExistsException(String.format(
                                                    "Un utilisateur avec le téléphone %s et le rôle %s existe déjà",
                                                    request.getPhoneNumber(), role.name())))
                                            : Mono.empty());
                        }))
                .then();
    }

    private UserRoleAssignment assignment(String userId, String email, String phoneNumber,
                                          UserRole role, boolean primary) {
        return UserRoleAssignment.builder()
                .userId(userId)
                .email(email)
                .phoneNumber(phoneNumber)
                .role(role)
                .isPrimary(primary)
                .isActive(true)
                .assignedAt(LocalDateTime.now())
                .build();
    }

    @Override
    @Transactional
    public Mono<UserWithRolesResponse> addRoleToUser(String userId, UserRole role) {
        // SUPER_ADMIN n'est jamais attribuable par l'API. Sans ce controle, un
        // ADMIN compromis se promouvrait lui-meme : le pouvoir de distribuer les
        // roles inclurait celui de s'octroyer le plus eleve, et la distinction
        // entre les deux ne servirait plus a rien.
        //
        // Le controle est ici, dans le service, et non seulement dans une
        // annotation du controleur : une regle verifiee a un seul endroit est
        // une regle qu'on contourne en ajoutant un second point d'entree.
        Mono<UserWithRolesResponse> refus = refuserSiNonAttribuable(role, userId);
        if (refus != null) {
            return refus;
        }

        log.info("Ajout du rôle {} à l'utilisateur {}", role.name(), userId);

        return requireUser(userId)
                .flatMap(user -> roleAssignmentRepository.existsByEmailAndRole(user.getEmail(), role)
                        .flatMap(exists -> Boolean.TRUE.equals(exists)
                                ? Mono.<UserRoleAssignment>error(new ResourceAlreadyExistsException(
                                        String.format("L'utilisateur possède déjà le rôle %s", role.name())))
                                : roleAssignmentRepository.save(assignment(
                                        userId, user.getEmail(), user.getPhoneNumber(), role, false)))
                        .doOnSuccess(saved -> log.info("Rôle {} ajouté avec succès à l'utilisateur {}",
                                role.name(), userId))
                        .then(activeRoles(userId))
                        .flatMap(roles -> syncRoleUpdateWithUserService(userId, roles)
                                .thenReturn(userRoleMapper.toUserWithRolesResponse(
                                        user, roles, user.getRole()))));
    }

    @Override
    @Transactional
    public Mono<UserWithRolesResponse> removeRoleFromUser(String userId, UserRole role) {
        log.info("Suppression du rôle {} de l'utilisateur {}", role.name(), userId);

        return requireUser(userId)
                .flatMap(user -> roleAssignmentRepository
                        .findActiveByEmailAndRole(user.getEmail(), role.name())
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                                String.format("Rôle %s non trouvé pour l'utilisateur", role.name()))))
                        .flatMap(assignment -> roleAssignmentRepository.findActiveRolesByUserId(userId)
                                .count()
                                .flatMap(activeCount -> {
                                    // Un utilisateur doit toujours conserver au moins un rôle,
                                    // et son rôle principal ne peut pas être retiré directement.
                                    if (activeCount == 1) {
                                        return Mono.error(new IllegalArgumentException(
                                                "Impossible de supprimer le dernier rôle de l'utilisateur"));
                                    }
                                    if (Boolean.TRUE.equals(assignment.getIsPrimary())) {
                                        return Mono.error(new IllegalArgumentException(
                                                "Impossible de supprimer le rôle principal. "
                                                        + "Changez d'abord le rôle principal."));
                                    }
                                    assignment.setIsActive(false);
                                    return roleAssignmentRepository.save(assignment);
                                }))
                        .doOnSuccess(saved -> log.info("Rôle {} supprimé avec succès de l'utilisateur {}",
                                role.name(), userId))
                        .then(activeRoles(userId))
                        .flatMap(roles -> syncRoleUpdateWithUserService(userId, roles)
                                .thenReturn(userRoleMapper.toUserWithRolesResponse(
                                        user, roles, user.getRole()))));
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<UserWithRolesResponse> getUserWithRoles(String userId) {
        return requireUser(userId)
                .flatMap(user -> activeRoles(userId)
                        .map(roles -> userRoleMapper.toUserWithRolesResponse(user, roles, user.getRole())));
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<UserWithRolesResponse> getUsersByRole(UserRole role) {
        return roleAssignmentRepository.findAll()
                .filter(a -> a.getRole().equals(role) && Boolean.TRUE.equals(a.getIsActive()))
                .concatMap(assignment -> userRepository.findById(assignment.getUserId())
                        .flatMap(user -> activeRoles(user.getId())
                                .map(roles -> userRoleMapper.toUserWithRolesResponse(
                                        user, roles, user.getRole()))));
    }

    @Override
    @Transactional
    public Mono<UserWithRolesResponse> changePrimaryRole(String userId, UserRole newPrimaryRole) {
        log.info("Changement du rôle principal de l'utilisateur {} vers {}", userId, newPrimaryRole.name());

        return requireUser(userId)
                .flatMap(user -> roleAssignmentRepository
                        .findActiveByEmailAndRole(user.getEmail(), newPrimaryRole.name())
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException(String.format(
                                "L'utilisateur ne possède pas le rôle %s", newPrimaryRole.name()))))
                        .thenMany(roleAssignmentRepository.findActiveRolesByUserId(userId))
                        .concatMap(assignment -> {
                            assignment.setIsPrimary(assignment.getRole().equals(newPrimaryRole));
                            return roleAssignmentRepository.save(assignment);
                        })
                        .map(UserRoleAssignment::getRole)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet<UserRole>::new))
                        .flatMap(roles -> {
                            user.setRole(newPrimaryRole);
                            return userRepository.save(user)
                                    .doOnSuccess(saved -> log.info(
                                            "Rôle principal changé avec succès pour l'utilisateur {}", userId))
                                    .then(syncRoleUpdateWithUserService(userId, roles))
                                    .thenReturn(userRoleMapper.toUserWithRolesResponse(
                                            user, roles, newPrimaryRole));
                        }));
    }

    private Mono<User> requireUser(String userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                        String.format(AppConstants.USER_NOT_FOUND_MESSAGE, userId))));
    }

    private Mono<Set<UserRole>> activeRoles(String userId) {
        return roleAssignmentRepository.findActiveRolesByUserId(userId)
                .map(UserRoleAssignment::getRole)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet<UserRole>::new));
    }

    // `syncWithUserService` a ete supprimee : elle recopiait courriel, numero,
    // nom, role et roles multiples dans user-service par un appel Feign.
    //
    // Deux raisons de la retirer, pas une. D'abord elle n'etait appelee par
    // personne. Ensuite ce qu'elle faisait est exactement ce qu'il ne faut pas
    // faire : recopier une identite que CE service detient dans un autre, qui
    // n'a aucune raison de la conserver. user-service lit ces champs a la
    // demande, par `UserMapper`, et ne garde que l'identifiant.

    /**
     * Point d'extension : la propagation des rôles vers user-service n'est pas
     * encore implémentée côté client Feign (l'endpoint n'existe pas).
     */
    private Mono<Void> syncRoleUpdateWithUserService(String userId, Set<UserRole> roles) {
        log.info("Synchronisation des rôles avec user-service pour userId={} ({} rôles)",
                userId, roles.size());
        return Mono.empty();
    }

}
