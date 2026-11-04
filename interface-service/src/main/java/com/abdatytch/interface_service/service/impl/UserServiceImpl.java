package com.abdatytch.interface_service.service.impl;

import com.abdatytch.commons.security.EmpreinteDeSecret;
import com.abdatytch.commons.security.SecretMasking;
import com.abdatytch.interface_service.constants.ApplicationConstants;
import com.abdatytch.interface_service.dto.request.LoginRequest;
import com.abdatytch.interface_service.dto.request.RegisterRequest;
import com.abdatytch.interface_service.dto.request.ResetPasswordWithOtpRequest;
import com.abdatytch.interface_service.dto.request.VerifyEmailRequest;
import com.abdatytch.interface_service.dto.request.VerifyPhoneRequest;
import com.abdatytch.interface_service.dto.response.UserResponse;
import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.exception.BusinessException;
import com.abdatytch.interface_service.mapper.UserMapper;
import com.abdatytch.interface_service.metrics.ProjectMetrics;
import com.abdatytch.interface_service.repository.UserRepository;
import com.abdatytch.interface_service.service.EmailService;
import com.abdatytch.interface_service.service.IDevJwtService;
import com.abdatytch.interface_service.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Implémentation du service utilisateur avec cache Redis et résilience
 */
@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ProjectMetrics metrics;
    private final EmailService emailService;
    private final IDevJwtService devJwtService;

    public UserServiceImpl(UserRepository userRepository, UserMapper userMapper,
                          PasswordEncoder passwordEncoder, ReactiveRedisTemplate<String, Object> redisTemplate,
                          ProjectMetrics metrics, EmailService emailService, IDevJwtService devJwtService) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.emailService = emailService;
        this.devJwtService = devJwtService;
    }

    @Override
    public Mono<UserResponse> register(RegisterRequest request) {
        log.info("Inscription d'un nouvel utilisateur: {}", request.getEmail());
        
        var timer = metrics.startUserRegistrationTimer();
        
        return validateRegistrationRequest(request)
                .then(checkUserExists(request.getEmail()))
                .filter(exists -> !exists)
                .switchIfEmpty(Mono.error(new BusinessException("Un utilisateur avec cet email existe déjà", "USER_ALREADY_EXISTS")))
                .then(createUser(request))
                .flatMap(this::saveUserWithCache)
                .flatMap(savedUser ->
                    emailService.sendEmailOtp(savedUser.getEmail(), savedUser.getEmailOtpCode(), savedUser.getFirstName())
                        .doOnSuccess(unused -> log.info("Code OTP envoyé par email à: {}", savedUser.getEmail()))
                        .doOnError(error -> log.error("Erreur envoi OTP email: {}", error.getMessage()))
                        .onErrorResume(error -> Mono.empty()) // Continue même si l'email échoue
                        .thenReturn(savedUser)
                )
                .map(userMapper::toResponse)
                .doOnSuccess(response -> {
                    metrics.stopUserRegistrationTimer(timer);
                    metrics.incrementUserRegistered();
                    log.info("Utilisateur inscrit avec succès: {}", response.getId());
                })
                .doOnError(error -> {
                    metrics.stopUserRegistrationTimer(timer);
                    log.error("Erreur lors de l'inscription: {}", error.getMessage());
                })
                .retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(throwable -> !(throwable instanceof BusinessException)));
    }

    @Override
    public Mono<Map<String, Object>> login(LoginRequest request) {
        log.info("Tentative de connexion pour: {}", request.getIdentifier());

        // Essayer d'abord par email, puis par téléphone
        Mono<User> userMono;
        if (request.isEmail()) {
            log.debug("Tentative de connexion par email: {}", request.getIdentifier());
            userMono = userRepository.findByEmail(request.getIdentifier())
                    .switchIfEmpty(Mono.defer(() -> {
                        log.debug("Email non trouvé, tentative par téléphone: {}", request.getIdentifier());
                        return userRepository.findByPhoneNumber(request.getIdentifier());
                    }));
        } else if (request.isPhoneNumber()) {
            log.debug("Tentative de connexion par téléphone: {}", request.getIdentifier());
            userMono = userRepository.findByPhoneNumber(request.getIdentifier());
        } else {
            log.warn("Format d'identifiant invalide: {}", request.getIdentifier());
            return Mono.error(new BusinessException("Format d'identifiant invalide", "INVALID_IDENTIFIER"));
        }

        return userMono
                .switchIfEmpty(Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND")))
                .flatMap(user -> cacheUser(user).thenReturn(user))
                .flatMap(user -> {
                    // Empêcher l'admin de se connecter via /auth/login
                    if ("ADMIN".equals(user.getRole())) {
                        return Mono.error(new BusinessException("Identifiants invalides", "INVALID_CREDENTIALS"));
                    }
                    return Mono.just(user);
                })
                .flatMap(user -> validateLogin(user, request.getPassword()))
                .flatMap(this::updateLastLoginAndCache)
                .flatMap(user -> Mono.fromCallable(() -> {
                    // Générer les tokens JWT signés RSA
                    String accessToken = devJwtService.generateToken(user);
                    String refreshToken = devJwtService.generateRefreshToken(user);
                    long expiresIn = devJwtService.getAccessTokenExpiration();

                    UserResponse userResponse = userMapper.toResponse(user);

                    return Map.of(
                        "accessToken", accessToken,
                        "refreshToken", refreshToken,
                        "expiresIn", expiresIn,
                        "tokenType", "Bearer",
                        "user", userResponse
                    );
                })
                // LA CAUSE RACINE DU RAFRAICHISSEMENT MORT.
                //
                // La connexion emettait un jeton de rafraichissement et ne le
                // persistait NULLE PART : `updateLastLoginAndCache` n'ecrit que
                // `lastLoginAt`, et `generateRefreshToken` ne fait que signer.
                // `updateRefreshToken` n'avait qu'un seul appelant — le flux de
                // rafraichissement lui-meme. Le premier rafraichissement ne
                // pouvait donc rien trouver, et rendait une erreur.
                //
                // L'empreinte est enregistree ICI, a l'emission. C'est le seul
                // moment ou l'on detient a la fois le compte et le jeton.
                .flatMap(reponse -> updateRefreshToken(
                            user.getId(), (String) reponse.get("refreshToken"))
                        .thenReturn(reponse)))
                .doOnSuccess(response -> {
                    UserResponse userResponse = (UserResponse) response.get("user");
                    metrics.incrementUserLogin(userResponse.getId().toString());
                    log.info("Connexion réussie pour l'utilisateur: {} (via {})", userResponse.getId(), request.isEmail() ? "email" : "téléphone");
                })
                .doOnError(error -> log.warn("Échec de connexion pour: {} - {}", request.getIdentifier(), error.getMessage()));
    }

    @Override
    public Mono<UserResponse> findById(UUID userId) {
        log.debug("Recherche utilisateur par ID: {}", userId);
        
        String cacheKey = ApplicationConstants.CACHE_USER_PREFIX + userId;
        
        return redisTemplate.opsForValue()
                .get(cacheKey)
                .cast(User.class)
                .doOnNext(user -> log.debug("Utilisateur trouvé dans le cache: {}", userId))
                .switchIfEmpty(
                    userRepository.findById(userId)
                            .doOnNext(user -> log.debug("Utilisateur trouvé en base: {}", userId))
                            .flatMap(user -> cacheUser(user).thenReturn(user))
                )
                .map(userMapper::toResponse);
    }

    @Override
    public Mono<User> findByEmail(String email) {
        log.debug("Recherche utilisateur par email: {}", email);
        
        return userRepository.findByEmail(email)
                .doOnNext(user -> log.debug("Utilisateur trouvé par email: {}", user.getId()));
    }

    @Override
    public Mono<UserResponse> updateProfile(UUID userId, UserResponse userUpdate) {
        log.info("Mise à jour du profil utilisateur: {}", userId);
        
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND")))
                .flatMap(user -> {
                    // Mise à jour des champs modifiables
                    if (userUpdate.getFirstName() != null) {
                        user.setFirstName(userUpdate.getFirstName());
                    }
                    if (userUpdate.getLastName() != null) {
                        user.setLastName(userUpdate.getLastName());
                    }
                    if (userUpdate.getPhoneNumber() != null) {
                        user.setPhoneNumber(userUpdate.getPhoneNumber());
                        user.setPhoneVerified(false); // Reverifier le nouveau numéro
                    }
                    
                    return saveUserWithCache(user);
                })
                .map(userMapper::toResponse)
                .doOnSuccess(response -> log.info("Profil mis à jour pour l'utilisateur: {}", userId));
    }

    @Override
    public Mono<Void> verifyEmail(VerifyEmailRequest request) {
        log.info("Vérification de l'email avec OTP pour: {}", request.getEmail());

        return userRepository.findByEmail(request.getEmail())
                .switchIfEmpty(Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND")))
                .flatMap(user -> {
                    // Empêcher l'admin de vérifier son email
                    if ("ADMIN".equals(user.getRole())) {
                        return Mono.error(new BusinessException("Code de vérification invalide", "INVALID_OTP"));
                    }

                    // Vérifier si le compte est verrouillé
                    return checkAccountLock(user)
                            .then(Mono.defer(() -> {
                                // Vérifier si le code OTP est valide
                                if (user.getEmailOtpCode() == null || !user.getEmailOtpCode().equals(request.getOtpCode())) {
                                    return handleFailedAttempt(user)
                                            .then(Mono.error(new BusinessException("Code de vérification invalide", "INVALID_OTP")));
                                }

                                // Vérifier si le code OTP n'a pas expiré
                                if (user.getEmailOtpExpiresAt() == null || user.getEmailOtpExpiresAt().isBefore(LocalDateTime.now())) {
                                    return Mono.error(new BusinessException("Code de vérification expiré", "OTP_EXPIRED"));
                                }

                                user.setEmailVerified(true);
                                user.setEmailOtpCode(null);
                                user.setEmailOtpExpiresAt(null);
                                user.resetFailedLoginAttempts(); // Réinitialiser les tentatives après succès
                                return saveUserWithCache(user);
                            }));
                })
                .then()
                .doOnSuccess(unused -> log.info("Email vérifié avec succès pour: {}", request.getEmail()));
    }

    @Override
    public Mono<Void> verifyPhone(VerifyPhoneRequest request) {
        log.info("Vérification de téléphone avec OTP pour: {}", request.getPhoneNumber());

        return userRepository.findByPhoneNumber(request.getPhoneNumber())
                .switchIfEmpty(Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND")))
                .flatMap(user -> {
                    // Empêcher l'admin de vérifier son téléphone
                    if ("ADMIN".equals(user.getRole())) {
                        return Mono.error(new BusinessException("Code de vérification invalide", "INVALID_OTP"));
                    }

                    // Vérifier si le compte est verrouillé
                    return checkAccountLock(user)
                            .then(Mono.defer(() -> {
                                // Vérifier si le code OTP est valide
                                if (user.getPhoneOtpCode() == null || !user.getPhoneOtpCode().equals(request.getOtpCode())) {
                                    return handleFailedAttempt(user)
                                            .then(Mono.error(new BusinessException("Code de vérification invalide", "INVALID_OTP")));
                                }

                                // Vérifier si le code OTP n'a pas expiré
                                if (user.getPhoneOtpExpiresAt() == null || user.getPhoneOtpExpiresAt().isBefore(LocalDateTime.now())) {
                                    return Mono.error(new BusinessException("Code de vérification expiré", "OTP_EXPIRED"));
                                }

                                user.setPhoneVerified(true);
                                user.setPhoneOtpCode(null);
                                user.setPhoneOtpExpiresAt(null);
                                user.resetFailedLoginAttempts(); // Réinitialiser les tentatives après succès
                                return saveUserWithCache(user);
                            }));
                })
                .then()
                .doOnSuccess(unused -> log.info("Téléphone vérifié avec succès pour: {}", request.getPhoneNumber()));
    }

    @Override
    public Mono<Void> requestEmailOtp(String email) {
        log.info("Demande de code OTP pour email: {}", email);

        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND")))
                .flatMap(user -> {
                    // Empêcher l'admin de demander un OTP
                    if ("ADMIN".equals(user.getRole())) {
                        return Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND"));
                    }

                    // Vérifier si le compte est verrouillé
                    return checkAccountLock(user)
                            .then(Mono.defer(() -> {
                                // Générer un code OTP à 6 chiffres
                                String otpCode = generateOtpCode();
                                user.setEmailOtpCode(otpCode);
                                user.setEmailOtpExpiresAt(LocalDateTime.now().plusMinutes(10)); // Expire après 10 minutes

                                return saveUserWithCache(user)
                                        .flatMap(savedUser ->
                                            emailService.sendEmailOtp(email, otpCode, savedUser.getFirstName())
                                                .doOnSuccess(unused -> log.info("Code OTP envoyé par email à: {}", email))
                                                .doOnError(error -> log.error("Erreur envoi OTP email: {}", error.getMessage()))
                                                .onErrorResume(error -> Mono.empty())
                                                .thenReturn(savedUser)
                                        );
                            }));
                })
                .then();
    }

    @Override
    public Mono<Void> requestPhoneOtp(String phoneNumber) {
        log.info("Demande de code OTP pour téléphone: {}", phoneNumber);

        return userRepository.findByPhoneNumber(phoneNumber)
                .switchIfEmpty(Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND")))
                .flatMap(user -> {
                    // Empêcher l'admin de demander un OTP
                    if ("ADMIN".equals(user.getRole())) {
                        return Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND"));
                    }

                    // Vérifier si le compte est verrouillé
                    return checkAccountLock(user)
                            .then(Mono.defer(() -> {
                                // Générer un code OTP à 6 chiffres
                                String otpCode = generateOtpCode();
                                user.setPhoneOtpCode(otpCode);
                                user.setPhoneOtpExpiresAt(LocalDateTime.now().plusMinutes(10)); // Expire après 10 minutes

                                return saveUserWithCache(user)
                                        .flatMap(savedUser ->
                                            // TODO: Implémenter l'envoi de SMS avec le code OTP
                                            Mono.fromCallable(() -> {
                                                // Le CODE n'est pas journalise.
                                                //
                                                // Il l'etait avec le numero de
                                                // destination : tout ce qu'il faut
                                                // pour prendre le compte, a INFO et
                                                // sans condition. Aucun masquage par
                                                // empreinte n'est possible sur six
                                                // chiffres — voir
                                                // SecretMasking.masquerCodeCourt.
                                                //
                                                // Le filet d'appender de ce service
                                                // ne l'attrapait pas : son motif
                                                // exige « token », « secret » ou
                                                // « password » adjacent au
                                                // separateur, et un OTP n'en porte
                                                // aucun.
                                                log.info("Code OTP généré (SMS non implémenté) pour: {}",
                                                        phoneNumber);
                                                return savedUser;
                                            })
                                                .doOnSuccess(unused -> log.info("Code OTP généré pour téléphone: {}", phoneNumber))
                                        );
                            }));
                })
                .then();
    }

    /**
     * Génère un code OTP à 6 chiffres
     */
    private String generateOtpCode() {
        SecureRandom random = new SecureRandom();
        return String.format("%06d", random.nextInt(1000000));
    }

    @Override
    public Mono<Void> requestPasswordReset(String email) {
        log.info("Demande de réinitialisation de mot de passe pour: {}", email);

        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND")))
                .flatMap(user -> {
                    // Empêcher l'admin de demander une réinitialisation via /auth/request-password-reset
                    if ("ADMIN".equals(user.getRole())) {
                        return Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND"));
                    }

                    // Vérifier si le compte est verrouillé
                    return checkAccountLock(user)
                            .then(Mono.defer(() -> {
                                String otpCode = generateOtpCode();
                                user.setPasswordResetOtpCode(otpCode);
                                user.setPasswordResetOtpExpiresAt(LocalDateTime.now().plusMinutes(10));

                                return saveUserWithCache(user)
                                        .flatMap(savedUser ->
                                            emailService.sendPasswordResetOtp(email, otpCode, savedUser.getFirstName())
                                                .doOnSuccess(unused -> log.info("Code OTP de réinitialisation envoyé à: {}", email))
                                                .doOnError(error -> log.error("Erreur envoi OTP réinitialisation: {}", error.getMessage()))
                                                .onErrorResume(error -> Mono.empty()) // Continue même si l'email échoue
                                                .thenReturn(savedUser)
                                        );
                            }));
                })
                .then();
    }

    @Override
    public Mono<Void> resetPassword(String token, String newPassword) {
        log.info("Réinitialisation de mot de passe, jeton {}", SecretMasking.masquer(token));

        return userRepository.findByPasswordResetToken(token)
                .switchIfEmpty(Mono.error(new BusinessException("Token de réinitialisation invalide ou expiré", "INVALID_TOKEN")))
                .flatMap(user -> {
                    // Empêcher l'admin de réinitialiser son mot de passe via /auth/reset-password
                    if ("ADMIN".equals(user.getRole())) {
                        return Mono.error(new BusinessException("Token de réinitialisation invalide ou expiré", "INVALID_TOKEN"));
                    }
                    user.setPasswordHash(passwordEncoder.encode(newPassword));
                    user.setPasswordResetToken(null);
                    user.setPasswordResetExpiresAt(null);
                    user.resetFailedLoginAttempts();

                    return saveUserWithCache(user);
                })
                .then()
                .doOnSuccess(unused -> log.info("Mot de passe réinitialisé avec succès"));
    }

    @Override
    public Mono<Void> resetPasswordWithOtp(ResetPasswordWithOtpRequest request) {
        log.info("Réinitialisation de mot de passe avec OTP pour: {}", request.getEmail());

        return userRepository.findByEmail(request.getEmail())
                .switchIfEmpty(Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND")))
                .flatMap(user -> {
                    // Empêcher l'admin de réinitialiser son mot de passe via /auth/reset-password-otp
                    if ("ADMIN".equals(user.getRole())) {
                        return Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND"));
                    }

                    // Vérifier si le compte est verrouillé
                    return checkAccountLock(user)
                            .then(Mono.defer(() -> {
                                // Vérifier que le code OTP est valide et n'est pas expiré
                                if (user.getPasswordResetOtpCode() == null || !user.getPasswordResetOtpCode().equals(request.getOtpCode())) {
                                    return handleFailedAttempt(user)
                                            .then(Mono.error(new BusinessException("Code OTP invalide", "INVALID_OTP")));
                                }
                                if (user.getPasswordResetOtpExpiresAt() == null || user.getPasswordResetOtpExpiresAt().isBefore(LocalDateTime.now())) {
                                    return Mono.error(new BusinessException("Code OTP expiré", "EXPIRED_OTP"));
                                }

                                // Réinitialiser le mot de passe
                                user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
                                user.setPasswordResetOtpCode(null);
                                user.setPasswordResetOtpExpiresAt(null);
                                user.resetFailedLoginAttempts(); // Réinitialiser les tentatives après succès

                                return saveUserWithCache(user);
                            }));
                })
                .then()
                .doOnSuccess(unused -> log.info("Mot de passe réinitialisé avec succès pour: {}", request.getEmail()));
    }

    @Override
    public Mono<Void> updateLastLogin(UUID userId) {
        return userRepository.findById(userId)
                .flatMap(user -> {
                    user.setLastLoginAt(LocalDateTime.now());
                    return saveUserWithCache(user);
                })
                .then();
    }

    // ========== MÉTHODES PRIVÉES ==========

    /**
     * Valide la demande d'inscription
     */
    private Mono<Void> validateRegistrationRequest(RegisterRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            return Mono.error(new BusinessException("Les mots de passe ne correspondent pas", "PASSWORD_MISMATCH"));
        }
        if (!Boolean.TRUE.equals(request.getAcceptTerms())) {
            return Mono.error(new BusinessException("Vous devez accepter les conditions d'utilisation", "TERMS_NOT_ACCEPTED"));
        }
        return Mono.empty();
    }

    /**
     * Vérifie si un utilisateur existe déjà
     */
    private Mono<Boolean> checkUserExists(String email) {
        return userRepository.existsByEmail(email)
                .doOnNext(exists -> log.debug("Utilisateur existe déjà: {}", exists));
    }

    /**
     * Crée un nouvel utilisateur
     */
    private Mono<User> createUser(RegisterRequest request) {
        User user = userMapper.toEntity(request);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        // Générer un code OTP pour l'email au lieu d'un token
        String otpCode = generateOtpCode();
        user.setEmailOtpCode(otpCode);
        user.setEmailOtpExpiresAt(LocalDateTime.now().plusMinutes(10)); // Expire après 10 minutes

        return Mono.just(user);
    }

    /**
     * Trouve un utilisateur par email ou username avec cache
     */
    private Mono<User> findByEmailOrUsername(String identifier) {
        return userRepository.findByEmailOrUsername(identifier)
                .flatMap(user -> cacheUser(user).thenReturn(user));
    }

    /**
     * Vérifie si le compte est verrouillé et retourne une erreur si c'est le cas
     */
    private Mono<Void> checkAccountLock(User user) {
        if (user.isAccountLocked()) {
            // Vérifier si le verrouillage est permanent (niveau 3)
            if (user.getFailedLoginAttempts() >= 8) {
                return Mono.error(new BusinessException("Compte définitivement verrouillé suite à trop de tentatives. Contactez le support.", "ACCOUNT_PERMANENTLY_LOCKED"));
            }
            // Calculer le temps restant
            long minutesRemaining = java.time.Duration.between(LocalDateTime.now(), user.getAccountLockedUntil()).toMinutes();
            return Mono.error(new BusinessException("Compte temporairement verrouillé. Réessayez dans " + minutesRemaining + " minutes.", "ACCOUNT_LOCKED"));
        }
        return Mono.empty();
    }

    /**
     * Incrémente les tentatives échouées et applique le verrouillage progressif
     */
    private Mono<Void> handleFailedAttempt(User user) {
        user.incrementFailedLoginAttempts();
        int attempts = user.getFailedLoginAttempts();

        if (attempts == 4) {
            // Niveau 1 : Verrouillage 3 minutes
            user.lockAccount(3);
        } else if (attempts == 7) {
            // Niveau 2 : Verrouillage 10 minutes
            user.lockAccount(10);
        } else if (attempts >= 8) {
            // Niveau 3 : Verrouillage permanent (24 heures pour éviter le blocage infini)
            user.lockAccount(1440);
        }

        return userRepository.save(user).then();
    }

    /**
     * Valide les informations de connexion avec protection contre le brute force
     * Niveau 0 (0-3 tentatives) : Pas de restriction
     * Niveau 1 (4 tentatives) : Verrouillage 3 minutes
     * Niveau 2 (après déblocage niveau 1, 3 tentatives supplémentaires = 7 au total) : Verrouillage 10 minutes
     * Niveau 3 (après déblocage niveau 2, 1 tentative supplémentaire = 8 au total) : Verrouillage permanent
     */
    private Mono<User> validateLogin(User user, String password) {
        return checkAccountLock(user)
                .then(Mono.defer(() -> {
                    if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                        return handleFailedAttempt(user)
                                .then(Mono.error(new BusinessException("Identifiants incorrects", "INVALID_CREDENTIALS")));
                    }

                    user.resetFailedLoginAttempts();
                    return Mono.just(user);
                }));
    }

    /**
     * Met à jour la dernière connexion et le cache
     */
    private Mono<User> updateLastLoginAndCache(User user) {
        user.setLastLoginAt(LocalDateTime.now());
        return saveUserWithCache(user);
    }

    /**
     * Sauvegarde un utilisateur en base et en cache
     */
    private Mono<User> saveUserWithCache(User user) {
        return userRepository.save(user)
                .flatMap(savedUser -> cacheUser(savedUser).thenReturn(savedUser))
                .doOnSuccess(savedUser -> log.debug("Utilisateur sauvegardé et mis en cache: {}", savedUser.getId()));
    }

    /**
     * Met un utilisateur en cache Redis
     */
    private Mono<Void> cacheUser(User user) {
        String cacheKey = ApplicationConstants.CACHE_USER_PREFIX + user.getId();
        
        return redisTemplate.opsForValue()
                .set(cacheKey, user, Duration.ofSeconds(ApplicationConstants.CACHE_TTL_USER))
                .doOnSuccess(success -> log.debug("Utilisateur mis en cache: {}", user.getId()))
                .then();
    }

    /**
     * Retrouve un compte a partir du jeton de rafraichissement PRESENTE.
     *
     * <p>Le jeton est transforme en empreinte avant la recherche : la base ne
     * detient que l'empreinte depuis la migration V9. Elle interrogeait
     * auparavant la colonne en clair, ajoutee par V3 a cote de la colonne
     * d'empreinte creee par V1 — deux magasins pour un meme etat, et c'est le
     * dangereux qui servait.
     */
    @Override
    public Mono<UserResponse> findByRefreshToken(String refreshToken) {
        log.debug("Recherche utilisateur par empreinte du refresh token");

        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new BusinessException(
                    "Refresh token invalide", "INVALID_REFRESH_TOKEN"));
        }

        return userRepository.findByRefreshTokenHash(EmpreinteDeSecret.empreinte(refreshToken))
                .switchIfEmpty(Mono.error(new BusinessException("Refresh token invalide", "INVALID_REFRESH_TOKEN")))
                .flatMap(user -> cacheUser(user).thenReturn(user))
                .map(userMapper::toResponse);
    }

    /**
     * Echange un jeton de rafraichissement contre une nouvelle paire RS256.
     *
     * <p>La rotation est <b>complete</b> : le jeton presente devient inutilisable
     * des que le nouveau est enregistre, parce que l'empreinte stockee est
     * remplacee. Sans cela, un jeton intercepte resterait valable jusqu'a son
     * expiration en parallele du nouveau.
     *
     * <p>Les jetons sont frappes par {@code devJwtService}, comme a la
     * connexion. Le controleur en signait de son cote en HMAC256, que personne
     * ne pouvait valider.
     */
    @Override
    public Mono<Map<String, Object>> refreshTokens(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new BusinessException(
                    "Refresh token invalide", "INVALID_REFRESH_TOKEN"));
        }

        return userRepository.findByRefreshTokenHash(EmpreinteDeSecret.empreinte(refreshToken))
                .switchIfEmpty(Mono.error(new BusinessException(
                        "Refresh token invalide", "INVALID_REFRESH_TOKEN")))
                .flatMap(user -> Mono.fromCallable(() -> {
                            String accessToken = devJwtService.generateToken(user);
                            String nouveauRafraichissement = devJwtService.generateRefreshToken(user);
                            return Map.<String, Object>of(
                                    "accessToken", accessToken,
                                    "refreshToken", nouveauRafraichissement,
                                    "expiresIn", devJwtService.getAccessTokenExpiration(),
                                    "tokenType", "Bearer");
                        })
                        .flatMap(reponse -> updateRefreshToken(
                                    user.getId(), (String) reponse.get("refreshToken"))
                                .thenReturn(reponse)))
                .doOnSuccess(unused -> log.info("Paire de jetons renouvelee"));
    }

    /**
     * Enregistre l'EMPREINTE du jeton, jamais le jeton.
     *
     * <p>Le parametre reste le jeton en clair : c'est l'appelant qui l'a, et
     * lui faire calculer l'empreinte reporterait la decision sur chaque site
     * d'appel. Un seul endroit sait comment ce secret est stocke.
     */
    @Override
    public Mono<Void> updateRefreshToken(UUID userId, String refreshToken) {
        log.debug("Mise à jour de l'empreinte du refresh token pour l'utilisateur: {}", userId);

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new BusinessException("Utilisateur non trouvé", "USER_NOT_FOUND")))
                .flatMap(user -> {
                    user.setRefreshTokenHash(EmpreinteDeSecret.empreinte(refreshToken));
                    user.setRefreshTokenExpiresAt(LocalDateTime.now().plusDays(7)); // 7 jours
                    return saveUserWithCache(user);
                })
                .then();
    }

    /**
     * Invalide le jeton de rafraichissement d'un compte.
     *
     * <p>Rend un {@code Mono} vide si aucun compte ne correspond, et c'est
     * voulu : une deconnexion presentant un jeton inconnu ne doit pas renseigner
     * l'appelant sur son existence. Mais le site d'appel doit savoir que
     * « rien n'a ete invalide » et « invalidation reussie » se ressemblent ici —
     * voir le javadoc de la deconnexion dans {@code AuthController}.
     */
    @Override
    public Mono<Void> invalidateRefreshToken(String refreshToken) {
        log.debug("Invalidation du refresh token");

        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.empty();
        }

        return userRepository.findByRefreshTokenHash(EmpreinteDeSecret.empreinte(refreshToken))
                .flatMap(user -> {
                    user.setRefreshTokenHash(null);
                    user.setRefreshTokenExpiresAt(null);
                    return saveUserWithCache(user);
                })
                .then()
                .doOnSuccess(unused -> log.info("Refresh token invalidé"));
    }
}
