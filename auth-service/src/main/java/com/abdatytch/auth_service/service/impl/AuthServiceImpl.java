package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.util.IdentifiantDeCompte;
import com.abdatytch.auth_service.util.PhoneNumbers;
import com.abdatytch.auth_service.client.GatewayGeoLocationClient;
import com.abdatytch.commons.security.SecretMasking;
import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.dto.request.*;
import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.dto.response.VerificationResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import com.abdatytch.auth_service.event.UserCreatedEvent;
import com.abdatytch.auth_service.event.UserLoggedInEvent;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.mappers.AuthResponseMapper;
import com.abdatytch.auth_service.mappers.VerificationResponseMapper;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import com.abdatytch.commons.notification.IMessagerieSortante;

/**
 * Authentification, en réactif de bout en bout.
 *
 * <p>Points d'attention de la migration depuis JPA :
 * <ul>
 *   <li>les {@code orElseThrow} deviennent des {@code switchIfEmpty(Mono.error(...))} ;</li>
 *   <li>l'ordre des contrôles métier est strictement conservé (il porte des
 *       décisions de sécurité : le mot de passe est vérifié <b>avant</b> l'OTP) ;</li>
 *   <li>la publication Kafka et la géolocalisation restent « best effort » : leur
 *       échec ne fait jamais échouer une authentification ;</li>
 *   <li>la journalisation d'un échec est chaînée <b>avant</b> la propagation de
 *       l'erreur, pour ne pas perdre la trace.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements IAuthService {

    /** Préfixe des numéros factices pour les parcours par email seul. */
    private static final String TEMP_PHONE_PREFIX = "temp_";

    private final UserRepository userRepository;
    private final IVerificationCodeService verificationCodeService;
    private final IJwtService jwtService;
    private final IRefreshTokenService refreshTokenService;
    private final IAuthenticationLogService authenticationLogService;
    private final AuthResponseMapper authResponseMapper;
    private final VerificationResponseMapper verificationResponseMapper;
    private final IMotDePasseService motDePasseService;
    private final IEventPublisher eventPublisher;
    private final GatewayGeoLocationClient geoLocationClient;
    private final AuthMetrics authMetrics;
    private final IAccountLockoutService accountLockoutService;
    private final IMessagerieSortante messagerie;
    private final ISecondFacteurService secondFacteurService;

    /**
     * Appareils de confiance : voir {@link IAppareilDeConfianceService}.
     *
     * <p>Consulte sur le chemin de connexion, ce qui est la raison pour laquelle
     * cette donnee vit dans auth-service et non dans security-service — un appel
     * reseau par connexion serait inacceptable, et une panne de ce service
     * empecherait toute connexion.
     */
    private final IAppareilDeConfianceService appareilDeConfianceService;

    /**
     * Alertes de securite : voir {@link IAlerteDeSecuriteService}.
     *
     * <p>Jamais sur le chemin d'une decision : ce service ne refuse rien, il
     * rend visible ce qui serait passe inaperçu. Un echec d'envoi n'interrompt
     * donc aucune operation — mais il est compte, parce qu'une alerte qui ne
     * part plus rend les detournements de compte silencieux a nouveau.
     */
    private final IAlerteDeSecuriteService alerteDeSecuriteService;

    // ==========================================================
    // Connexion par mot de passe
    // ==========================================================

    @Override
    @Transactional
    public Mono<AuthResponse> login(LoginRequest request, String ipAddress, String userAgent) {
        String identifiant = identifiantDe(request);
        log.info(ServiceConstants.LOG_INFO_LOGIN_ATTEMPT, identifiant);

        return IdentifiantDeCompte.resoudre(userRepository, identifiant)
                .switchIfEmpty(Mono.defer(() -> userNotFound(identifiant, ipAddress, userAgent)))
                .flatMap(user -> ensureNotLocked(user)
                        .flatMap(unlocked -> verifyPassword(unlocked, request.getPassword(), ipAddress, userAgent))
                        .flatMap(this::ensureVerifiedAndActive)
                        // Le second facteur est oppose ICI, et il manquait.
                        //
                        // Ce chemin ne l'a jamais verifie : il lisait le mot de
                        // passe, l'etat du compte, et emettait le jeton. Un
                        // compte d'administration dont le second facteur etait
                        // enrole se connectait donc par /auth/login avec le mot
                        // de passe SEUL — le second facteur n'etait oppose que
                        // sur /auth/admin/login, qu'un attaquant n'a aucune
                        // raison d'emprunter.
                        //
                        // Oppose a TOUS les comptes et non aux seuls
                        // administrateurs : la verification ne fait rien quand
                        // le second facteur n'est pas enrole, et une regle
                        // conditionnee a un role est une regle qu'on contourne
                        // en changeant de role.
                        // Un appareil de confiance dispense du second facteur,
                        // et de lui SEUL : le mot de passe a deja ete verifie
                        // plus haut. Un jeton d'appareil vole ne suffit donc
                        // pas a entrer.
                        //
                        // La dispense passe par `verifier` quand le jeton n'est
                        // pas reconnu, ce qui couvre tous les cas douteux —
                        // jeton absent, perime, revoque, ou appartenant a un
                        // autre compte. `estDeConfiance` ne leve jamais : un
                        // jeton invalide signifie « demandez le code », ce qui
                        // est le comportement par defaut.
                        .flatMap(actif -> appareilDeConfianceService
                                .estDeConfiance(actif.getId(), request.getDeviceToken())
                                .flatMap(deConfiance -> Boolean.TRUE.equals(deConfiance)
                                        ? Mono.just(actif)
                                        : secondFacteurService.verifier(
                                                actif, request.getTotpCode(),
                                                ipAddress)))
                        .flatMap(verified -> {
                            verified.setFailedLoginAttempts(0);
                            verified.setLastLogin(LocalDateTime.now());
                            return userRepository.save(verified);
                        })
                        .flatMap(saved -> journaliserEtRendreLaGeo(
                                        saved, EventType.LOGIN, ipAddress, userAgent)
                                .flatMap(geo -> publishLoginEvent(saved, ipAddress, userAgent)
                                        // Alerte de nouvelle connexion, si la
                                        // personne l'a demandee.
                                        //
                                        // Dans la chaine, donc la reponse attend
                                        // l'envoi. C'est assume : le cas courant
                                        // est un reglage inactif, qui se termine
                                        // immediatement sans rien envoyer, et la
                                        // convention de ce service est l'envoi
                                        // chaine — l'inscription attend deja son
                                        // courriel de bienvenue.
                                        .then(alerteDeSecuriteService.nouvelleConnexion(
                                                saved,
                                                com.abdatytch.auth_service.dto
                                                        .ContexteDeSecurite.de(
                                                        ipAddress, userAgent,
                                                        geo.orElse(null))))
                                        .then(Mono.fromRunnable(
                                                () -> authMetrics.recordLoginSuccess("credentials")))
                                        .then(generateAuthResponse(saved, ipAddress, userAgent)))));
    }

    /**
     * Designateur de compte retenu : {@code identifier} s'il est fourni, sinon
     * {@code phoneNumber}.
     *
     * <p>{@code identifier} est prioritaire parce qu'il est le champ explicite :
     * un appelant qui le remplit designe le compte sciemment. {@code phoneNumber}
     * reste accepte pour ne pas casser les integrations existantes du SDK.
     *
     * <p>Rend une chaine vide plutot que {@code null} quand les deux manquent :
     * {@code IdentifiantDeCompte.resoudre} traite ce cas comme un compte inconnu, donc le
     * refus est le refus uniforme. La validation du corps l'a de toute façon
     * deja rejete en 400 (voir {@code LoginRequest.isIdentifiantFourni}) — ce
     * repli n'existe que pour les appels directs au service, dont les tests.
     */
    private String identifiantDe(LoginRequest request) {
        String identifier = request.getIdentifier();
        if (identifier != null && !identifier.isBlank()) {
            return identifier;
        }
        String phoneNumber = request.getPhoneNumber();
        return phoneNumber != null ? phoneNumber : "";
    }

    /**
     * Utilisateur inconnu : on journalise l'échec et on renvoie la même erreur
     * qu'un mot de passe invalide, pour ne pas révéler l'existence du compte.
     */
    private Mono<User> userNotFound(String phoneNumber, String ipAddress, String userAgent) {
        return authenticationLogService.logAuthenticationEvent(
                        null, phoneNumber, EventType.LOGIN, Status.FAILED,
                        ipAddress, userAgent, AppConstants.USER_NOT_FOUND_MESSAGE)
                .then(Mono.fromRunnable(() -> authMetrics.recordLoginFailure("credentials", "user_not_found")))
                .then(Mono.error(new AuthException(ErrorCode.INVALID_CREDENTIALS)));
    }

    /**
     * Verrouillage de compte : refus tant que la période court, déverrouillage
     * automatique une fois échue.
     */
    /**
     * Formule la réponse selon que l'envoi a réellement eu lieu.
     *
     * <p>Annoncer « Code OTP envoyé par SMS » alors que {@code sms.enabled} est
     * faux faisait attendre un message qui ne partait pas. Le code est bien
     * généré : c'est l'<b>envoi</b> qui n'a pas lieu, et la réponse le dit.
     */
    /*
     * `messageDEnvoiOtp()` a disparu.
     *
     * Elle lisait `envoiSmsActif()`, un interrupteur de CONFIGURATION, et ne
     * connaissait donc qu'une des quatre raisons de ne rien envoyer. Un numero
     * hors liste autorisee, un plafond journalier atteint ou un fournisseur mal
     * configure produisaient tous « Code OTP envoyé par SMS » — et la personne
     * attendait un message qui n'arriverait jamais.
     *
     * La reponse est desormais formulee depuis EnvoiDuCode, qui porte ce qui est
     * REELLEMENT parti, canal par canal. La regle « si ce n'est pas parti, le
     * dire » est posee une seule fois, dans ce type.
     */

    private Mono<User> ensureNotLocked(User user) {
        // Delegue : la regle de verrouillage n'a plus qu'un seul detenteur.
        //
        // Cette methode en portait sa propre copie, avec son propre seuil
        // (AppConstants.MAX_FAILED_LOGIN_ATTEMPTS) tandis que
        // AccountLockoutServiceImpl lisait auth.lockout.max-attempts. Deux
        // valeurs a maintenir d'accord pour une meme regle, et deux magasins
        // qui ne se rencontraient jamais : c'est de la que venait un
        // verrouillage administrateur qui ne verrouillait personne.
        return accountLockoutService.assurerNonVerrouille(user);
    }

    /**
     * Verification du mot de passe, portee hors de l'EventLoop.
     *
     * <p>Elle appelait {@code passwordEncoder.matches} en ligne, donc 241 ms de
     * calcul sur un thread d'EventLoop Netty par tentative. Voir
     * {@link IMotDePasseService} pour la mesure et ses consequences.
     */
    private Mono<User> verifyPassword(User user, String rawPassword, String ipAddress, String userAgent) {
        return motDePasseService.correspond(rawPassword, user.getPasswordHash())
                .flatMap(concorde -> {
                    if (Boolean.TRUE.equals(concorde)) {
                        return Mono.just(user);
                    }
                    return handleFailedLogin(user, ipAddress, userAgent)
                            .then(Mono.fromRunnable(() ->
                                    authMetrics.recordLoginFailure("credentials", "invalid_password")))
                            .then(Mono.error(new AuthException(ErrorCode.INVALID_CREDENTIALS)));
                });
    }

    private Mono<User> ensureVerifiedAndActive(User user) {
        if (!Boolean.TRUE.equals(user.getIsPhoneVerified())) {
            return Mono.error(new AuthException(ErrorCode.PHONE_NOT_VERIFIED));
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            return Mono.error(new AuthException(ErrorCode.ACCOUNT_DISABLED));
        }
        return Mono.just(user);
    }

    /**
     * Incrémente le compteur d'échecs et verrouille le compte au seuil atteint.
     */
    private Mono<Void> handleFailedLogin(User user, String ipAddress, String userAgent) {
        // Le COMPTAGE et le verrouillage sont delegues ; ce qui reste ici est
        // la journalisation metier et la metrique, qui n'appartiennent pas au
        // service de verrouillage.
        //
        // enregistrerEchec persiste l'entite : il n'y a plus de `save` ici, et
        // surtout plus de seconde ecriture concurrente sur la meme ligne.
        return accountLockoutService.enregistrerEchec(user)
                .flatMap(verrouille -> {
                    if (!Boolean.TRUE.equals(verrouille)) {
                        return Mono.<Void>empty();
                    }
                    return authenticationLogService.logAuthenticationEvent(
                                    user.getId(), user.getPhoneNumber(),
                                    EventType.ACCOUNT_LOCKED, Status.SUCCESS,
                                    ipAddress, userAgent,
                                    "Compte verrouillé après "
                                            + user.getFailedLoginAttempts()
                                            + " tentatives échouées")
                            // La metrique de verrouillage N'EST PLUS posee ici.
                            //
                            // Elle est passee chez AccountLockoutServiceImpl, qui
                            // detient la regle et par lequel passent tous les
                            // chemins. La laisser ici en plus ferait compter ce
                            // chemin DEUX fois et les autres zero — un ecart plus
                            // trompeur que l'absence de mesure.
                            .then();
                })
                // `then` souscrit a l'etape suivante meme sur une source vide :
                // l'echec se journalise qu'il y ait eu verrouillage ou non.
                .then(authenticationLogService.logAuthenticationEvent(
                        user.getId(), user.getPhoneNumber(), EventType.LOGIN, Status.FAILED,
                        ipAddress, userAgent, "Mot de passe incorrect"));
    }

    /**
     * Publication Kafka « best effort » : l'échec est journalisé mais n'interrompt
     * jamais l'authentification.
     */
    private Mono<Void> publishLoginEvent(User user, String ipAddress, String userAgent) {
        return Mono.fromRunnable(() -> eventPublisher.publishUserLoggedIn(
                        UserLoggedInEvent.builder()
                                .authUserId(user.getId())
                                .loginAt(LocalDateTime.now())
                                .ipAddress(ipAddress)
                                .userAgent(userAgent)
                                .build()))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.error(ServiceConstants.LOG_ERROR_EVENT_PUBLISH_FAILED, error);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> publishUserCreatedEvent(User user) {
        return Mono.fromRunnable(() -> eventPublisher.publishUserCreated(
                        UserCreatedEvent.builder()
                                .authUserId(user.getId())
                                .email(user.getEmail())
                                .phoneNumber(user.getPhoneNumber())
                                .firstName(user.getFirstName())
                                .lastName(user.getLastName())
                                .role(user.getRole())
                                .createdAt(LocalDateTime.now())
                                .build()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(unused -> log.info("Événement UserCreated publié pour: {}", user.getPhoneNumber()))
                .onErrorResume(error -> {
                    log.error("Erreur lors de la publication de l'événement UserCreated", error);
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Émet l'access token et le refresh token de la session.
     */
    private Mono<AuthResponse> generateAuthResponse(User user, String ipAddress, String userAgent) {
        return jwtService.generateToken(user)
                .flatMap(accessToken -> refreshTokenService.createRefreshToken(user, ipAddress, userAgent)
                        .map(refreshToken -> authResponseMapper.toAuthResponse(
                                accessToken, refreshToken, jwtService.getExpirationMs() / 1000, user)));
    }

    /**
     * Journalise un événement en l'enrichissant de la géolocalisation quand elle
     * est disponible.
     *
     * <p>La géolocalisation est « best effort » : échec réseau ou IP locale donnent
     * un log sans données de localisation, jamais une erreur.
     *
     * <p>On passe par {@link java.util.Optional} plutôt que par
     * {@code switchIfEmpty} : la méthode de journalisation renvoie un
     * {@code Mono<Void>}, toujours vide, ce qui déclencherait systématiquement la
     * branche de repli et produirait deux écritures.
     */
    private Mono<Void> logWithGeoLocation(User user, EventType eventType, String ipAddress, String userAgent) {
        return journaliserEtRendreLaGeo(user, eventType, ipAddress, userAgent).then();
    }

    /**
     * Journalise l'evenement et REND la geolocalisation obtenue.
     *
     * <p>Le chemin de connexion en a besoin deux fois : pour le journal, et pour
     * l'alerte de nouvelle connexion — « connexion depuis Bamako, Mali » est ce
     * qui permet a la personne de reconnaitre, ou non, sa propre action.
     *
     * <p>Rendue plutot que cherchee deux fois : un second appel au service de
     * geolocalisation doublerait la latence de la connexion, et pourrait rendre
     * une reponse differente de celle qui vient d'etre journalisee — le journal
     * et l'alerte raconteraient alors deux lieux pour un meme evenement.
     *
     * <p>Emet TOUJOURS, {@code Optional.empty()} quand la recherche a echoue :
     * un Mono vide ici ferait se terminer la connexion sans reponse.
     */
    private Mono<java.util.Optional<com.abdatytch.auth_service.dto.request.GeoLocationData>>
            journaliserEtRendreLaGeo(User user, EventType eventType,
                                     String ipAddress, String userAgent) {
        return geoLocationClient.getGeoLocation(ipAddress)
                .onErrorResume(error -> {
                    log.warn("Failed to fetch geolocation for IP {}: {}", ipAddress, error.getMessage());
                    return Mono.empty();
                })
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(geoData -> authenticationLogService.logAuthenticationEvent(
                                user.getId(), user.getPhoneNumber(), eventType, Status.SUCCESS,
                                ipAddress, userAgent, geoData.orElse(null), null)
                        .thenReturn(geoData));
    }

    // ==========================================================
    // Mot de passe oublié
    // ==========================================================

    @Override
    @Transactional
    public Mono<VerificationResponse> initiateForgotPassword(String phoneNumber) {
        log.info(ServiceConstants.LOG_INFO_FORGOT_PASSWORD, phoneNumber);

        // La reponse est IDENTIQUE, que le compte existe ou non.
        //
        // Mesure sur l'environnement de developpement, avant correction :
        //
        //   {"phoneNumber":"+22399999999"} -> 200 "Code envoye"
        //   {"phoneNumber":"+22399999998"} -> 404 "Utilisateur non trouve"
        //
        // N'importe qui pouvait donc determiner quels numeros sont inscrits, un
        // appel a la fois, sur un endpoint PUBLIC et sans rien forger.
        //
        // Le 409 de l'inscription est un compromis different et assume :
        // l'utilisateur doit savoir qu'il possede deja un compte, sinon il ne
        // peut pas se connecter. Ici, rien ne justifie de le reveler — l'appelant
        // n'a aucune decision a prendre selon que le compte existe.
        //
        // Ce que ce changement ne corrige PAS : le temps de reponse reste plus
        // long quand un code est reellement genere et envoye. Fermer ce canal
        // demanderait un delai constant, ce qui n'est pas fait ici.
        return userRepository.findByPhoneNumber(phoneNumber)
                .flatMap(user -> verificationCodeService.generateAndSendCode(
                        phoneNumber, user.getEmail(), VerificationCodeType.PASSWORD_RESET))
                // Compte inconnu : on ne genere rien, on ne journalise pas le
                // numero comme une erreur, et on rend la MEME reponse.
                //
                // `thenReturn` et NON `Mono.fromCallable` : fromCallable
                // transforme un retour null en Mono VIDE, donc en reponse 200
                // sans corps, silencieusement. thenReturn leve tout de suite si
                // le mapper rend null — un defaut bruyant valant mieux qu'une
                // reponse vide qu'aucun appelant ne saurait interpreter.
                .thenReturn(verificationResponseMapper.toSuccessResponse(
                        AppConstants.FORGOT_PASSWORD_NEUTRAL_MESSAGE,
                        AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));
    }

    @Override
    public Mono<VerificationResponse> renvoyerLeCode(
            com.abdatytch.auth_service.dto.request.ResendCodeRequest request) {
        boolean parAdresse = request.getEmail() != null && !request.getEmail().isBlank();

        // Un canal, et un seul : le DTO le garantit par @AssertTrue. Le test
        // porte donc sur « lequel » et non sur « combien ».
        if (parAdresse) {
            return initiateEmailRegister(
                    com.abdatytch.auth_service.dto.request.EmailOtpRequest.builder()
                            .email(request.getEmail())
                            .build());
        }
        return initiatePhoneRegister(PhoneAuthRequest.builder()
                .phoneNumber(request.getPhoneNumber())
                .countryCode(PhoneNumbers.prefixeIndicatif(request.getPhoneNumber()))
                .build());
    }

    @Override
    @Transactional
    public Mono<Void> changerMotDePasse(String userId,
                                        com.abdatytch.auth_service.dto.request
                                                .ChangePasswordRequest request,
                                        com.abdatytch.auth_service.dto
                                                .ContexteDeSecurite contexte) {
        // L'identifiant vient du jeton, jamais du corps : voir la javadoc du DTO.
        log.info("Changement de mot de passe demande par {}", userId);

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                // Le verrouillage s'applique : un compte verrouille ne doit pas
                // pouvoir changer son mot de passe pour echapper au verrou.
                .flatMap(this::ensureNotLocked)
                .flatMap(user -> motDePasseService
                        .correspond(request.getCurrentPassword(), user.getPasswordHash())
                        .flatMap(concorde -> {
                            if (!Boolean.TRUE.equals(concorde)) {
                                // L'echec est COMPTE, comme un echec de
                                // connexion. Sans cela, qui tient un jeton vole
                                // peut eprouver les mots de passe sans frein, et
                                // ce point d'entree devient un oracle.
                                return accountLockoutService.enregistrerEchec(user)
                                        .then(Mono.error(new AuthException(
                                                ErrorCode.INVALID_CREDENTIALS)));
                            }
                            return motDePasseService.chiffrer(request.getNewPassword());
                        })
                        .flatMap(empreinte -> {
                            user.setPasswordHash(empreinte);
                            // Le compteur d'echecs est remis a zero : la preuve
                            // de l'ancien mot de passe vient d'etre apportee.
                            user.setFailedLoginAttempts(0);
                            return userRepository.save(user);
                        }))
                // Toutes les sessions sont fermees, celle de l'appelant comprise.
                // Si le changement est motive par un soupçon, laisser vivre les
                // autres sessions annulerait l'operation.
                .flatMap(enregistre -> refreshTokenService.revokeAllUserTokens(enregistre)
                        // L'alerte part APRES l'enregistrement, et ne peut pas le
                        // faire echouer : le mot de passe a deja change. Une
                        // panne de SMTP qui remonterait en erreur ferait croire a
                        // la personne que rien n'a bouge, et elle reessaierait
                        // avec l'ancien.
                        .then(alerteDeSecuriteService.motDePasseModifie(
                                enregistre, contexte)))
                .then(Mono.fromRunnable(authMetrics::recordPasswordReset))
                .doOnSuccess(unused -> log.info(
                        "Mot de passe change et sessions fermees pour {}", userId))
                .then();
    }

    @Override
    public Mono<Void> resetPassword(ResetPasswordRequest request,
                                    com.abdatytch.auth_service.dto
                                            .ContexteDeSecurite contexte) {
        log.info(ServiceConstants.LOG_INFO_RESET_PASSWORD, request.getPhoneNumber());

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            return Mono.error(new AuthException(ErrorCode.VALIDATION_ERROR));
        }

        return verificationCodeService.validateCode(
                        request.getPhoneNumber(), request.getCode(), VerificationCodeType.PASSWORD_RESET)
                .then(userRepository.findByPhoneNumber(request.getPhoneNumber()))
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .flatMap(user -> motDePasseService.chiffrer(request.getNewPassword())
                        .flatMap(empreinte -> {
                            user.setPasswordHash(empreinte);
                            user.setFailedLoginAttempts(0);
                            user.setIsLocked(false);
                            user.setLockedUntil(null);
                            return userRepository.save(user);
                        }))
                // Un changement de mot de passe invalide toutes les sessions ouvertes
                .flatMap(enregistre -> refreshTokenService.revokeAllUserTokens(enregistre)
                        // L'alerte compte DOUBLE sur ce chemin-ci.
                        //
                        // `changerMotDePasse` exige l'ancien mot de passe ;
                        // celui-ci n'exige qu'un code reçu par SMS. C'est donc
                        // le chemin qu'emprunte une reprise de compte par
                        // interception de SMS ou echange de carte SIM — et la
                        // seule chose qui la rende visible au titulaire est
                        // cette alerte, envoyee a son adresse de courriel,
                        // qu'un detournement de numero n'atteint pas.
                        .then(alerteDeSecuriteService.motDePasseModifie(
                                enregistre, contexte)))
                .then(Mono.fromRunnable(() -> authMetrics.recordPasswordReset()))
                .doOnSuccess(unused -> log.info(ServiceConstants.LOG_INFO_RESET_PASSWORD, request.getPhoneNumber()))
                .then();
    }

    // ==========================================================
    // Rafraîchissement et déconnexion
    // ==========================================================

    @Override
    @Transactional
    public Mono<AuthResponse> refreshToken(String refreshTokenString, String ipAddress, String userAgent) {
        log.info(ServiceConstants.LOG_INFO_REFRESH_TOKEN,
                SecretMasking.masquer(refreshTokenString));

        return refreshTokenService.validateAndGetRefreshToken(refreshTokenString)
                // R2DBC : le token porte userId, plus l'entité User -> lecture explicite
                .flatMap(refreshToken -> userRepository.findById(refreshToken.getUserId())
                        .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND))))
                // Le verrouillage est vérifié ICI aussi, et son absence était un
                // contournement complet.
                //
                // `assurerNonVerrouille` était appelé par la connexion normale
                // et par la connexion administrateur — deux chemins sur trois.
                // Sur celui-ci, rien. Qui détenait déjà un refresh token
                // continuait donc de renouveler son accès INDÉFINIMENT après le
                // verrouillage de son compte : l'administrateur voyait « compte
                // verrouillé » et la personne gardait l'accès.
                //
                // La protection existait, elle n'était simplement pas atteinte
                // sur ce chemin.
                .flatMap(this::ensureNotLocked)
                .flatMap(user -> jwtService.generateToken(user)
                        .flatMap(newAccessToken -> authenticationLogService.logAuthenticationEvent(
                                        user.getId(), user.getPhoneNumber(), EventType.REFRESH_TOKEN,
                                        Status.SUCCESS, ipAddress, userAgent, null)
                                .thenReturn(authResponseMapper.toAuthResponse(
                                        newAccessToken, refreshTokenString,
                                        jwtService.getExpirationMs() / 1000, user))));
    }

    @Override
    @Transactional
    public Mono<Void> logout(String refreshTokenString, String userId, String ipAddress, String userAgent) {
        log.info(ServiceConstants.LOG_INFO_LOGOUT_SUCCESS, userId);

        Mono<Void> revoke = refreshTokenString != null
                ? refreshTokenService.revokeToken(refreshTokenString)
                : Mono.empty();

        return revoke.then(authenticationLogService.logAuthenticationEvent(
                userId, null, EventType.LOGOUT, Status.SUCCESS, ipAddress, userAgent, null));
    }

    // ==========================================================
    // Profil
    // ==========================================================

    @Override
    public Mono<AuthResponse> getUserProfile(String userId) {
        log.info("Récupération du profil utilisateur: {}", userId);

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .map(authResponseMapper::toAuthResponseWithUser);
    }

    @Override
    @Transactional
    public Mono<User> updateProfile(String userId, UpdateProfileRequest request,
                                    com.abdatytch.auth_service.dto
                                            .ContexteDeSecurite contexte) {
        log.info(ServiceConstants.LOG_INFO_UPDATE_PROFILE, userId);

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .flatMap(user -> {
                    // L'ANCIENNE adresse est lue AVANT toute mutation.
                    //
                    // `ensureEmailAvailable` ecrit la nouvelle valeur dans
                    // l'entite en memoire : la lire apres rendrait la nouvelle,
                    // et l'alerte partirait a celui qui vient de la choisir.
                    // C'est l'inversion exacte qui rend cette alerte inutile,
                    // et elle s'ecrit sans y penser.
                    String ancienneAdresse = user.getEmail();
                    boolean ancienneEtaitVerifiee =
                            Boolean.TRUE.equals(user.getIsEmailVerified());

                    return ensureEmailAvailable(user, request.getEmail())
                            .flatMap(checked -> {
                                if (request.getFirstName() != null) {
                                    checked.setFirstName(request.getFirstName());
                                }
                                if (request.getLastName() != null) {
                                    checked.setLastName(request.getLastName());
                                }
                                return userRepository.save(checked);
                            })
                            .flatMap(enregistre -> {
                                boolean adresseChangee = ancienneAdresse != null
                                        && !ancienneAdresse.equals(enregistre.getEmail());
                                if (!adresseChangee) {
                                    // Un changement de prenom n'est pas un
                                    // evenement de securite : alerter ici
                                    // noierait les alertes qui comptent.
                                    return Mono.just(enregistre);
                                }
                                return alerteDeSecuriteService.adresseModifiee(
                                                enregistre, ancienneAdresse,
                                                ancienneEtaitVerifiee, contexte)
                                        .thenReturn(enregistre);
                            });
                })
                .doOnSuccess(saved -> log.info(ServiceConstants.LOG_INFO_UPDATE_PROFILE, userId));
    }

    private Mono<User> ensureEmailAvailable(User user, String newEmail) {
        if (newEmail == null || newEmail.equals(user.getEmail())) {
            return Mono.just(user);
        }

        return userRepository.existsByEmail(newEmail)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.error(new AuthException(ErrorCode.EMAIL_ALREADY_EXISTS));
                    }
                    user.setEmail(newEmail);
                    // La nouvelle adresse n'est PAS vérifiée, et le drapeau
                    // doit le dire.
                    //
                    // Il restait à vrai : une adresse vérifiée remplacée par
                    // une autre laissait le compte affirmer que celle-ci
                    // l'était aussi. Conséquence concrète — tout ce qui se fie
                    // à cette colonne, dont une récupération de compte par
                    // courriel, aurait accepté une adresse que personne n'a
                    // jamais prouvée. Il suffisait de changer d'adresse pour
                    // hériter du drapeau.
                    //
                    // La confirmation se fait ensuite par
                    // IConfirmationDeContactService, qui n'existait pas non plus.
                    user.setIsEmailVerified(false);
                    log.info("Adresse modifiee pour {} : confirmation a refaire",
                            user.getId());
                    return Mono.just(user);
                });
    }

    // ==========================================================
    // Parcours téléphone (OTP)
    // ==========================================================

    @Override
    @Transactional
    public Mono<VerificationResponse> initiatePhoneAuth(PhoneAuthRequest request) {
        log.info(ServiceConstants.LOG_INFO_PHONE_AUTH_REQUEST, request.getPhoneNumber());

        return userRepository.findByPhoneNumber(request.getPhoneNumber())
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .flatMap(user -> verificationCodeService.generateAndSendCode(
                        request.getPhoneNumber(), user.getEmail(), VerificationCodeType.PHONE_VERIFICATION))
                // `map` et non `thenReturn` : la reponse depend desormais de ce
                // qui est REELLEMENT parti. L'amont ne peut pas etre vide — le
                // `switchIfEmpty` ci-dessus leve sur un compte inconnu — donc
                // aucune reponse ne disparait au passage.
                .map(envoi -> verificationResponseMapper.toSuccessResponse(
                        IVerificationCodeService.EnvoiDuCode.message(
                                envoi.smsAccepte(), AppConstants.OTP_ENVOYE_PAR_SMS),
                        AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));
    }

    @Override
    @Transactional
    public Mono<AuthResponse> verifyPhoneAuth(PhoneAuthVerifyRequest request, String ipAddress, String userAgent) {
        log.info(ServiceConstants.LOG_INFO_PHONE_AUTH_VERIFY, request.getPhoneNumber());

        return userRepository.findByPhoneNumber(request.getPhoneNumber())
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .flatMap(user -> {
                    if (!Boolean.TRUE.equals(user.getIsActive())) {
                        return Mono.error(new AuthException(ErrorCode.ACCOUNT_DISABLED));
                    }
                    // Le mot de passe est vérifié AVANT l'OTP : un OTP intercepté
                    // ne suffit pas à ouvrir une session.
                    return verifyPassword(user, request.getPassword(), ipAddress, userAgent);
                })
                .flatMap(user -> verificationCodeService.validateCode(
                                request.getPhoneNumber(), request.getOtp(),
                                VerificationCodeType.PHONE_VERIFICATION)
                        .thenReturn(user))
                .flatMap(user -> {
                    user.setFailedLoginAttempts(0);
                    user.setLastLogin(LocalDateTime.now());
                    return userRepository.save(user);
                })
                .flatMap(user -> authenticationLogService.logAuthenticationEvent(
                                user.getId(), user.getPhoneNumber(), EventType.LOGIN, Status.SUCCESS,
                                ipAddress, userAgent, null)
                        .then(Mono.fromRunnable(() -> authMetrics.recordLoginSuccess("phone")))
                        .then(generateAuthResponse(user, ipAddress, userAgent)));
    }

    @Override
    @Transactional
    public Mono<VerificationResponse> initiatePhoneRegister(PhoneAuthRequest request) {
        log.info(ServiceConstants.LOG_INFO_PHONE_REGISTER_REQUEST, request.getPhoneNumber());

        return userRepository.existsByPhoneNumber(request.getPhoneNumber())
                .flatMap(exists -> Boolean.TRUE.equals(exists)
                        ? Mono.error(new AuthException(ErrorCode.PHONE_ALREADY_EXISTS))
                        : verificationCodeService.generateAndSendCode(
                                request.getPhoneNumber(), null, VerificationCodeType.REGISTRATION))
                // `recordOtpSent` a ete RETIRE d'ici. VerificationCodeServiceImpl
                // le pose deja pour chaque envoi : ce chemin comptait donc
                // DOUBLE, et le panneau Grafana affichait deux fois le nombre
                // reel d'OTP d'inscription. La metrique appartient a qui envoie.
                .map(envoi -> verificationResponseMapper.toSuccessResponse(
                        IVerificationCodeService.EnvoiDuCode.message(
                                envoi.smsAccepte(), AppConstants.OTP_ENVOYE_PAR_SMS),
                        AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));
    }

    @Override
    @Transactional
    public Mono<AuthResponse> verifyPhoneRegister(PhoneRegisterVerifyRequest request,
                                                  String ipAddress, String userAgent) {
        log.info(ServiceConstants.LOG_INFO_PHONE_REGISTER_VERIFY, request.getPhoneNumber());

        return verificationCodeService.validateCode(
                        request.getPhoneNumber(), request.getOtp(), VerificationCodeType.REGISTRATION)
                .then(userRepository.existsByPhoneNumber(request.getPhoneNumber()))
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.error(new AuthException(ErrorCode.PHONE_ALREADY_EXISTS));
                    }

                    return motDePasseService.chiffrer(request.getPassword())
                            .map(empreinte -> User.builder()
                                    .phoneNumber(request.getPhoneNumber())
                                    .countryCode(PhoneNumbers.prefixeIndicatif(
                                            request.getPhoneNumber()))
                                    .passwordHash(empreinte)
                                    .role(determineUserRole(request.getDesiredRole()))
                                    .isPhoneVerified(true)
                                    .build())
                            .flatMap(userRepository::save);
                })
                .flatMap(user -> logWithGeoLocation(user, EventType.REGISTER, ipAddress, userAgent)
                        .then(Mono.fromRunnable(() -> {
                            authMetrics.recordRegistration("phone");
                            authMetrics.recordOtpVerification(true);
                        }))
                        .then(generateAuthResponse(user, ipAddress, userAgent)));
    }

    // ==========================================================
    // Parcours email (OTP)
    // ==========================================================

    @Override
    @Transactional
    public Mono<VerificationResponse> initiateEmailAuth(EmailAuthRequest request) {
        log.info("Demande d'authentification par email: {}", request.getEmail());

        return userRepository.findByEmail(request.getEmail())
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .flatMap(user -> verificationCodeService.generateAndSendCode(
                        tempPhoneFor(request.getEmail()), request.getEmail(),
                        VerificationCodeType.EMAIL_VERIFICATION))
                // Le message etait un LITTERAL annoncant l'envoi sans condition.
                .map(envoi -> verificationResponseMapper.toSuccessResponse(
                        IVerificationCodeService.EnvoiDuCode.message(
                                envoi.courrielAccepte(), AppConstants.OTP_ENVOYE_PAR_COURRIEL),
                        AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));
    }

    @Override
    @Transactional
    public Mono<AuthResponse> verifyEmailAuth(EmailAuthVerifyRequest request,
                                              String ipAddress, String userAgent) {
        log.info("Vérification OTP et authentification par email: {}", request.getEmail());

        return userRepository.findByEmail(request.getEmail())
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .flatMap(user -> {
                    if (!Boolean.TRUE.equals(user.getIsActive())) {
                        return Mono.error(new AuthException(ErrorCode.ACCOUNT_DISABLED));
                    }
                    return verifyPassword(user, request.getPassword(), ipAddress, userAgent);
                })
                .flatMap(user -> verificationCodeService.validateCodeByEmail(
                                request.getEmail(), request.getOtp(),
                                VerificationCodeType.EMAIL_VERIFICATION)
                        .thenReturn(user))
                .flatMap(user -> {
                    user.setFailedLoginAttempts(0);
                    user.setLastLogin(LocalDateTime.now());
                    user.setIsEmailVerified(true);
                    return userRepository.save(user);
                })
                .flatMap(user -> logWithGeoLocation(user, EventType.LOGIN, ipAddress, userAgent)
                        .then(Mono.fromRunnable(() -> authMetrics.recordLoginSuccess("email")))
                        .then(generateAuthResponse(user, ipAddress, userAgent)));
    }

    @Override
    @Transactional
    public Mono<VerificationResponse> initiateEmailRegister(EmailOtpRequest request) {
        log.info("Demande d'enregistrement par email: {}", request.getEmail());

        return userRepository.existsByEmail(request.getEmail())
                .flatMap(exists -> Boolean.TRUE.equals(exists)
                        ? Mono.error(new AuthException(ErrorCode.EMAIL_ALREADY_EXISTS))
                        : verificationCodeService.generateAndSendCode(
                                tempPhoneFor(request.getEmail()), request.getEmail(),
                                VerificationCodeType.REGISTRATION))
                // Comme pour l'inscription par telephone : `recordOtpSent`
                // retire, ce chemin comptait double.
                .map(envoi -> verificationResponseMapper.toSuccessResponse(
                        IVerificationCodeService.EnvoiDuCode.message(
                                envoi.courrielAccepte(), AppConstants.OTP_ENVOYE_PAR_COURRIEL),
                        AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));
    }

    @Override
    @Transactional
    public Mono<AuthResponse> verifyEmailRegister(EmailRegisterVerifyRequest request,
                                                  String ipAddress, String userAgent) {
        log.info("Vérification OTP et enregistrement par email: {}", request.getEmail());

        return userRepository.existsByEmail(request.getEmail())
                .flatMap(emailExists -> Boolean.TRUE.equals(emailExists)
                        ? Mono.<Boolean>error(new AuthException(ErrorCode.EMAIL_ALREADY_EXISTS))
                        : userRepository.existsByPhoneNumber(request.getPhoneNumber()))
                .flatMap(phoneExists -> Boolean.TRUE.equals(phoneExists)
                        ? Mono.<Void>error(new AuthException(ErrorCode.PHONE_ALREADY_EXISTS))
                        : verificationCodeService.validateCodeByEmail(
                                request.getEmail(), request.getOtp(), VerificationCodeType.REGISTRATION))
                .then(Mono.defer(() -> {
                    User user = User.builder()
                            .email(request.getEmail())
                            .firstName(request.getFirstName())
                            .lastName(request.getLastName())
                            .phoneNumber(request.getPhoneNumber())
                            .countryCode(request.getCountryCode())
                            .passwordHash(null)
                            .role(determineUserRole(request.getDesiredRole()))
                            .isEmailVerified(true)
                            .isPhoneVerified(false)
                            .build();

                    return motDePasseService.chiffrer(request.getPassword())
                            .map(empreinte -> {
                                user.setPasswordHash(empreinte);
                                return user;
                            })
                            .flatMap(userRepository::save);
                }))
                .flatMap(user -> publishUserCreatedEvent(user)
                        .then(authenticationLogService.logAuthenticationEvent(
                                user.getId(), user.getPhoneNumber(), EventType.REGISTER, Status.SUCCESS,
                                ipAddress, userAgent, null))
                        .doOnSuccess(unused -> log.info(
                                "Utilisateur enregistré avec succès: {}", user.getPhoneNumber()))
                        .then(generateAuthResponse(user, ipAddress, userAgent)));
    }

    /**
     * Numéro factice court (< 20 caractères) forçant l'envoi du code par email seul.
     */
    private String tempPhoneFor(String email) {
        return TEMP_PHONE_PREFIX + Math.abs(email.hashCode());
    }

    /**
     * Rôle attribué à l'inscription.
     *
     * <p><b>Aucun rôle d'administration ne peut être auto-attribué</b> : il est
     * rétrogradé en {@code USER}. Seul un administrateur existant peut conférer
     * {@code ADMIN}, et {@code SUPER_ADMIN} n'est accordé que par le seeder de
     * déploiement.
     *
     * <p>Le contrôle portait auparavant sur le seul {@code ADMIN}, nommé
     * explicitement. {@code SUPER_ADMIN} n'existait pas encore ; dès son ajout,
     * une inscription portant {@code role=SUPER_ADMIN} l'aurait obtenu. C'est le
     * défaut classique d'une liste tenue à la main : elle est exacte le jour où
     * on l'écrit.
     *
     * <p>La question posée est désormais « ce rôle est-il un rôle
     * d'administration ? », à laquelle {@link UserRole} répond — un nouveau rôle
     * d'administration sera donc couvert sans que ce code change.
     */
    private UserRole determineUserRole(UserRole desiredRole) {
        if (desiredRole == null) {
            log.info("Aucun rôle spécifié, attribution du rôle USER par défaut");
            return UserRole.USER;
        }
        if (desiredRole.estRoleDAdministration()) {
            log.warn("Tentative d'auto-attribution du rôle {} refusée, attribution du rôle USER",
                    desiredRole);
            return UserRole.USER;
        }
        log.info("Attribution du rôle demandé: {}", desiredRole);
        return desiredRole;
    }

}
