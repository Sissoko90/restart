package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.WebConstants;
import com.abdatytch.auth_service.dto.ContexteDeSecurite;
import com.abdatytch.commons.security.SecretMasking;
import com.abdatytch.auth_service.dto.request.*;
import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.dto.response.OAuth2AuthResponse;
import com.abdatytch.auth_service.dto.response.VerificationResponse;
import com.abdatytch.auth_service.service.IAuthService;
import com.abdatytch.auth_service.dto.response.MessageResponse;
import com.abdatytch.auth_service.service.IOAuth2Service;
import com.abdatytch.auth_service.util.AppelantAuthentifie;
import com.abdatytch.auth_service.util.PhoneNumbers;
import com.abdatytch.auth_service.util.ReactiveRequestUtil;
import jakarta.validation.Valid;
import com.abdatytch.auth_service.service.oauth2.ProfilInitialApple;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping(AppConstants.API_BASE_PATH)
@RequiredArgsConstructor
@Slf4j
public class AuthController {
    
    private final IAuthService authService;
    private final IOAuth2Service oauth2Service;

    /** Lecture du champ {@code user} du rappel Apple. Voir {@link ProfilInitialApple}. */
    private final ObjectMapper objectMapper;
    
    @PostMapping(AppConstants.LOGIN_ENDPOINT)
    public Mono<ResponseEntity<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_LOGIN_REQUEST);
        
        return Mono.defer(() -> {
            String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
            String userAgent = ReactiveRequestUtil.getUserAgent(exchange);
            return authService.login(request, ipAddress, userAgent);
        })
        .map(ResponseEntity::ok);
    }
    
    @PostMapping(AppConstants.LOGOUT_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> logout(
            @RequestBody(required = false) RefreshTokenRequest refreshTokenRequest,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_LOGOUT_REQUEST);

        String refreshToken = refreshTokenRequest != null ? refreshTokenRequest.getRefreshToken() : null;
        String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
        String userAgent = ReactiveRequestUtil.getUserAgent(exchange);

        // La tolerance « deconnexion sans jeton valide » a ete RETIREE, parce
        // qu'elle etait inatteignable.
        //
        // Ce chemin releve de `.pathMatchers("/auth/**").authenticated()` : un
        // appel sans jeton valide est refuse en 401 par la chaine de securite et
        // n'arrive jamais ici. Le `defaultIfEmpty("")` et le `userId.isEmpty()
        // ? null : userId` qui l'accompagnaient n'ont donc jamais pu servir —
        // ils donnaient seulement l'impression d'un cas traite.
        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> authService.logout(refreshToken, userId, ipAddress, userAgent))
                .thenReturn(ResponseEntity.ok(
                        MessageResponse.de(AppConstants.LOGOUT_SUCCESS_MESSAGE)));
    }
    
    @PostMapping(AppConstants.REFRESH_TOKEN_ENDPOINT)
    public Mono<ResponseEntity<AuthResponse>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_REFRESH_TOKEN_REQUEST);
        
        return Mono.defer(() -> {
            String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
            String userAgent = ReactiveRequestUtil.getUserAgent(exchange);
            return authService.refreshToken(request.getRefreshToken(), ipAddress, userAgent);
        })
        .map(ResponseEntity::ok);
    }
    
    @PostMapping(AppConstants.FORGOT_PASSWORD_ENDPOINT)
    public Mono<ResponseEntity<VerificationResponse>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        log.info(WebConstants.LOG_INFO_FORGOT_PASSWORD_REQUEST);
        
        return Mono.defer(() -> authService.initiateForgotPassword(request.getPhoneNumber()))
            .map(ResponseEntity::ok);
    }
    
    @PostMapping(AppConstants.RESET_PASSWORD_ENDPOINT)
    public Mono<ResponseEntity<Map<String, String>>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_RESET_PASSWORD_REQUEST);

        return authService.resetPassword(request, contexteDe(exchange))
                .thenReturn(Map.of("message", AppConstants.RESET_PASSWORD_SUCCESS_MESSAGE))
                .map(ResponseEntity::ok);
    }
    
    /**
     * Renvoi du code d'inscription, par le canal de l'inscription.
     *
     * <p>Prenait un {@code ForgotPasswordRequest} — qui ne porte qu'un numero et
     * l'exige — et appelait {@code initiatePhoneRegister} quelle que soit la
     * demande. L'endpoint portait donc un nom generique pour un seul parcours,
     * et qui s'etait inscrit par adresse n'avait aucun moyen de se faire
     * renvoyer son code.
     *
     * <p>L'aiguillage entre les deux parcours est dans le service : c'est une
     * regle metier, pas une affaire de liaison HTTP.
     */
    @PostMapping(AppConstants.RESEND_CODE_ENDPOINT)
    public Mono<ResponseEntity<VerificationResponse>> resendCode(
            @Valid @RequestBody ResendCodeRequest request) {
        log.info(WebConstants.LOG_INFO_RESEND_CODE);

        return authService.renvoyerLeCode(request)
                .map(ResponseEntity::ok);
    }
    
    @GetMapping(AppConstants.UPDATE_PROFILE_ENDPOINT)
    public Mono<ResponseEntity<AuthResponse>> getProfile() {
        log.info(WebConstants.LOG_INFO_GET_PROFILE);

        return AppelantAuthentifie.identifiant()
                .flatMap(authService::getUserProfile)
                .map(ResponseEntity::ok);
    }
    
    @PutMapping(AppConstants.UPDATE_PROFILE_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_UPDATE_PROFILE_REQUEST);

        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> authService.updateProfile(
                        userId, request, contexteDe(exchange)))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.UPDATE_PROFILE_SUCCESS_MESSAGE)));
    }
    
    /**
     * Jeton temporaire OAuth2, lu dans {@code Authorization: Bearer}.
     *
     * <p>Il voyageait dans le corps de la requête. Le faire passer par
     * l'en-tête unifie le canal des justificatifs de ce service : Spring
     * Security peut alors trancher, au lieu de laisser le contrôleur seul juge.
     *
     * <p>Rend la chaîne vide si l'en-tête manque. Ce cas ne devrait pas se
     * produire — la matrice exige {@code hasRole("OAUTH2_TEMP")}, donc un appel
     * sans jeton valide n'atteint pas cette méthode — mais une chaîne vide
     * échoue proprement dans la validation du service, là où un {@code null}
     * lèverait.
     */
    private String jetonTemporaire(ServerWebExchange exchange) {
        String entete = exchange.getRequest().getHeaders()
                .getFirst(WebConstants.HEADER_AUTHORIZATION);
        if (entete == null || !entete.startsWith("Bearer ")) {
            return "";
        }
        return entete.substring("Bearer ".length());
    }

    // Méthode getClientIp supprimée - utiliser ReactiveRequestUtil.getClientIp() à la place
    
    /** Emet la redirection. L'URL est construite par le service. */
    private Mono<Void> rediriger(ServerWebExchange exchange, String url) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().setLocation(java.net.URI.create(url));
        return exchange.getResponse().setComplete();
    }


    // ==================== FLUX 1: AUTH/REGISTER PAR TÉLÉPHONE (OTP) ====================
    
    @PostMapping(AppConstants.PHONE_AUTH_REQUEST_ENDPOINT)
    public Mono<ResponseEntity<VerificationResponse>> requestPhoneAuth(
            @Valid @RequestBody PhoneAuthRequest request) {
        log.info(WebConstants.LOG_INFO_PHONE_AUTH_REQUEST);
        
        return Mono.defer(() -> authService.initiatePhoneAuth(request))
            .map(ResponseEntity::ok);
    }
    
    @PostMapping(AppConstants.PHONE_AUTH_VERIFY_ENDPOINT)
    public Mono<ResponseEntity<AuthResponse>> verifyPhoneAuth(
            @Valid @RequestBody PhoneAuthVerifyRequest request,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_PHONE_AUTH_VERIFY);
        
        return Mono.defer(() -> {
            String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
            String userAgent = ReactiveRequestUtil.getUserAgent(exchange);
            return authService.verifyPhoneAuth(request, ipAddress, userAgent);
        })
        .map(ResponseEntity::ok);
    }
    
    @PostMapping(AppConstants.PHONE_REGISTER_REQUEST_ENDPOINT)
    public Mono<ResponseEntity<VerificationResponse>> requestPhoneRegister(
            @Valid @RequestBody PhoneAuthRequest request) {
        log.info(WebConstants.LOG_INFO_PHONE_REGISTER_REQUEST);
        
        return Mono.defer(() -> authService.initiatePhoneRegister(request))
            .map(ResponseEntity::ok);
    }
    
    @PostMapping(AppConstants.PHONE_REGISTER_VERIFY_ENDPOINT)
    public Mono<ResponseEntity<AuthResponse>> verifyPhoneRegister(
            @Valid @RequestBody PhoneRegisterVerifyRequest request,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_PHONE_REGISTER_VERIFY);
        
        return Mono.defer(() -> {
            String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
            String userAgent = ReactiveRequestUtil.getUserAgent(exchange);
            return authService.verifyPhoneRegister(request, ipAddress, userAgent);
        })
        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }
    
    // ==================== FLUX 2: AUTH/REGISTER PAR EMAIL (OTP) ====================
    
    @PostMapping(AppConstants.EMAIL_AUTH_REQUEST_ENDPOINT)
    public Mono<ResponseEntity<VerificationResponse>> requestEmailAuth(
            @Valid @RequestBody EmailAuthRequest request) {
        log.info(WebConstants.LOG_INFO_EMAIL_AUTH_REQUEST);
        
        return Mono.defer(() -> authService.initiateEmailAuth(request))
            .map(ResponseEntity::ok);
    }
    
    @PostMapping(AppConstants.EMAIL_AUTH_VERIFY_ENDPOINT)
    public Mono<ResponseEntity<AuthResponse>> verifyEmailAuth(
            @Valid @RequestBody EmailAuthVerifyRequest request,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_EMAIL_AUTH_VERIFY);
        
        return Mono.defer(() -> {
            String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
            String userAgent = ReactiveRequestUtil.getUserAgent(exchange);
            return authService.verifyEmailAuth(request, ipAddress, userAgent);
        })
        .map(ResponseEntity::ok)
        .doOnError(throwable -> {
            log.error("Error in verifyEmailAuth for email {}: {}", request.getEmail(), throwable.getMessage());
        })
        .onErrorResume(throwable -> {
            // Let the GlobalExceptionHandler handle the exception properly
            return Mono.error(throwable);
        });
    }
    
    @PostMapping(AppConstants.EMAIL_REGISTER_ENDPOINT)
    public Mono<ResponseEntity<VerificationResponse>> requestEmailRegister(
            @Valid @RequestBody EmailOtpRequest request) {
        log.info(WebConstants.LOG_INFO_EMAIL_REGISTER);
        
        return Mono.defer(() -> authService.initiateEmailRegister(request))
            .map(ResponseEntity::ok)
            .doOnError(throwable -> {
                log.error("Error in requestEmailRegister for email {}: {}", request.getEmail(), throwable.getMessage());
            })
            .onErrorResume(throwable -> {
                return Mono.error(throwable);
            });
    }
    
    @PostMapping(AppConstants.EMAIL_REGISTER_VERIFY_ENDPOINT)
    public Mono<ResponseEntity<AuthResponse>> verifyEmailRegister(
            @Valid @RequestBody EmailRegisterVerifyRequest verifyRequest,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_EMAIL_REGISTER_VERIFY);
        
        return Mono.defer(() -> {
            String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
            String userAgent = ReactiveRequestUtil.getUserAgent(exchange);
            return authService.verifyEmailRegister(verifyRequest, ipAddress, userAgent);
        })
        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
        .doOnError(throwable -> {
            log.error("Error in verifyEmailRegister for email {}: {}", verifyRequest.getEmail(), throwable.getMessage());
        })
        .onErrorResume(throwable -> {
            return Mono.error(throwable);
        });
    }
    
    // ==================== FLUX 3: AUTH/REGISTER PAR OAUTH2 (GOOGLE/FACEBOOK) ====================
    
    /**
     * Endpoint pour initier l'authentification OAuth2
     * Génère l'URL d'autorisation et redirige l'utilisateur vers le provider
     */
    @GetMapping("/oauth2/authorize/{provider}")
    public Mono<ResponseEntity<Map<String, String>>> initiateOAuth2(
            @PathVariable String provider,
            @RequestParam(required = false) String redirect_uri) {
        log.info("Initiation OAuth2 pour le provider: {}", provider);
        
        return oauth2Service.getAuthorizationUrl(provider, redirect_uri)
            .map(authUrl -> {
                // Seul le début de l'URL est journalisé : la suite porte le
                // client_id et la valeur anti-falsification du parcours.
                log.info("URL d'autorisation OAuth2 générée pour {}: {}", provider,
                    authUrl.substring(0, Math.min(50, authUrl.length())));
                return Map.of(
                    "authorizationUrl", authUrl,
                    "provider", provider,
                    "message", "Redirigez l'utilisateur vers cette URL"
                );
            })
        .map(ResponseEntity::ok)
        .onErrorResume(e -> {
            log.error("Erreur OAuth2 authorize: {}", e.getMessage());
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                    "error", "invalid_provider",
                    "message", "Provider non supporté: " + provider,
                    "supportedProviders", "google, facebook"
                )));
        });
    }
    
    /**
     * Endpoint GET de callback OAuth2 - utilisé par Google/Facebook pour la redirection
     * Redirige l'utilisateur vers le frontend avec les données appropriées
     */
    @GetMapping(AppConstants.OAUTH2_CALLBACK_PROVIDER_ENDPOINT)
    public Mono<Void> handleOAuth2CallbackGet(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String error_description,
            ServerWebExchange exchange) {
        
        log.info("GET /auth/oauth2/callback - Callback OAuth2 - Provider: {}", provider);
        // Le `code` est un code d'AUTORISATION OAuth2 : il s'echange contre un
        // jeton d'acces chez le fournisseur. Le journaliser en clair — meme a
        // DEBUG, niveau actif en developpement et en pre-production — permet a
        // qui lit les journaux avant l'echange de prendre le compte.
        //
        // `state` est masque pour la meme raison : il protege contre la
        // falsification de requete inter-site, et le connaitre permet de forger
        // un rappel credible.
        log.debug("OAuth2 callback - code {}, state {}, error: {}, error_description: {}",
            SecretMasking.masquer(code), SecretMasking.masquer(state),
            error, error_description);
        
        // Gérer les erreurs OAuth2
        if (error != null) {
            log.error("OAuth2 error: {} - {}", error, error_description);
            return rediriger(exchange, oauth2Service.urlDErreur(error,
                    error_description != null ? error_description : "Unknown error"));
        }

        // Vérifier la présence du code
        if (code == null || code.isEmpty()) {
            log.error("OAuth2 callback error: authorization code is missing");
            return rediriger(exchange, oauth2Service.urlDErreur("missing_code",
                    "Authorization code is missing"));
        }
        
        return Mono.defer(() -> {
            OAuth2CallbackRequest request = OAuth2CallbackRequest.builder()
                    .code(code)
                    .provider(provider)
                    // Le state est transmis TEL QUEL, absence comprise. Il
                    // etait remplace par WebConstants.DEFAULT_OAUTH2_STATE —
                    // la chaine vide — ce qui faisait ressembler une absence a
                    // une presence : le service recevait une valeur et ne
                    // pouvait plus distinguer « aucun state » de « state vide ».
                    // Les deux sont refuses, mais le journal ne nommait pas la
                    // bonne cause.
                    .state(state)
                    .redirectUri(oauth2Service.redirectUriDuFournisseur(provider))
                    .build();
            
            String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
            String userAgent = ReactiveRequestUtil.getUserAgent(exchange);
            
            return oauth2Service.handleOAuth2Callback(request, ipAddress, userAgent);
        })
        .flatMap(response -> {
            // L'URL est construite par le SERVICE ; ce controleur n'emet que le
            // 302. Le contrat HTTP est inchange — meme statut, meme en-tete
            // Location — mais la regle « profil complet ou non, et quels
            // parametres » a desormais un seul detenteur, et le controleur ne
            // relit plus app.frontend-url que le service lit deja.
            log.info(response.isProfileComplete()
                    ? "OAuth2 : authentification reussie, redirection vers le front"
                    : "OAuth2 : profil incomplet, redirection vers la completion");
            // flatMap et non plus un appel direct : l'URL porte desormais un
            // code retenu dans Redis, donc sa construction est reactive. Voir
            // IOAuth2Service.urlDeRedirectionApresRappel.
            return oauth2Service.urlDeRedirectionApresRappel(response)
                    .flatMap(url -> rediriger(exchange, url));
        });
    }

    /**
     * Rappel OAuth2 en POST, impose par Apple.
     *
     * <h2>Pourquoi un second endpoint sur le meme chemin</h2>
     *
     * Apple <b>exige</b> {@code response_mode=form_post} des que le perimetre
     * demande {@code name} ou {@code email}, et refuse l'autorisation sinon. Le
     * rappel arrive donc en {@code POST}, avec un corps de formulaire, la ou
     * Google et Facebook reviennent en {@code GET} avec des parametres de
     * requete. Ce n'est pas un choix : c'est la seule forme qu'Apple accepte.
     *
     * <h2>Le champ user, et l'unique occasion de le lire</h2>
     *
     * Apple n'a aucun point de profil et ne transmet le nom qu'ici, et
     * <b>uniquement a la premiere autorisation</b>. Les connexions suivantes ne
     * le renvoient plus : non enregistre ce jour-la, il est perdu definitivement
     * — sauf si la personne revoque l'acces dans les reglages de son appareil.
     * C'est la cause connue des comptes Apple anonymes.
     *
     * <h2>Le state est verifie comme ailleurs</h2>
     *
     * La protection anti-falsification ne depend pas de la methode HTTP. Elle
     * compte d'autant plus ici que la protection CSRF de Spring est desactivee
     * — ce service est sans session et le jeton porte l'authentification — donc
     * ce POST inter-site aboutit bien jusqu'au controleur. Le {@code state} est
     * la seule chose qui distingue un rappel que nous avons initie.
     */
    @PostMapping(value = AppConstants.OAUTH2_CALLBACK_PROVIDER_ENDPOINT,
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<Void> handleOAuth2CallbackFormPost(
            @PathVariable String provider,
            ServerWebExchange exchange) {

        log.info("POST /auth/oauth2/callback - Rappel OAuth2 en form_post - Provider: {}",
                provider);

        return exchange.getFormData().flatMap(formulaire -> {
            String code = formulaire.getFirst("code");
            String state = formulaire.getFirst("state");
            String error = formulaire.getFirst("error");

            // Mêmes raisons qu'en GET : le code s'échange contre un jeton, et
            // connaître le state permet de forger un rappel crédible.
            log.debug("Rappel form_post - code {}, state {}, error {}",
                    SecretMasking.masquer(code), SecretMasking.masquer(state), error);

            if (error != null) {
                log.error("Rappel OAuth2 {} en erreur : {}", provider, error);
                return rediriger(exchange, oauth2Service.urlDErreur(error, "Rappel refusé"));
            }
            if (code == null || code.isEmpty()) {
                log.error("Rappel OAuth2 {} sans code d'autorisation", provider);
                return rediriger(exchange, oauth2Service.urlDErreur("missing_code",
                        "Authorization code is missing"));
            }

            ProfilInitialApple profil = ProfilInitialApple.lire(
                    formulaire.getFirst("user"), objectMapper);
            if (profil.estPresent()) {
                // Le nom n'est pas journalisé : donnée personnelle. Seul son
                // passage l'est, parce qu'il ne repassera jamais.
                log.info("Rappel {} : nom transmis a la premiere autorisation", provider);
            }

            OAuth2CallbackRequest request = OAuth2CallbackRequest.builder()
                    .code(code)
                    .provider(provider)
                    .state(state)
                    .redirectUri(oauth2Service.redirectUriDuFournisseur(provider))
                    .prenomDuRappel(profil.prenom())
                    .nomDuRappel(profil.nom())
                    .build();

            return oauth2Service.handleOAuth2Callback(request,
                            ReactiveRequestUtil.getClientIp(exchange),
                            ReactiveRequestUtil.getUserAgent(exchange))
                    .flatMap(reponse -> oauth2Service.urlDeRedirectionApresRappel(reponse))
                    .flatMap(url -> rediriger(exchange, url));
        });
    }

    /**
     * Endpoint POST de callback OAuth2 - utilisé par le frontend
     * Le frontend reçoit le code d'autorisation et l'envoie ici
     */
    @PostMapping(AppConstants.OAUTH2_CALLBACK_ENDPOINT)
    public Mono<ResponseEntity<OAuth2AuthResponse>> handleOAuth2Callback(
            @Valid @RequestBody OAuth2CallbackRequest request,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_OAUTH2_CALLBACK, request.getProvider());
        
        return Mono.defer(() -> {
            String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
            String userAgent = ReactiveRequestUtil.getUserAgent(exchange);
            return oauth2Service.handleOAuth2Callback(request, ipAddress, userAgent);
        })
            .map(response -> {
                if (response.isProfileComplete()) {
                    return ResponseEntity.ok(response);
                } else {
                    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(response);
                }
            });
    }

    /**
     * Echange le code de remise contre les jetons.
     *
     * <h2>Pourquoi cet endpoint existe</h2>
     *
     * La redirection de fin de parcours OAuth2 portait les jetons dans sa chaine
     * de requete : {@code ?accessToken=...&refreshToken=...}. Une chaine de
     * requete entre dans l'historique du navigateur, dans les journaux d'acces
     * de tout proxy et du frontal, et dans l'en-tete {@code Referer} des
     * requetes emises par la page chargee. Le jeton de rafraichissement y etait
     * le plus expose : il suffit a renouveler une session pendant des jours,
     * sans mot de passe ni second facteur.
     *
     * <p>La redirection ne porte plus qu'un code a usage unique, et c'est ici
     * qu'il s'echange — en POST, donc dans un corps, qui ne va dans aucun des
     * trois endroits ci-dessus.
     *
     * <h2>Statut de la reponse</h2>
     *
     * Le meme decoupage que le rappel ci-dessus : {@code 200} si le profil est
     * complet, {@code 206} s'il reste des champs a fournir. Le frontal n'a donc
     * pas a connaitre deux conventions selon le chemin par lequel il est arrive.
     *
     * @see com.abdatytch.auth_service.service.oauth2.CodeDeRemiseOAuth2Redis
     */
    @PostMapping(AppConstants.OAUTH2_EXCHANGE_ENDPOINT)
    public Mono<ResponseEntity<OAuth2AuthResponse>> echangerLeCodeDeRemise(
            @Valid @RequestBody OAuth2ExchangeRequest requete) {

        // Le code N'EST PAS journalise : il vaut les jetons qu'il designe le
        // temps de sa validite. Le journaliser remettrait dans les journaux ce
        // que cet endpoint existe pour en sortir.
        log.info("OAuth2 : echange d'un code de remise");

        return oauth2Service.echanger(requete.getCode())
                .map(reponse -> reponse.isProfileComplete()
                        ? ResponseEntity.ok(reponse)
                        : ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(reponse));
    }

    /**
     * Endpoint pour compléter le profil utilisateur après OAuth2
     * Utilisé quand Google/Facebook ne fournit pas toutes les infos requises (téléphone, etc.)
     */
    @PostMapping(AppConstants.OAUTH2_COMPLETE_PROFILE_ENDPOINT)
    public Mono<ResponseEntity<OAuth2AuthResponse>> completeOAuth2Profile(
            @Valid @RequestBody ProfileCompletionRequest request,
            ServerWebExchange exchange) {
        log.info(WebConstants.LOG_INFO_OAUTH2_PROFILE_COMPLETION);
        
        return Mono.defer(() -> {
            String ipAddress = ReactiveRequestUtil.getClientIp(exchange);
            String userAgent = ReactiveRequestUtil.getUserAgent(exchange);
            // Le jeton temporaire est lu dans Authorization: Bearer, et recopie
            // dans la requete pour le service, qui le valide une seconde fois.
            //
            // Deux verifications du meme jeton, et c'est voulu : Spring Security
            // garantit qu'il est present et du bon type AVANT que ce code ne
            // tourne, le service verifie en outre son expiration et en extrait
            // le fournisseur. Retirer la seconde ferait dependre l'extraction
            // d'un filtre, a distance du code qui s'en sert.
            request.setTempToken(jetonTemporaire(exchange));
            return oauth2Service.completeProfile(request, ipAddress, userAgent);
        })
        .map(response -> (ResponseEntity<OAuth2AuthResponse>) ResponseEntity.ok(response));
    }

    /**
     * Les circonstances de l'appel, pour les alertes de securite.
     *
     * <p>Assemblees ici et non dans le service : l'adresse de l'appelant se lit
     * dans l'echange HTTP, et {@code ReactiveRequestUtil.getClientIp} applique
     * la resolution d'adresse du depot — celle qui ne se fie pas aveuglement a
     * {@code X-Forwarded-For}.
     *
     * <p>Sans geolocalisation : ces deux chemins n'en font pas la recherche, et
     * l'ajouter couterait un appel reseau sur une operation qui n'en a pas
     * besoin. Le chemin de connexion, lui, la cherche deja pour son journal et
     * la transmet a l'alerte.
     */
    private static ContexteDeSecurite contexteDe(ServerWebExchange exchange) {
        return ContexteDeSecurite.de(
                ReactiveRequestUtil.getClientIp(exchange),
                ReactiveRequestUtil.getUserAgent(exchange));
    }
}
