package com.abdatytch.interface_service.controller;

import com.abdatytch.commons.security.ClesRedisSecurite;
import com.abdatytch.commons.security.GatewayClientIp;
import com.abdatytch.commons.security.SecretMasking;
import com.abdatytch.interface_service.dto.request.AdminLoginRequest;
import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.repository.UserRepository;
import com.abdatytch.interface_service.service.EmailService;
import com.abdatytch.interface_service.service.FaitsDeTentativeAdmin;
import com.abdatytch.interface_service.service.GeoLocationService;
import com.abdatytch.interface_service.service.IDevJwtService;
import com.abdatytch.commons.totp.TotpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import java.util.HexFormat;

/**

 * Contrôleur REST pour l'administration
 * Authentification sécurisée avec TOTP obligatoire
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "Administration", description = "API d'administration sécurisée")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    /**
     * Validite des deux liens de l'alerte de connexion.
     *
     * <p>Le nombre etait ecrit DEUX fois : {@code plusMinutes(30)} pour
     * l'echeance reelle, et « 30 minutes » dans le texte du courriel. Rien
     * n'obligeait les deux a rester d'accord, et un message qui annonce une
     * echeance fausse fait cliquer sur un lien deja mort.
     */
    private static final Duration VALIDITE_DE_LA_CONFIRMATION = Duration.ofMinutes(30);

    /** Validite du lien de maintenance. Meme raison, meme remede. */
    private static final Duration VALIDITE_DE_LA_MAINTENANCE = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final IDevJwtService jwtService;
    private final EmailService emailService;
    private final GeoLocationService geoLocationService;
    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * L'adresse du compte administrateur.
     *
     * <h3>La clef lue etait {@code admin.mail.from}, et elle n'existe plus</h3>
     *
     * Ce bloc de configuration a ete retire du module en meme temps que ses
     * trois comptes SMTP — ce service n'expedie plus rien lui-meme. Le
     * {@code @Value} n'avait AUCUNE valeur par defaut : un demarrage neuf
     * aurait echoue sur un marqueur non resolu.
     *
     * <h3>Et c'etait deja la mauvaise clef</h3>
     *
     * Ce champ ne sert pas a expedier : il sert a RETROUVER le compte
     * administrateur ({@code findByEmail}) et a lui adresser l'alerte de
     * tentative de connexion. Le commentaire de {@code getAdminInfo} disait
     * d'ailleurs que l'adresse devait venir d'{@code ADMIN_DEFAULT_EMAIL} —
     * le code lisait autre chose. {@code AdminSeeder},
     * {@code RecoverySecretScheduler} et {@code MaintenanceController} lisent
     * tous les trois {@code admin.default.email} ; ce champ les rejoint.
     *
     * <h3>Le destinataire de l'alerte est donc le bon</h3>
     *
     * L'alerte « est-ce vous ? » ne peut etre repondue que par la personne a
     * qui appartient le compte. L'ancienne clef y envoyait l'adresse
     * d'EXPEDITION, ce qui marchait par coincidence tant que les deux etaient
     * la meme boite.
     */
    @Value("${admin.default.email}")
    private String adminEmail;

    @Value("${server.port:8081}")
    private String serverPort;

    /**

     * Destinataire des alertes critiques de sécurité.
     *
     * <p>Cette adresse était écrite en littéral dans
     * {@code sendCriticalAlertEmail}. Outre qu'il s'agit d'une donnée
     * personnelle dans un dépôt, cela rendait la destination des alertes
     * impossible à changer sans recompiler — et lisible par quiconque veut
     * savoir qui prévenir d'éviter.
     */
    @Value("${security.critical-alert.email}")
    private String criticalAlertEmail;

    /**

     * Base publique du portail, pour construire le lien de maintenance.
     * L'adresse était figée sur {@code localhost:8081}, donc le lien envoyé
     * par courriel était inutilisable dès que le portail n'était plus servi
     * depuis la machine de développement.
     */
    @Value("${app.public-base-url}")
    private String publicBaseUrl;

    public AdminController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          TotpService totpService,
                          IDevJwtService jwtService,
                          EmailService emailService,
                          GeoLocationService geoLocationService,
                          ReactiveStringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.jwtService = jwtService;
        this.emailService = emailService;
        this.geoLocationService = geoLocationService;
        this.redisTemplate = redisTemplate;
    }



    /**



     * Authentification admin avec TOTP obligatoire
     */
    @PostMapping("/login")
    @Operation(summary = "Connexion administrateur avec TOTP",
               description = "Authentification sécurisée nécessitant email, mot de passe et code TOTP")
    public Mono<ResponseEntity<Map<String, Object>>> adminLogin(
            @RequestBody AdminLoginRequest request,
            @RequestHeader(value = "User-Agent", required = false) String userAgent,
            ServerWebExchange exchange) {
        log.info(" Tentative de connexion admin pour: {}", request.getEmail());

        // L'adresse vient du Gateway, jamais d'un en-tete fourni par l'appelant.
        // Elle alimente la geolocalisation et les alertes de securite : une
        // adresse choisie par l'attaquant y designait un pays arbitraire.
        String ipAddress = GatewayClientIp.of(exchange);

        // Récupérer le User-Agent
        String userAgentStr = userAgent != null ? userAgent : "unknown";

        return userRepository.findByEmail(request.getEmail())
            .switchIfEmpty(Mono.defer(() -> {
                log.warn(" Tentative de connexion admin avec email inexistant: {}", request.getEmail());
                return Mono.error(new RuntimeException("Identifiants invalides"));
            }))
            .flatMap(user -> validateAdminCredentials(user, request, ipAddress, userAgentStr))
            .flatMap(user -> validateTotpCode(user, request.getTotpCode()))
            .flatMap(this::generateAdminToken)
            .map(tokenData -> {
                log.info(" Connexion admin réussie pour: {}", request.getEmail());
                return ResponseEntity.ok(Map.of(
                    "message", "Connexion admin réussie",
                    "accessToken", tokenData.get("accessToken"),
                    "refreshToken", tokenData.get("refreshToken"),
                    "expiresIn", tokenData.get("expiresIn"),
                    "role", "ADMIN"));
            })
            .onErrorResume(error -> {
                log.error(" Échec de connexion admin pour {}: {}", request.getEmail(), error.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Échec d'authentification", "message", error.getMessage())));
            });
    }

    /**

     * Affiche les informations admin masquées (sécurisé)
     */
    @GetMapping("/info")
    @Operation(summary = "Informations admin", description = "Affiche les informations admin avec secrets masqués")
    public Mono<ResponseEntity<Map<String, Object>>> getAdminInfo() {
        // L'adresse était écrite en dur ("admin@abdaty.com") et ne correspondait
        // donc pas au compte réellement créé par AdminSeeder, qui vient de
        // ADMIN_DEFAULT_EMAIL.
        return userRepository.findByEmail(adminEmail)
            .map(admin -> {
                Map<String, Object> info = new HashMap<>();
                info.put("email", admin.getEmail());
                info.put("phone", admin.getPhoneNumber());
                // Seul l'ÉTAT du second facteur est exposé, jamais son secret —
                // pas même tronqué. Huit caractères en révélaient 40 bits sur 160.
                info.put("totpEnabled", admin.getTotpEnabled());
                info.put("lastLogin", admin.getLastLoginAt());
                info.put("accountLocked", admin.isAccountLocked());

                return ResponseEntity.ok(info);
            })
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()));
    }

    /**

     * Génère l'URL QR Code pour configurer TOTP (endpoint protégé)
     */
    /**
     * Enrôlement du second facteur, en un seul passage.
     *
     * <h3>Ce que corrige cette version</h3>
     *
     * L'endpoint était un {@code GET /admin/totp/qr-code?email=...} déclaré
     * {@code permitAll()}, et il renvoyait {@code "secret": totpSecret} en clair.
     * <b>N'importe qui connaissant l'adresse de l'administrateur pouvait donc
     * récupérer la graine de son second facteur, sans s'authentifier.</b> Et
     * cette adresse était elle-même écrite en dur dans le dépôt.
     *
     * <p>Le second facteur ne protégeait donc rien : un attaquant obtenait le
     * secret, calculait des codes valides indéfiniment, et il ne lui restait que
     * le mot de passe à trouver — c'est-à-dire un seul facteur.
     *
     * <h3>Pourquoi l'endpoint ne pouvait pas être simplement protégé</h3>
     *
     * {@code /admin/login} exige déjà un code TOTP. Un administrateur qui vient
     * d'être créé ne peut donc pas se connecter avant d'avoir enrôlé son second
     * facteur, ni enrôler s'il faut être connecté. C'est cette impasse qui avait
     * conduit au {@code permitAll()}.
     *
     * <p>La sortie retenue : exiger le <b>mot de passe</b>, et ne servir le
     * secret que tant que le second facteur n'est pas activé. L'enrôlement
     * devient ainsi à usage unique, adossé à un facteur que l'attaquant ne
     * possède pas.
     */
    @PostMapping("/totp/enroll")
    @Operation(summary = "Enrôler le second facteur (une seule fois)",
               description = "Exige le mot de passe. Ne renvoie le secret que tant que "
                    + "le second facteur n'est pas activé.")
    public Mono<ResponseEntity<Map<String, String>>> enrollTotp(
            @RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        if (email == null || password == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "email et password sont requis")));
        }

        return userRepository.findByEmail(email)
            .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()))
            .flatMap(user -> {
                if (Boolean.TRUE.equals(user.getTotpEnabled())) {
                    // Le secret a déjà été remis et le facteur est actif : le
                    // resservir rouvrirait exactement la faille corrigée ici.
                    log.warn(" Enrôlement TOTP refusé pour {} : déjà actif", email);
                    return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Map.of("error", "Le second facteur est déjà actif. "
                    + "Passer par /maintenance/regenerate-totp pour le réinitialiser.")));
                }
                if (user.getTotpSecret() == null) {
                    return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(Map.of("error", "Aucun secret TOTP généré pour ce compte")));
                }

                String qrCodeUrl = totpService.generateQrCodeUrl(
                        user.getEmail(), user.getTotpSecret(), "Abdaty Interface");

                user.setTotpEnabled(true);
                return userRepository.save(user)
                        .doOnSuccess(saved -> log.info(
                                " Second facteur enrôlé pour {} — le secret ne sera plus servi", email))
                        .thenReturn(ResponseEntity.ok(Map.of(
                                "qrCodeUrl", qrCodeUrl,
                                "secret", user.getTotpSecret(),
                                "message", "Scannez ce QR Code MAINTENANT : ce secret ne sera plus affiché.")));
            })
            // Identifiants faux comme compte inexistant donnent la même réponse :
            // distinguer les deux permettrait d'énumérer les comptes.
            .switchIfEmpty(Mono.fromCallable(() -> {
                log.warn(" Enrôlement TOTP refusé pour {}", email);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Identifiants invalides"));
            }));
    }

    /**

     * Valide les identifiants admin (email + mot de passe) avec protection par confirmation
     * UNE SEULE tentative incorrecte déclenche une alerte email et bloque le compte jusqu'à confirmation
     */
    private Mono<User> validateAdminCredentials(User user, AdminLoginRequest request, String ipAddress, String userAgent) {
        return Mono.fromCallable(() -> {
            // Vérifier que c'est bien un admin (basé sur le rôle)
            if (!"ADMIN".equals(user.getRole())) {
                throw new RuntimeException("Accès administrateur requis");
            }

            // Vérifier si le compte est en attente de confirmation
            if (user.isAdminConfirmationPending()) {
                throw new RuntimeException("Compte en attente de confirmation. Veuillez vérifier votre email pour confirmer si c'est vous.");
            }

            // Vérifier le mot de passe
            if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                // Incrémenter les tentatives échouées
                user.incrementFailedLoginAttempts();
                int attempts = user.getFailedLoginAttempts();

                // Si c'est la 1ère tentative incorrecte, envoyer l'alerte email et bloquer jusqu'à confirmation
                if (attempts == 1) {
                    // Générer un token de confirmation
                    String confirmationToken = generateConfirmationToken();

                    // Marquer le compte comme en attente de confirmation
                    user.setAdminConfirmationPending(true);
                    user.setAdminConfirmationToken(confirmationToken);
                    user.setAdminConfirmationExpiresAt(LocalDateTime.now()
                            .plusMinutes(VALIDITE_DE_LA_CONFIRMATION.toMinutes()));
                    user.setAdminConfirmationIp(ipAddress);

                    // Sauvegarder l'utilisateur
                    userRepository.save(user).subscribe();

                    // Envoyer l'email de confirmation avec toutes les infos détaillées
                    sendAdminConfirmationEmail(user, confirmationToken, ipAddress, userAgent);

                    throw new RuntimeException("Tentative de connexion détectée. Un email de confirmation a été envoyé. Veuillez confirmer si c'est vous.");
                }

                // Logique de couche critique après confirmation
                // Si l'admin a déjà confirmé que "c'est moi" et continue à échouer
                if (!user.isAdminConfirmationPending()) {
                    user.incrementPostConfirmationAttempts();
                    int postAttempts = user.getPostConfirmationAttempts();

                    // Après 3 tentatives après confirmation  envoyer email critique + bloquer IP + compte
                    if (postAttempts >= 3 && !Boolean.TRUE.equals(user.getCriticalAlertSent())) {
                        user.setCriticalAlertSent(true);
                        user.lockAccount(1440); // Bloquer le compte pour 24h

                        // Bloquer l'IP dans Redis
                        blockIpAddressInRedis(ipAddress);

                        // Générer un token de maintenance
                        String maintenanceToken = generateMaintenanceToken();
                        user.setCriticalMaintenanceToken(maintenanceToken);
                        user.setCriticalMaintenanceExpiresAt(LocalDateTime.now()
                                .plusHours(VALIDITE_DE_LA_MAINTENANCE.toHours()));

                        // Envoyer l'email critique avec toutes les infos
                        sendCriticalAlertEmail(user, maintenanceToken, ipAddress, userAgent);

                        log.warn(" ALERTE CRITIQUE : 3 tentatives après confirmation pour admin {}. IP bloquée + Compte bloqué (24h)", user.getEmail());
                    }

                    // Bloquer définitivement après 5 tentatives après confirmation
                    if (postAttempts >= 5) {
                        user.lockAccount(10080); // 7 jours
                        blockIpAddressInRedis(ipAddress);
                        log.error(" BLOCAGE DÉFINITIF : 5 tentatives après confirmation pour admin {}", user.getEmail());
                    }
                }

                userRepository.save(user).subscribe();
                throw new RuntimeException("Identifiants invalides");
            }

            // Réinitialiser les tentatives après succès
            user.resetFailedLoginAttempts();
            user.resetPostConfirmationAttempts();
            return user;
        });
    }

    /**

     * Génère un token de confirmation sécurisé
     */
    private String generateConfirmationToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Previent l'administrateur qu'une connexion a son compte a ete tentee.
     *
     * <h3>Le corps du message n'est plus compose ici</h3>
     *
     * Cette methode ecrivait cent lignes de HTML en {@code String.format},
     * DEUX fois — une par branche de la geolocalisation. Le HTML partait vers
     * un modele sans variante {@code corps-html} : le courriel arrivait en
     * texte brut, balisage compris.
     *
     * <p>Pire, {@code userAgent} et les champs de geolocalisation y entraient
     * sans echappement. Le {@code User-Agent} est pose par la personne que
     * cette alerte denonce : elle pouvait ecrire un lien dans le message qui
     * la signale. Passes en variables d'un gabarit, ils sont echappes par le
     * rendu de notification-service.
     *
     * <h3>Une geolocalisation absente ne supprime plus l'alerte</h3>
     *
     * L'ancien code s'abonnait avec un gestionnaire d'erreur qui renvoyait un
     * second message, plus court. Il n'avait en revanche rien pour un
     * {@code Mono} VIDE — et un Mono vide ne declenche ni l'un ni l'autre :
     * l'alerte ne partait tout simplement pas. {@code defaultIfEmpty} ferme ce
     * cas, et les champs manquants portent « non disponible ».
     */
    private void sendAdminConfirmationEmail(User user, String confirmationToken,
                                            String ipAddress, String userAgent) {
        String baseUrl = publicBaseUrl + "/admin/confirm";
        String confirmUrl = baseUrl + "?token=" + confirmationToken + "&action=confirm";
        String blockUrl = baseUrl + "?token=" + confirmationToken + "&action=block";

        FaitsDeTentativeAdmin sansGeo = new FaitsDeTentativeAdmin(
                user.getEmail(), ipAddress, userAgent, null,
                user.getFailedLoginAttempts(), user.getPostConfirmationAttempts());

        geoLocationService.getDetailedGeoInfo(ipAddress, userAgent)
            .map(geo -> new FaitsDeTentativeAdmin(user.getEmail(), ipAddress, userAgent,
                    geo, user.getFailedLoginAttempts(), user.getPostConfirmationAttempts()))
            .onErrorResume(erreur -> {
                log.warn("Geolocalisation indisponible pour l'alerte de connexion "
                        + "administrateur, l'alerte part sans elle : {}", erreur.getMessage());
                return Mono.just(sansGeo);
            })
            .defaultIfEmpty(sansGeo)
            .flatMap(faits -> emailService.alerterSurUneConnexionAdmin(adminEmail, faits,
                    confirmUrl, blockUrl, VALIDITE_DE_LA_CONFIRMATION.toMinutes()))
            .subscribe(rien -> { },
                    erreur -> log.error("Alerte de connexion administrateur non envoyee : {}",
                            erreur.getMessage()));
    }

    /**

     * Valide le code TOTP
     */
    private Mono<User> validateTotpCode(User user, String totpCode) {
        if (user.getTotpSecret() == null || !Boolean.TRUE.equals(user.getTotpEnabled())) {
            return Mono.error(new RuntimeException("TOTP non configuré pour cet administrateur"));
        }

        return totpService.validateTotpCode(user.getTotpSecret(), totpCode)
            .flatMap(isValid -> {
                if (!isValid) {
                    return Mono.error(new RuntimeException("Code TOTP invalide"));
                }

                // Mettre à jour la dernière connexion
                user.setLastLoginAt(LocalDateTime.now());
                user.resetFailedLoginAttempts();

                return userRepository.save(user);
            });
    }

    /**

     * Endpoint de confirmation admin (C'est moi / Ce n'est pas moi)
     */
    @GetMapping("/confirm")
    @Operation(summary = "Confirmation admin", description = "Confirme si c'est l'admin ou bloque l'IP")
    public Mono<ResponseEntity<Map<String, String>>> confirmAdminAccess(
            @RequestParam String token,
            @RequestParam String action) {
        // Le jeton est masque ici, a la source.
        //
        // Le MaskingPatternLayout de ce service l'attrapait deja a l'ecriture
        // (son motif exige « token » adjacent au separateur, ce que « Token: »
        // satisfait). Mais ce filet n'existe que dans interface-service, et il
        // repose sur une liste de mots surveilles — le mode d'echec deja
        // rencontre dans ce depot, quand un filtre sur SECRET|PASSWORD|TOKEN
        // laissait passer 153 des 182 variables d'environnement.
        //
        // Celui qui ecrit la ligne sait ce qu'il journalise ; le motif, lui,
        // devine. Le filet reste utile comme derniere barriere.
        log.info(" Confirmation admin reçue - Action: {}, jeton {}",
                action, SecretMasking.masquer(token));

        // Requete par jeton, et NON findAll() suivi d'un filtre en memoire.
        //
        // `findByAdminConfirmationToken` existait deja dans le depot et
        // n'etait appelee nulle part : encore une methode ecrite, presente et
        // jamais atteinte. Le controleur chargeait donc la table entiere des
        // utilisateurs pour en trouver un, a chaque clic sur le lien de
        // confirmation.
        //
        // Au-dela du cout, le filtre comparait le jeton avec `.equals`, dont le
        // temps d'execution depend du nombre de caracteres identiques en tete.
        // Une recherche indexee ne presente pas cette caracteristique, et c'est
        // la forme retenue partout ailleurs dans ce depot pour un jeton a usage
        // unique (reinitialisation de mot de passe, verification d'adresse).
        return userRepository.findByAdminConfirmationToken(token)
            .flatMap(user -> {
                // Vérifier si le token n'a pas expiré
                if (user.getAdminConfirmationExpiresAt() == null || user.getAdminConfirmationExpiresAt().isBefore(LocalDateTime.now())) {
                    user.setAdminConfirmationPending(false);
                    user.setAdminConfirmationToken(null);
                    user.setAdminConfirmationExpiresAt(null);
                    user.setAdminConfirmationIp(null);
                    return userRepository.save(user)
                        .thenReturn(ResponseEntity.status(HttpStatus.GONE)
                            .body(Map.of("error", "Token expiré")));
                }

                if ("confirm".equals(action)) {
                    // C'est moi : débloquer le compte
                    user.setAdminConfirmationPending(false);
                    user.setAdminConfirmationToken(null);
                    user.setAdminConfirmationExpiresAt(null);
                    user.setAdminConfirmationIp(null);
                    user.resetFailedLoginAttempts();
                    user.resetPostConfirmationAttempts(); // Réinitialiser les tentatives après confirmation

                    return userRepository.save(user)
                        .thenReturn(ResponseEntity.ok(Map.of("message", "Compte admin débloqué avec succès")));
                } else if ("block".equals(action)) {
                    // Ce n'est pas moi : bloquer l'IP via Redis Gateway
                    String ipToBlock = user.getAdminConfirmationIp();
                    user.setAdminConfirmationPending(false);
                    user.setAdminConfirmationToken(null);
                    user.setAdminConfirmationExpiresAt(null);
                    user.setAdminConfirmationIp(null);
                    user.resetFailedLoginAttempts();

                    // Le blocage est CHAINE, et non lance de cote.
                    //
                    // `blockIpAddressInRedis` se terminait par `.subscribe(...)`
                    // avec deux lambdas de journalisation : le blocage partait
                    // en parallele de la reponse. Consequences :
                    //
                    //  - la reponse annonçait « IP bloquée avec succès » avant
                    //    de savoir si Redis avait accepte l'ecriture, et un
                    //    echec ne laissait qu'une ligne d'erreur dans les
                    //    journaux ;
                    //  - sur l'action « ce n'est pas moi », le blocage EST
                    //    l'objet de la requete. Une API qui affirme avoir bloque
                    //    sans l'avoir fait est pire qu'une API qui echoue, parce
                    //    que personne ne va verifier.
                    //
                    // Le blocage precede l'enregistrement : s'il echoue, le
                    // drapeau de confirmation reste pose et la personne peut
                    // recliquer. L'inverse perdrait le jeton sans avoir bloque.
                    return blockIpAddressInRedis(ipToBlock)
                        .then(userRepository.save(user))
                        .thenReturn(ResponseEntity.ok(Map.of("message", "IP bloquée avec succès")));
                } else {
                    return Mono.just(ResponseEntity.badRequest()
                        .body(Map.of("error", "Action invalide")));
                }
            })
            .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Token invalide"))))
            .onErrorResume(error -> {
                log.error(" Erreur confirmation admin: {}", error.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Erreur lors de la confirmation")));
            });
    }

    /**

     * Génère les tokens JWT pour l'admin
     */
    private Mono<Map<String, Object>> generateAdminToken(User admin) {
        return Mono.fromCallable(() -> {
            String accessToken = jwtService.generateToken(admin);
            String refreshToken = jwtService.generateRefreshToken(admin);

            return Map.of(
                "accessToken", accessToken,
                "refreshToken", refreshToken,
                "expiresIn", jwtService.getAccessTokenExpiration()
            );
        });
    }

    /**

     * Bloque une adresse IP via Redis (utilisé par le Gateway)
     */
    private Mono<Void> blockIpAddressInRedis(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equals(ipAddress)) {
            // Rien a bloquer : ce n'est pas une erreur, mais le journal doit le
            // dire, car l'appelant recevra « IP bloquée avec succès ».
            log.warn(" IP invalide, impossible de bloquer: {}", ipAddress);
            return Mono.empty();
        }

        // Clé Redis partagée avec le Gateway — voir docs/GATEWAY-SECURITY.md.
        // Ce préfixe était auparavant "ip:blacklist:", que le Gateway ne lisait
        // pas : il cherchait un ensemble nommé "ip_blacklist", dans une autre
        // base Redis. Le blocage était donc écrit et jamais appliqué.
        //
        // Le littéral a été remplacé par la constante partagée. Il était juste,
        // et c'est précisément ce qui le rendait trompeur : le filtre du MÊME
        // module lisait alors une propriété que la configuration fixait à
        // "blacklist:ip:". Deux préfixes dans un seul module, dont un seul
        // atteignait le Gateway — et le littéral correct ici masquait que la
        // détection automatique, elle, écrivait dans le vide.
        String blacklistKey = ClesRedisSecurite.PREFIXE_IP_BLOQUEE + ipAddress;

        return redisTemplate.opsForValue()
                .set(blacklistKey, "blocked_by_admin", Duration.ofDays(120))
                .doOnSuccess(ok -> log.info(
                        " IP {} bloquée suite à confirmation admin (ce n'est pas moi)",
                        ipAddress))
                .doOnError(e -> log.error(
                        " Erreur lors du blocage IP dans Redis: {}", e.getMessage()))
                // Une reponse VIDE de Redis ne doit pas etre rapportee comme un
                // blocage reussi.
                //
                // Precision importante, verifiee par mutation : contrairement a
                // `flatMap`, **`then()` souscrit a la suite meme sur une source
                // vide**. La requete ne serait donc PAS perdue sans ce
                // defaultIfEmpty — j'avais d'abord ecrit l'inverse, en
                // appliquant par reflexe la lecon du piege Mono<Void> la ou elle
                // ne vaut pas.
                //
                // Ce que ce defaultIfEmpty apporte reellement : fermer plutot
                // qu'ouvrir. Sans lui, un `set` qui ne confirme rien laisse la
                // reponse annoncer « IP bloquée avec succès ».
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(ecrit -> {
                    if (Boolean.FALSE.equals(ecrit)) {
                        // Redis a repondu sans confirmer l'ecriture : ne pas
                        // annoncer un blocage qui n'a pas eu lieu.
                        return Mono.error(new IllegalStateException(
                                "Blocage d'IP non confirmé par Redis"));
                    }
                    return Mono.empty();
                })
                .then();
    }

    /**

     * Génère un token de maintenance sécurisé
     */
    private String generateMaintenanceToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Signale que la confirmation a ete franchie et que les tentatives
     * continuent.
     *
     * <p>Meme correction que {@code sendAdminConfirmationEmail} : le rapport
     * etait ecrit deux fois en {@code String.format}, et un {@code Mono} vide
     * de la geolocalisation faisait disparaitre l'alerte la plus grave du
     * service. Il part en CRITIQUE, donc par le compte d'expedition dedie,
     * avec repli sur le compte ordinaire.
     */
    private void sendCriticalAlertEmail(User user, String maintenanceToken,
                                        String ipAddress, String userAgent) {
        String maintenanceUrl = publicBaseUrl + "/maintenance/auth?token=" + maintenanceToken;

        FaitsDeTentativeAdmin sansGeo = new FaitsDeTentativeAdmin(
                user.getEmail(), ipAddress, userAgent, null,
                user.getFailedLoginAttempts(), user.getPostConfirmationAttempts());

        geoLocationService.getDetailedGeoInfo(ipAddress, userAgent)
            .map(geo -> new FaitsDeTentativeAdmin(user.getEmail(), ipAddress, userAgent,
                    geo, user.getFailedLoginAttempts(), user.getPostConfirmationAttempts()))
            .onErrorResume(erreur -> {
                log.warn("Geolocalisation indisponible pour l'alerte critique, "
                        + "l'alerte part sans elle : {}", erreur.getMessage());
                return Mono.just(sansGeo);
            })
            .defaultIfEmpty(sansGeo)
            .flatMap(faits -> emailService.alerterSurUneAttaqueAdmin(criticalAlertEmail,
                    faits, maintenanceUrl, VALIDITE_DE_LA_MAINTENANCE.toHours()))
            .subscribe(rien -> { },
                    erreur -> log.error("Alerte critique non envoyee : {}",
                            erreur.getMessage()));
    }
}
