package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.service.IOAuth2Service;
import com.abdatytch.auth_service.service.IJwtService;
import com.abdatytch.auth_service.service.IRefreshTokenService;
import com.abdatytch.auth_service.service.IEventPublisher;
import com.abdatytch.auth_service.service.IAuthenticationLogService;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.dto.request.OAuth2CallbackRequest;
import com.abdatytch.auth_service.dto.request.ProfileCompletionRequest;
import com.abdatytch.auth_service.dto.response.OAuth2AuthResponse;
import com.abdatytch.auth_service.dto.response.OAuth2UserInfo;
import com.abdatytch.auth_service.entities.OAuthAccount;
import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.OAuthProvider;
import com.abdatytch.auth_service.enums.Status;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.event.OAuth2ProfileCompletedEvent;
import com.abdatytch.auth_service.event.UserCreatedEvent;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.mappers.OAuth2ResponseMapper;
import com.abdatytch.auth_service.mappers.UserMapper;
import com.abdatytch.auth_service.repository.OAuthAccountRepository;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.oauth2.CodeDeRemiseOAuth2Redis;
import com.abdatytch.auth_service.service.oauth2.EtatOAuth2Redis;
import com.abdatytch.auth_service.service.oauth2.IFournisseurOAuth2;
import com.abdatytch.auth_service.service.oauth2.RegistreDesFournisseursOAuth2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service pour gérer l'authentification OAuth2 avec Google et Facebook
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuth2ServiceImpl implements IOAuth2Service {
    
    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final IJwtService jwtService;
    private final IRefreshTokenService refreshTokenService;
    private final UserMapper userMapper;
    private final OAuth2ResponseMapper oAuth2ResponseMapper;
    private final IEventPublisher eventPublisher;
    private final IAuthenticationLogService authenticationLogService;
    private final com.abdatytch.auth_service.metrics.AuthMetrics authMetrics;

    /**
     * Les fournisseurs, et le seul endroit qui en choisisse un.
     *
     * <p>Remplace le {@code if/else} de l'URL d'autorisation, le {@code switch}
     * de l'echange de code et les six champs {@code @Value} qui portaient les
     * identifiants de Google et de Facebook. Ces identifiants vivent desormais
     * dans les fournisseurs eux-memes, ce qui retire d'ici toute connaissance
     * d'un fournisseur particulier.
     */
    private final RegistreDesFournisseursOAuth2 registreDesFournisseurs;

    /** Valeur anti-falsification du parcours : voir {@link EtatOAuth2Redis}. */
    private final EtatOAuth2Redis etats;

    /**
     * Remise des jetons hors de l'URL : voir {@link CodeDeRemiseOAuth2Redis}.
     *
     * <p>Distinct de {@link #etats} et pour une raison distincte : le state
     * protege l'<b>aller</b> du parcours contre une falsification, le code de
     * remise protege le <b>retour</b> contre la fuite des jetons par l'URL. Les
     * confondre donnerait une valeur qui sert deux fins et qu'on n'oserait plus
     * faire expirer vite.
     */
    private final CodeDeRemiseOAuth2Redis codeDeRemise;

    /**
     * Base de l'application frontale, vers laquelle le rappel OAuth2 redirige.
     *
     * <p>Sans valeur par defaut : elle etait ecrite en dur dans le controleur
     * ({@code "http://localhost:3000"}), et la connexion OAuth2 redirigeait donc
     * vers localhost en production. Un repli silencieux est exactement ce qui a
     * ete retire — le service refuse de demarrer si la propriete manque, ce qui
     * rend l'oubli visible au deploiement et non a la premiere connexion d'un
     * utilisateur.
     */
    @Value("${app.frontend-url}")
    private String frontendUrl;
    
    /**
     * URL d'autorisation vers laquelle rediriger la personne.
     *
     * <h2>Pourquoi cette methode rend maintenant un Mono</h2>
     *
     * Elle doit retenir la valeur anti-falsification avant de rendre l'URL, et
     * cette ecriture va dans Redis. La rendre synchrone imposerait d'attendre
     * Redis sur le fil appelant — une EventLoop — ce que la politique de ce
     * depot interdit et que BlockHound detecte.
     *
     * <h2>Le redirect_uri de l'appelant reste ignore</h2>
     *
     * C'etait deja le cas et ce n'est pas un oubli : seul le {@code redirect_uri}
     * de la configuration est utilise. Accepter celui de l'appelant permettrait
     * de faire livrer le code d'autorisation sur un domaine qu'il choisit.
     */
    @Override
    public Mono<String> getAuthorizationUrl(String provider, String redirectUri) {
        log.info("Génération de l'URL d'autorisation OAuth2 pour le provider: {}", provider);

        OAuthProvider oauthProvider = validateProvider(provider);
        IFournisseurOAuth2 fournisseur = registreDesFournisseurs.pour(oauthProvider);

        return etats.creer(oauthProvider)
                .map(fournisseur::urlDAutorisation);
    }

    /**
     * Gère le callback OAuth2 après authentification avec le provider
     */
    @Transactional
    public Mono<OAuth2AuthResponse> handleOAuth2Callback(OAuth2CallbackRequest request,
                                                         String ipAddress, String userAgent) {
        log.info(ServiceConstants.LOG_INFO_OAUTH2_CALLBACK, request.getProvider(), request.getCode() != null);

        OAuthProvider provider = validateProvider(request.getProvider());

        // 1. Vérifier d'abord le state, AVANT l'échange du code.
        //    L'ordre compte : échanger puis vérifier ferait consommer le code
        //    d'autorisation d'un parcours que nous n'avons pas initié, et un
        //    code ne sert qu'une fois. L'attaquant obtiendrait l'effet de bord
        //    même si la requête est ensuite refusée.
        return etats.consommer(request.getState(), provider)
                // 2. Échange du code contre l'identité, chez le fournisseur.
                //    Google et Facebook restent bloquants et restent isolés sur
                //    le pool d'I/O bloquante ; Apple est non bloquant. Le
                //    service ne sait plus lequel est lequel.
                .then(Mono.defer(() -> registreDesFournisseurs.pour(provider)
                        .echangerLeCode(decoderLeCode(request.getCode()))))
                // 3. Nom transmis par le rappel, le cas echeant.
                .map(userInfo -> completerAvecLeRappel(userInfo, request))
                // Toute erreur non nommée devient EXTERNAL_SERVICE_ERROR, comme
                // le faisait le try/catch de l'ancienne méthode. La différence :
                // une AuthException passe désormais telle quelle. L'ancien
                // catch(Exception) les enveloppait aussi, et transformait donc
                // un « fournisseur non configuré » ou un « jeton d'identité
                // invalide » en « erreur du service externe » — un diagnostic
                // qui accusait Google ou Apple de notre propre cause.
                .onErrorMap(erreur -> !(erreur instanceof AuthException), erreur -> {
                    log.error(ServiceConstants.LOG_ERROR_OAUTH2_TOKEN_EXCHANGE,
                            provider.getProviderName(), erreur.getMessage(), erreur);
                    return new AuthException(ErrorCode.EXTERNAL_SERVICE_ERROR);
                })
                .flatMap(userInfo -> oauthAccountRepository
                        .findByProviderAndProviderUserId(provider, userInfo.getProviderId())
                        // 2. Compte OAuth déjà lié -> connexion directe
                        .flatMap(existing -> handleExistingOAuthUser(existing, userInfo, ipAddress, userAgent))
                        // 3. Sinon -> création (profil complet ou non)
                        .switchIfEmpty(Mono.defer(() ->
                                handleNewOAuthUser(provider, userInfo, ipAddress, userAgent))));
    }
    
    /**
     * Complète le profil utilisateur après authentification OAuth2
     */
    @Transactional
    public Mono<OAuth2AuthResponse> completeProfile(ProfileCompletionRequest request,
                                                    String ipAddress, String userAgent) {
        log.info(ServiceConstants.LOG_INFO_OAUTH2_PROFILE_COMPLETION, request.getTempToken());
        
        // 1. Valider et décoder le token temporaire
        Map<String, Object> tempTokenData = validateTempToken(request.getTempToken());
        String providerUserId = (String) tempTokenData.get("providerUserId");
        String providerName = (String) tempTokenData.get("provider");
        OAuthProvider provider = OAuthProvider.fromString(providerName);
        
        // 2. Récupérer le compte OAuth temporaire, puis l'utilisateur lié
        //    (R2DBC : plus de relation, on suit la clé étrangère explicitement)
        return oauthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.INVALID_CREDENTIALS)))
                .flatMap(oauthAccount -> userRepository.findById(oauthAccount.getUserId())
                        .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND))))
                .flatMap(user -> {
                    // 3. Compléter les informations manquantes
                    user.setPhoneNumber(request.getPhoneNumber());
                    user.setCountryCode(request.getCountryCode());
                    user.setIsPhoneVerified(false);

                    // 3.1. Email seulement s'il en manque un : ne jamais écraser
                    //      un email déjà vérifié par le fournisseur.
                    if (request.getEmail() != null && !request.getEmail().isEmpty()
                            && (user.getEmail() == null || user.getEmail().isEmpty())) {
                        user.setEmail(request.getEmail());
                        user.setIsEmailVerified(false);
                    }

                    return userRepository.save(user);
                })
                .flatMap(user -> {
                    EventType eventType = provider.getEvenementDeConnexion();

                    return authenticationLogService.logAuthenticationEvent(
                                    user.getId(), user.getPhoneNumber(), eventType, Status.SUCCESS,
                                    ipAddress, userAgent,
                                    ServiceConstants.LOG_INFO_OAUTH2_PROFILE_COMPLETED)
                            .then(Mono.fromRunnable(() -> {
                                authMetrics.recordOAuth2Login(provider.name().toLowerCase());
                                authMetrics.recordLoginSuccess("oauth2");

                                eventPublisher.publishOAuth2ProfileCompleted(
                                        OAuth2ProfileCompletedEvent.builder()
                                                .authUserId(user.getId())
                                                .phoneNumber(request.getPhoneNumber())
                                                .countryCode(request.getCountryCode())
                                                .completedAt(LocalDateTime.now())
                                                .build());
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .then(issueTokens(user, "OAuth2 Profile Completion",
                                    AppConstants.OAUTH2_PROFILE_COMPLETED));
                });
    }

    /**
     * Émet l'access token et le refresh token à l'issue d'un parcours OAuth2.
     */
    private Mono<OAuth2AuthResponse> issueTokens(User user, String userAgentLabel, String message) {
        return jwtService.generateToken(user)
                .flatMap(accessToken -> refreshTokenService
                        .createRefreshToken(user, "oauth2", userAgentLabel)
                        .map(refreshToken -> oAuth2ResponseMapper.toCompleteProfileResponse(
                                accessToken, refreshToken, user, message)));
    }
    
    /**
     * Reprend le nom transmis par le rappel quand le fournisseur n'en donne pas.
     *
     * <h2>Pourquoi ce complément existe</h2>
     *
     * Apple n'a aucun point de profil et ne transmet le nom qu'une fois, dans le
     * champ {@code user} du formulaire de rappel, à la <b>première</b>
     * autorisation. Sans cette reprise, tous les comptes créés par Apple
     * seraient anonymes — et le nom serait perdu définitivement, puisque les
     * connexions suivantes ne le renvoient plus.
     *
     * <h2>Le fournisseur a toujours la priorité</h2>
     *
     * Le nom du rappel ne sert que si le fournisseur n'a rien donné. Il vient
     * d'un formulaire posté par un tiers : le laisser écraser un nom obtenu de
     * Google ou de Facebook permettrait de modifier l'état civil d'un compte
     * en forgeant un rappel.
     *
     * <p>Visibilité de paquet et non privée : c'est une fonction pure, et la
     * règle de priorité qu'elle porte est précisément celle qu'un test doit
     * pouvoir exercer. Laissée privée, elle n'était atteignable qu'en montant
     * les onze dépendances du service — et un mutant qui inversait la priorité
     * a effectivement survécu faute de ce test.
     */
    static OAuth2UserInfo completerAvecLeRappel(OAuth2UserInfo userInfo,
                                                OAuth2CallbackRequest request) {
        boolean rappelPorteUnNom = estRenseigne(request.getPrenomDuRappel())
                || estRenseigne(request.getNomDuRappel());
        if (!rappelPorteUnNom) {
            return userInfo;
        }

        if (!estRenseigne(userInfo.getFirstName()) && estRenseigne(request.getPrenomDuRappel())) {
            userInfo.setFirstName(request.getPrenomDuRappel().trim());
        }
        if (!estRenseigne(userInfo.getLastName()) && estRenseigne(request.getNomDuRappel())) {
            userInfo.setLastName(request.getNomDuRappel().trim());
        }
        if (!estRenseigne(userInfo.getName())) {
            String compose = ((estRenseigne(userInfo.getFirstName())
                    ? userInfo.getFirstName() : "") + " "
                    + (estRenseigne(userInfo.getLastName())
                    ? userInfo.getLastName() : "")).trim();
            if (!compose.isEmpty()) {
                userInfo.setName(compose);
            }
        }
        // Le nom n'est PAS journalise : c'est une donnee personnelle, et son
        // passage se constate au compte cree.
        log.info("Nom transmis par le rappel {} repris pour la creation du compte",
                request.getProvider());
        return userInfo;
    }

    private static boolean estRenseigne(String valeur) {
        return valeur != null && !valeur.isBlank();
    }

    /**
     * Décode le code d'autorisation s'il arrive encodé.
     *
     * <p>Comportement conservé de la méthode supprimée : selon le client qui
     * transmet le rappel, le code peut arriver encodé pour URL. L'envoyer tel
     * quel au fournisseur produit alors un {@code invalid_grant}, c'est-à-dire
     * un refus qui désigne le code et non l'encodage.
     */
    private static String decoderLeCode(String code) {
        return java.net.URLDecoder.decode(code, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Connexion d'un utilisateur dont le compte OAuth est déjà lié.
     *
     * <p>L'ancien {@code @Cacheable} sur cette méthode <b>privée</b> n'a jamais eu
     * d'effet : Spring AOP ne peut pas proxifier une méthode privée. Il est retiré.
     */
    private Mono<OAuth2AuthResponse> handleExistingOAuthUser(OAuthAccount oauthAccount,
                                                             OAuth2UserInfo userInfo,
                                                             String ipAddress, String userAgent) {
        log.info(ServiceConstants.LOG_INFO_OAUTH2_USER_EXISTS,
                oauthAccount.getProvider(), userInfo.getProviderId());

        // Rafraîchir les jetons du fournisseur
        oauthAccount.setAccessToken(userInfo.getAccessToken());
        oauthAccount.setRefreshToken(userInfo.getRefreshToken());
        oauthAccount.setTokenExpiresAt(userInfo.getExpiresIn() != null
                ? System.currentTimeMillis() + (userInfo.getExpiresIn() * 1000) : null);

        return oauthAccountRepository.save(oauthAccount)
                .then(userRepository.findById(oauthAccount.getUserId()))
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .flatMap(user -> {
                    user.setLastLogin(LocalDateTime.now());
                    return userRepository.save(user);
                })
                .flatMap(user -> {
                    EventType eventType = oauthAccount.getProvider().getEvenementDeConnexion();

                    return authenticationLogService.logAuthenticationEvent(
                                    user.getId(), user.getPhoneNumber(), eventType, Status.SUCCESS,
                                    ipAddress, userAgent,
                                    "Connexion OAuth2 réussie via "
                                            + oauthAccount.getProvider().getProviderName())
                            .then(Mono.fromRunnable(() -> {
                                authMetrics.recordOAuth2Login(
                                        oauthAccount.getProvider().name().toLowerCase());
                                authMetrics.recordLoginSuccess("oauth2");
                            }))
                            // La derniere connexion n'est plus propagee a
                            // user-service : elle appartient a CE service, qui
                            // la detient et la rend dans `lastLogin`. L'appel
                            // visait POST /internal/users/auth/{id}/login, dont
                            // l'implementation rendait Mono.empty() — trois
                            // niveaux d'indirection pour ne rien faire.
                            .then(issueTokens(user, "OAuth2 Login", AppConstants.OAUTH2_LOGIN_SUCCESS));
                });
    }

    /**
     * Gère la création d'un nouvel utilisateur OAuth
     */
    private Mono<OAuth2AuthResponse> handleNewOAuthUser(OAuthProvider provider, OAuth2UserInfo userInfo,
                                                        String ipAddress, String userAgent) {
        log.info(ServiceConstants.LOG_INFO_OAUTH2_USER_CREATE,
                provider.getProviderName(), userInfo.getEmail());

        // Google et Facebook ne fournissent jamais le numéro de téléphone :
        // le profil est toujours considéré incomplet et passe par la complétion.
        return isProfileComplete(userInfo)
                ? createCompleteOAuthUser(provider, userInfo, ipAddress, userAgent)
                : createTemporaryOAuthUser(provider, userInfo, ipAddress, userAgent);
    }
    
    /**
     * Vérifie si le profil OAuth est complet
     */
    private boolean isProfileComplete(OAuth2UserInfo userInfo) {
        // Google et Facebook ne fournissent pas le numéro de téléphone
        // On considère donc le profil comme incomplet
        return false;
    }
    
    /**
     * Crée un utilisateur temporaire et génère un token temporaire pour la complétion du profil
     */
    private Mono<OAuth2AuthResponse> createTemporaryOAuthUser(OAuthProvider provider, OAuth2UserInfo userInfo,
                                                              String ipAddress, String userAgent) {
        User user = User.builder()
                .email(userInfo.getEmail())
                .firstName(userInfo.getFirstName())
                .lastName(userInfo.getLastName())
                .phoneNumber(null) // complété lors de la seconde étape
                .countryCode(null)
                // Mot de passe aléatoire : le compte n'est accessible que par OAuth2
                                // AUCUN hachage ici, et c'est deliberе.
                                //
                                // La version precedente chiffrait un UUID
                                // aleatoire « pour remplir la colonne » : 241 ms
                                // de BCrypt sur l'EventLoop, pour une empreinte
                                // qu'aucun parcours ne verifie jamais — un
                                // compte OAuth2 n'a pas de mot de passe.
                                //
                                // `null` est en outre plus juste : il dit que
                                // ce compte ne se connecte pas par mot de passe,
                                // la ou une empreinte aleatoire laissait croire
                                // le contraire. IMotDePasseService.correspond
                                // rend false sur une empreinte nulle, donc la
                                // connexion par mot de passe echoue comme un
                                // mot de passe faux, sans designer le compte.
                                .passwordHash(null)
                .isEmailVerified(userInfo.getEmailVerified() != null ? userInfo.getEmailVerified() : false)
                .isPhoneVerified(false)
                .isActive(true)
                .isLocked(false)
                .role(UserRole.USER)
                .failedLoginAttempts(0)
                .build();

        return userRepository.save(user)
                .flatMap(savedUser -> oauthAccountRepository.save(OAuthAccount.builder()
                                .userId(savedUser.getId())
                                .provider(provider)
                                .providerUserId(userInfo.getProviderId())
                                .email(userInfo.getEmail())
                                .name(userInfo.getName())
                                .pictureUrl(userInfo.getPictureUrl())
                                .accessToken(userInfo.getAccessToken())
                                .refreshToken(userInfo.getRefreshToken())
                                .tokenExpiresAt(userInfo.getExpiresIn() != null
                                        ? System.currentTimeMillis() + (userInfo.getExpiresIn() * 1000)
                                        : null)
                                .build())
                        .doOnSuccess(account -> log.info(ServiceConstants.LOG_INFO_OAUTH2_ACCOUNT_LINK,
                                savedUser.getId(), provider.getProviderName()))
                        .thenReturn(savedUser))
                .flatMap(savedUser -> {
                    EventType eventType = provider.getEvenementDeConnexion();

                    return authenticationLogService.logAuthenticationEvent(
                                    savedUser.getId(), savedUser.getPhoneNumber(), eventType,
                                    Status.SUCCESS, ipAddress, userAgent,
                                    "Authentification OAuth2 initiale via "
                                            + provider.getProviderName() + " - Profil incomplet")
                            .then(Mono.fromRunnable(() -> {
                                authMetrics.recordOAuth2Login(provider.name().toLowerCase());
                                authMetrics.recordRegistration("oauth2");

                                eventPublisher.publishUserCreated(UserCreatedEvent.builder()
                                        .authUserId(savedUser.getId())
                                        .email(savedUser.getEmail())
                                        .phoneNumber(savedUser.getPhoneNumber())
                                        .firstName(savedUser.getFirstName())
                                        .lastName(savedUser.getLastName())
                                        .role(savedUser.getRole())
                                        .createdAt(savedUser.getCreatedAt())
                                        .build());
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .then(generateTempToken(savedUser.getId(), provider, userInfo.getProviderId()))
                            .map(tempToken -> oAuth2ResponseMapper.toIncompleteProfileResponse(
                                    tempToken,
                                    new String[]{
                                            AppConstants.OAUTH2_MISSING_PHONE,
                                            AppConstants.OAUTH2_MISSING_COUNTRY_CODE
                                    },
                                    savedUser,
                                    AppConstants.OAUTH2_PROFILE_INCOMPLETE));
                });
    }

    /**
     * Crée un compte OAuth2 dont le profil est déjà complet.
     *
     * <p>Aucun fournisseur ne remplit cette condition aujourd'hui : ni Google ni
     * Facebook ne communiquent le numéro de téléphone, et
     * {@code isProfileComplete} l'exige. Cette branche délègue donc à la
     * complétion, et c'est son état d'origine — la redirection était déjà là,
     * commentée comme telle.
     *
     * <p><b>Méthode réinsérée après une suppression accidentelle de ma part.</b>
     * Son corps vient du commit HEAD ; seul le type de retour a changé, pour
     * suivre la branche voisine du ternaire ({@code createTemporaryOAuthUser},
     * qui rend un {@code Mono<OAuth2AuthResponse>}). Rien n'a été inventé.
     */
    private Mono<OAuth2AuthResponse> createCompleteOAuthUser(OAuthProvider provider,
                                                             OAuth2UserInfo userInfo,
                                                             String ipAddress, String userAgent) {
        return createTemporaryOAuthUser(provider, userInfo, ipAddress, userAgent);
    }

    /**
     * Jeton temporaire portant la complétion de profil.
     *
     * <p><b>Méthode réinsérée après une suppression accidentelle de ma part.</b>
     * Le corps est celui de HEAD. Le type de retour passe de {@code String} à
     * {@code Mono<String>} parce que {@code IJwtService.generateTokenWithClaims}
     * rend désormais un {@code Mono}, et parce que le site d'appel l'enchaîne
     * par {@code .then(...)}, qui exige un éditeur.
     *
     * <p>L'expiration est posée en <b>millisecondes</b>, et c'est voulu : la
     * bibliothèque JWT construit un {@code Date} à partir de cette valeur.
     * {@link #validateTempToken(String)} la relit en <b>secondes</b>, parce que
     * la norme JWT stocke {@code exp} en secondes et que la sérialisation
     * convertit. Les deux unités sont justes de leur côté ; c'est une asymétrie
     * à lire deux fois, pas une erreur.
     */
    private Mono<String> generateTempToken(String userId, OAuthProvider provider,
                                           String providerUserId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("provider", provider.getProviderName());
        claims.put("providerUserId", providerUserId);
        claims.put("type", "oauth2_temp");
        claims.put("exp", System.currentTimeMillis()
                + (AppConstants.OAUTH2_TEMP_TOKEN_EXPIRATION_MINUTES * 60 * 1000));

        log.info(ServiceConstants.LOG_INFO_OAUTH2_TEMP_TOKEN_GENERATED,
                userId, AppConstants.OAUTH2_TEMP_TOKEN_EXPIRATION_MINUTES);

        return jwtService.generateTokenWithClaims(claims);
    }

    /**
     * Valide et décode le jeton temporaire de complétion.
     *
     * <p><b>Méthode réinsérée après une suppression accidentelle de ma part.</b>
     * Corps et signature sont ceux de HEAD, à l'identique : elle reste synchrone
     * parce que {@code IJwtService.extractAllClaims} l'est, et parce que le site
     * d'appel affecte directement son résultat à une {@code Map}.
     *
     * <p>Deux vérifications, et la seconde compte autant que la première : le
     * champ {@code type} doit valoir {@code oauth2_temp}. Sans elle, un jeton
     * d'accès ordinaire — correctement signé, non expiré — serait accepté ici et
     * permettrait de compléter le profil d'un compte arbitraire.
     */
    private Map<String, Object> validateTempToken(String tempToken) {
        try {
            Map<String, Object> claims = jwtService.extractAllClaims(tempToken);

            // JWT stocke `exp` en SECONDES, d'ou la division.
            Long exp = ((Number) claims.get("exp")).longValue();
            if (System.currentTimeMillis() / 1000 > exp) {
                log.error(ServiceConstants.LOG_ERROR_OAUTH2_TEMP_TOKEN_INVALID);
                throw new AuthException(ErrorCode.INVALID_TOKEN);
            }

            String type = (String) claims.get("type");
            if (!"oauth2_temp".equals(type)) {
                throw new AuthException(ErrorCode.INVALID_TOKEN);
            }

            return claims;
        } catch (Exception e) {
            // Toute cause — signature invalide, jeton malforme, claim absent —
            // rend la meme erreur : distinguer renseignerait un attaquant sur
            // la raison du refus.
            log.error(ServiceConstants.LOG_ERROR_OAUTH2_TEMP_TOKEN_INVALID);
            throw new AuthException(ErrorCode.INVALID_TOKEN);
        }
    }

    // `syncUserWithUserService` a ete supprimee : elle creait le compte dans
    // user-service par un appel Feign, en DOUBLE de l'evenement Kafka
    // `user.created` publie quelques lignes plus haut par
    // `eventPublisher.publishUserCreated(...)`.
    //
    // Deux mecanismes pour un meme enregistrement, et le second etait
    // enveloppe dans un `catch (Exception)` qui se contentait de journaliser :
    // il echouait depuis toujours — son chemin portait le prefixe public
    // /api/v1, que user-service ne sert pas — sans que personne le remarque,
    // puisque la voie Kafka faisait le travail.
    //
    // Elle n'etait d'ailleurs plus appelee par personne.
    
    /**
     * Valide le provider OAuth2
     */
    private OAuthProvider validateProvider(String providerName) {
        try {
            return OAuthProvider.fromString(providerName);
        } catch (IllegalArgumentException e) {
            log.error(ServiceConstants.LOG_ERROR_OAUTH2_INVALID_PROVIDER, providerName);
            throw new AuthException(ErrorCode.INVALID_REQUEST);
        }
    }
    
    // Fallback methods for Resilience4j
    
    

    // ==================================================================
    // Construction des redirections — deplacee depuis AuthController
    // ==================================================================

    @Override
    public String redirectUriDuFournisseur(String provider) {
        // Le fournisseur detient son URI de rappel, et c'est le meme objet qui
        // l'emploie pour construire l'URL d'autorisation. Les deux ne peuvent
        // donc plus differer — l'ecart produisait un redirect_uri_mismatch chez
        // le fournisseur, loin de la cause.
        return registreDesFournisseurs.pour(validateProvider(provider)).uriDeRappel();
    }

    @Override
    public Mono<String> urlDeRedirectionApresRappel(OAuth2AuthResponse reponse) {
        // La redirection ne porte plus QUE le code. Ni jeton d'acces, ni jeton
        // de rafraichissement, ni tempToken, ni meme missingFields — cette
        // derniere liste n'etait pas secrete, mais elle figure dans la reponse
        // de l'echange, et l'y laisser evite deux sources pour une meme
        // information.
        //
        // Les deux branches sont desormais identiques : le frontal lit
        // profileComplete dans la reponse de l'echange pour savoir ou aller.
        // C'est ce qui evite d'avoir a se souvenir laquelle des deux URL portait
        // un secret.
        return codeDeRemise.creer(reponse)
                .map(code -> String.format("%s/auth/callback?code=%s",
                        frontendUrl, encoder(code)));
    }

    @Override
    public Mono<OAuth2AuthResponse> echanger(String code) {
        return codeDeRemise.consommer(code);
    }

    @Override
    public String urlDErreur(String erreur, String description) {
        return String.format("%s/auth/callback?error=%s&error_description=%s",
                frontendUrl, encoder(erreur), encoder(description));
    }

    /**
     * Encodage d'une valeur de parametre de requete.
     *
     * <p>{@code error} et {@code error_description} viennent de la chaine de
     * requete, donc de l'appelant. Ils etaient interpoles <b>sans encodage</b>
     * dans l'URL passee a {@code URI.create}, avec deux consequences : un
     * caractere illegal — une espace, un diese — faisait lever
     * {@code IllegalArgumentException}, donc une erreur 500 sur une entree
     * entierement controlee par l'appelant ; et un {@code &} permettait
     * d'injecter des parametres supplementaires dans la redirection.
     */
    private static String encoder(String valeur) {
        return valeur == null
                ? ""
                : java.net.URLEncoder.encode(valeur, java.nio.charset.StandardCharsets.UTF_8);
    }
}
