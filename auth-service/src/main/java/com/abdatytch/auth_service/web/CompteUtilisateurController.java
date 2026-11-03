package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.WebConstants;
import com.abdatytch.auth_service.dto.request.BasculeRequest;
import com.abdatytch.auth_service.dto.request.ChangementDeNumeroRequest;
import com.abdatytch.auth_service.dto.request.ChangePasswordRequest;
import com.abdatytch.auth_service.dto.request.CodeRequest;
import com.abdatytch.auth_service.dto.request.ConfianceDemandeRequest;
import com.abdatytch.auth_service.dto.ContexteDeSecurite;
import com.abdatytch.auth_service.dto.response.AlerteDeConnexionResponse;
import com.abdatytch.auth_service.dto.response.AppareilDeConfianceResponse;
import com.abdatytch.auth_service.dto.response.CompteSocialResponse;
import com.abdatytch.auth_service.dto.response.ConfianceAccordeeResponse;
import com.abdatytch.auth_service.dto.response.EtatDesCodesResponse;
import com.abdatytch.auth_service.dto.response.LotDeCodesResponse;
import com.abdatytch.auth_service.dto.response.MessageResponse;
import com.abdatytch.auth_service.dto.response.SessionResponse;
import com.abdatytch.auth_service.dto.response.TotpEnrolementResponse;
import com.abdatytch.auth_service.dto.response.VerificationResponse;
import com.abdatytch.auth_service.enums.OAuthProvider;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.IAlerteDeSecuriteService;
import com.abdatytch.auth_service.service.IAppareilDeConfianceService;
import com.abdatytch.auth_service.service.IAuthService;
import com.abdatytch.auth_service.service.ICodeDeSecoursService;
import com.abdatytch.auth_service.service.IConfirmationDeContactService;
import com.abdatytch.auth_service.service.ISecondFacteurService;
import com.abdatytch.auth_service.service.ISessionService;
import com.abdatytch.auth_service.service.oauth2.IComptesSociauxService;
import com.abdatytch.auth_service.util.AppelantAuthentifie;
import com.abdatytch.auth_service.util.ReactiveRequestUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Le libre-service du compte : ce qu'une personne connectee peut faire
 * elle-meme.
 *
 * <h2>Pourquoi un controleur a part</h2>
 *
 * {@code AuthController} porte les parcours qui <b>menent</b> a une session :
 * inscription, connexion, rappel OAuth2, recuperation. Ce controleur porte ce
 * qui se fait <b>depuis</b> une session, sur son propre compte. La frontiere est
 * la meme que celle de la matrice d'autorisation, ce qui la rend lisible des
 * deux cotes.
 *
 * <p>Il evite aussi de pousser {@code AuthController} au-dela de cinq cents
 * lignes, ou plus personne ne retrouve une regle.
 *
 * <h2>L'identifiant du compte ne vient JAMAIS de la requete</h2>
 *
 * Toutes les methodes partent de {@link AppelantAuthentifie#identifiant()},
 * c'est-a-dire du jeton verifie. Aucun chemin, aucun corps ne porte
 * d'identifiant de compte : c'est ce qui rend l'IDOR impossible par
 * construction plutot que par vigilance. Ce depot a corrige des IDOR sur la
 * passerelle en partant du constat inverse.
 *
 * <h2>Aucune logique ici</h2>
 *
 * Chaque methode resout l'appelant, delegue, et traduit en reponse HTTP. Les
 * regles — un code valide pour desactiver le second facteur, le dernier moyen de
 * connexion qu'on ne delie pas, la confirmation qui ne s'envoie pas deux fois —
 * vivent dans les services, ou un seul detenteur les porte.
 */
@RestController
@RequestMapping(AppConstants.API_BASE_PATH)
@RequiredArgsConstructor
@Slf4j
public class CompteUtilisateurController {

    private final IAuthService authService;
    private final ISecondFacteurService secondFacteurService;
    private final ISessionService sessionService;
    private final IConfirmationDeContactService confirmationDeContactService;
    private final IComptesSociauxService comptesSociauxService;
    private final IAppareilDeConfianceService appareilDeConfianceService;
    private final ICodeDeSecoursService codeDeSecoursService;
    private final IAlerteDeSecuriteService alerteDeSecuriteService;

    /**
     * Lecture du compte, necessaire parce que les services du second facteur
     * travaillent sur l'entite.
     *
     * <p>Ils la prennent en parametre et non un identifiant : la regle du second
     * facteur a besoin de {@code totpEnabled} et {@code totpSecret}, et les
     * faire relire par chaque appelant multiplierait les lectures. Le prix est
     * cette dependance ici.
     */
    private final UserRepository userRepository;

    // ==================================================================
    // Mot de passe
    // ==================================================================

    @PostMapping(AppConstants.CHANGE_PASSWORD_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> changerMotDePasse(
            @Valid @RequestBody ChangePasswordRequest request,
            ServerWebExchange exchange) {
        log.info("Demande de changement de mot de passe");

        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> authService.changerMotDePasse(
                        userId, request,
                        ContexteDeSecurite.de(
                                ReactiveRequestUtil.getClientIp(exchange),
                                ReactiveRequestUtil.getUserAgent(exchange))))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.MOT_DE_PASSE_CHANGE)));
    }

    // ==================================================================
    // Second facteur, en libre-service
    // ==================================================================

    /**
     * Etape 1 : rend le secret et le QR Code, <b>sans</b> activer.
     *
     * <p>La reponse le dit explicitement. Sans cette precision, la personne
     * croit avoir termine, ne confirme jamais, et se croit protegee alors que sa
     * connexion continue de passer sans code.
     */
    @PostMapping(AppConstants.TOTP_ENROLL_ENDPOINT)
    public Mono<ResponseEntity<TotpEnrolementResponse>> demarrerLeSecondFacteur() {
        log.info("Demande d'enrolement du second facteur en libre-service");

        return compteDeLAppelant()
                .flatMap(secondFacteurService::demarrerEnrolement)
                .map(ResponseEntity::ok);
    }

    /** Etape 2 : active, apres preuve qu'un code valide est produisible. */
    @PostMapping(AppConstants.TOTP_CONFIRM_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> confirmerLeSecondFacteur(
            @Valid @RequestBody CodeRequest request) {
        return compteDeLAppelant()
                .flatMap(user -> secondFacteurService.confirmerEnrolement(
                        user, request.getCode()))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.TOTP_MESSAGE_CONFIRME)));
    }

    /**
     * Desactive son propre second facteur, code valide exige.
     *
     * <p>{@code DELETE} avec un corps : inhabituel mais correct, et preferable
     * aux deux solutions de rechange. Le code en parametre de requete
     * atterrirait dans les journaux d'acces ; un {@code POST /totp/disable}
     * decrirait mal une suppression.
     *
     * <p>Un code de secours est accepte a la place du code TOTP : c'est le
     * chemin de celui qui a perdu son appareil.
     */
    @DeleteMapping(AppConstants.TOTP_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> desactiverLeSecondFacteur(
            @Valid @RequestBody CodeRequest request,
            ServerWebExchange exchange) {
        log.info("Demande de desactivation du second facteur en libre-service");

        return compteDeLAppelant()
                .flatMap(user -> secondFacteurService.desactiver(
                        user, request.getCode(),
                        ReactiveRequestUtil.getClientIp(exchange)))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.TOTP_MESSAGE_DESACTIVE)));
    }

    // ==================================================================
    // Sessions
    // ==================================================================

    /**
     * Sessions ouvertes du compte.
     *
     * <p>L'en-tete {@code X-Refresh-Token} est optionnel et sert uniquement a
     * marquer la session de l'appelant : voir
     * {@link WebConstants#HEADER_REFRESH_TOKEN} pour la raison du choix de
     * l'en-tete.
     */
    @GetMapping(AppConstants.SESSIONS_ENDPOINT)
    public Flux<SessionResponse> sessions(
            @RequestHeader(value = WebConstants.HEADER_REFRESH_TOKEN, required = false)
            String jetonCourant) {
        return AppelantAuthentifie.identifiant()
                .flatMapMany(userId -> sessionService.sessions(userId, jetonCourant));
    }

    @DeleteMapping(AppConstants.SESSION_PAR_ID_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> fermerUneSession(
            @PathVariable String sessionId) {
        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> sessionService.fermer(userId, sessionId))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.SESSION_FERMEE)));
    }

    /**
     * Ferme les sessions du compte.
     *
     * @param conserverLaCourante par defaut {@code true}, ce qui correspond a
     *        « deconnecter mes autres appareils » — l'usage courant. La valeur
     *        {@code false} deconnecte aussi l'appelant, ce qui se demande apres
     *        un soupçon. Le defaut inverse surprendrait : on ne s'attend pas a
     *        etre deconnecte en fermant les sessions des autres
     */
    @DeleteMapping(AppConstants.SESSIONS_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> fermerLesSessions(
            @RequestParam(defaultValue = "true") boolean conserverLaCourante,
            @RequestHeader(value = WebConstants.HEADER_REFRESH_TOKEN, required = false)
            String jetonCourant) {
        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> sessionService.fermerToutes(
                        userId, conserverLaCourante, jetonCourant))
                .map(nombre -> ResponseEntity.ok(MessageResponse.reussie(
                        nombre + AppConstants.SESSIONS_FERMEES_SUFFIXE)));
    }

    // ==================================================================
    // Confirmation du contact apres inscription
    // ==================================================================

    @PostMapping(AppConstants.EMAIL_CONFIRM_REQUEST_ENDPOINT)
    public Mono<ResponseEntity<VerificationResponse>> demanderConfirmationEmail() {
        return AppelantAuthentifie.identifiant()
                .flatMap(confirmationDeContactService::demanderConfirmationEmail)
                .map(ResponseEntity::ok);
    }

    @PostMapping(AppConstants.EMAIL_CONFIRM_VERIFY_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> confirmerEmail(
            @Valid @RequestBody CodeRequest request) {
        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> confirmationDeContactService.confirmerEmail(
                        userId, request.getCode()))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.EMAIL_CONFIRME)));
    }

    @PostMapping(AppConstants.PHONE_CONFIRM_REQUEST_ENDPOINT)
    public Mono<ResponseEntity<VerificationResponse>> demanderConfirmationTelephone() {
        return AppelantAuthentifie.identifiant()
                .flatMap(confirmationDeContactService::demanderConfirmationTelephone)
                .map(ResponseEntity::ok);
    }

    @PostMapping(AppConstants.PHONE_CONFIRM_VERIFY_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> confirmerTelephone(
            @Valid @RequestBody CodeRequest request) {
        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> confirmationDeContactService.confirmerTelephone(
                        userId, request.getCode()))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.TELEPHONE_CONFIRME)));
    }

    // ==================================================================
    // Appareils de confiance
    // ==================================================================

    /**
     * Accorde la confiance a l'appareil courant, et rend le jeton UNE SEULE fois.
     *
     * <h2>Pourquoi un code TOTP est exige ICI</h2>
     *
     * Un appareil de confiance dispense du second facteur. L'accorder sans
     * l'avoir exige une fois donnerait a qui detient le mot de passe un moyen de
     * s'en dispenser definitivement : la protection s'annulerait elle-meme.
     *
     * <p>L'appelant est deja authentifie — mais une session peut avoir ete
     * ouverte depuis un autre appareil de confiance, donc sans code. Exiger le
     * code a cet instant garantit que la personne detient bien son second
     * facteur.
     */
    @PostMapping(AppConstants.APPAREILS_DE_CONFIANCE_ENDPOINT)
    public Mono<ResponseEntity<ConfianceAccordeeResponse>> accorderLaConfiance(
            @Valid @RequestBody ConfianceDemandeRequest requete,
            ServerWebExchange exchange) {
        log.info("Demande de confiance pour l'appareil courant");

        return compteDeLAppelant()
                // Le code est verifie AVANT d'accorder : voir la javadoc.
                .flatMap(user -> secondFacteurService
                        .confirmerPourUneOperation(user, requete.getCode(),
                                ReactiveRequestUtil.getClientIp(exchange))
                        .thenReturn(user))
                .flatMap(user -> appareilDeConfianceService.accorder(
                        user, requete.getNom(),
                        ReactiveRequestUtil.getUserAgent(exchange),
                        ReactiveRequestUtil.getClientIp(exchange)))
                .map(ResponseEntity::ok);
    }

    @GetMapping(AppConstants.APPAREILS_DE_CONFIANCE_ENDPOINT)
    public Flux<AppareilDeConfianceResponse> appareilsDeConfiance() {
        return AppelantAuthentifie.identifiant()
                .flatMapMany(appareilDeConfianceService::duCompte);
    }

    @DeleteMapping(AppConstants.APPAREIL_DE_CONFIANCE_PAR_ID_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> revoquerUnAppareil(
            @PathVariable String appareilId) {
        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> appareilDeConfianceService.revoquer(userId, appareilId))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.CONFIANCE_REVOQUEE)));
    }

    /**
     * Retire la confiance a TOUS les appareils.
     *
     * <p>Le second facteur sera demande partout a la prochaine connexion. C'est
     * l'operation a faire apres la perte d'un appareil, et elle n'exige pas de
     * code : exiger un code pour <b>renforcer</b> la securite serait un obstacle
     * au mauvais moment — la personne vient peut-etre de perdre l'appareil qui
     * le produit.
     */
    @DeleteMapping(AppConstants.APPAREILS_DE_CONFIANCE_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> revoquerTousLesAppareils() {
        log.info("Demande de revocation de tous les appareils de confiance");

        return AppelantAuthentifie.identifiant()
                .flatMap(appareilDeConfianceService::revoquerTous)
                .map(nombre -> ResponseEntity.ok(MessageResponse.reussie(
                        nombre + AppConstants.CONFIANCES_REVOQUEES_SUFFIXE)));
    }

    // ==================================================================
    // Codes de secours du second facteur
    // ==================================================================

    /**
     * Produit un lot de codes de secours et les rend UNE SEULE fois.
     *
     * <h2>Pourquoi une preuve est exigee ici</h2>
     *
     * Un code de secours contourne le second facteur. En remettre un lot a qui
     * detient seulement une session — peut-etre ouverte depuis un appareil de
     * confiance, donc sans jamais avoir produit de code — donnerait a un jeton
     * d'acces vole un moyen de se dispenser du second facteur pour de bon.
     *
     * <h2>Pourquoi un code de secours convient comme preuve</h2>
     *
     * {@code confirmerPourUneOperation} accepte un code TOTP ou un code de
     * secours. C'est ce qui rend ce chemin praticable apres la perte d'un
     * appareil : la personne entre avec un code de secours, et peut renouveler
     * son lot sans passer par une intervention humaine.
     *
     * <h2>Pourquoi le lot precedent meurt</h2>
     *
     * Deux lots valides a la fois doubleraient la surface sans que la personne
     * le sache : elle croirait avoir remplace ses codes alors que les anciens
     * fonctionneraient encore.
     */
    @PostMapping(AppConstants.CODES_DE_SECOURS_ENDPOINT)
    public Mono<ResponseEntity<LotDeCodesResponse>> regenererLesCodesDeSecours(
            @Valid @RequestBody CodeRequest request,
            ServerWebExchange exchange) {
        log.info("Demande de regeneration des codes de secours");

        return compteDeLAppelant()
                // La preuve est exigee AVANT de produire : voir la javadoc.
                .flatMap(user -> secondFacteurService
                        .confirmerPourUneOperation(user, request.getCode(),
                                ReactiveRequestUtil.getClientIp(exchange))
                        .thenReturn(user))
                .flatMap(codeDeSecoursService::regenerer)
                .map(ResponseEntity::ok);
    }

    /**
     * Etat de ses codes : combien restent, lesquels ont servi et d'ou.
     *
     * <p>Aucune preuve exigee, contrairement a la regeneration : lire son etat
     * ne contourne rien. Les codes eux-memes ne sont jamais rendus — seuls
     * leurs quatre premiers caracteres, pour que la personne les reconnaisse sur
     * son papier.
     *
     * <p>Un code consomme que la personne ne reconnait pas est le signal le plus
     * clair qu'elle puisse recevoir d'une intrusion. C'est la raison d'etre de
     * cet endpoint.
     */
    @GetMapping(AppConstants.CODES_DE_SECOURS_ENDPOINT)
    public Mono<ResponseEntity<EtatDesCodesResponse>> etatDesCodesDeSecours() {
        return AppelantAuthentifie.identifiant()
                .flatMap(codeDeSecoursService::etat)
                .map(ResponseEntity::ok);
    }

    // ==================================================================
    // Remplacement du numero de telephone
    // ==================================================================

    /**
     * Demande le remplacement du numero : le code part au NOUVEAU numero.
     *
     * <h2>Pourquoi le code ne part pas a l'ancien numero</h2>
     *
     * Seul un code reçu sur le nouveau numero prouve qu'on le detient. L'envoyer
     * a l'ancien ne prouverait rien du nouveau, et laisserait passer une faute
     * de frappe — qui enfermerait la personne hors de la recuperation par SMS,
     * precisement ce a quoi sert un numero.
     *
     * <p>Le compte garde son numero actuel tant que la confirmation n'a pas eu
     * lieu : l'operation s'annule par l'inaction.
     *
     * <h2>Pourquoi le mot de passe actuel est exige, et non un code TOTP</h2>
     *
     * Le numero est le canal de {@code /auth/forgot-password} : qui le remplace
     * prend le controle de la reinitialisation, donc du compte entier, sans
     * jamais connaitre le mot de passe. Une session seule ne peut pas suffire.
     *
     * <p>Le mot de passe plutot que le second facteur parce qu'il est universel :
     * exiger un code TOTP fermerait l'operation a la quasi-totalite des clients,
     * qui ne l'ont pas enrole.
     *
     * <p>Consequence assumee : un compte cree par OAuth2 n'a pas de mot de passe
     * et reçoit {@code VAL_6009}. Laisser passer sur la seule session y serait
     * pire que refuser.
     */
    @PostMapping(AppConstants.PHONE_CHANGE_REQUEST_ENDPOINT)
    public Mono<ResponseEntity<VerificationResponse>> demanderChangementDeNumero(
            @Valid @RequestBody ChangementDeNumeroRequest requete) {
        log.info("Demande de remplacement du numero de telephone");

        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> confirmationDeContactService
                        .demanderChangementDeTelephone(
                                userId, requete.getNouveauNumero(),
                                requete.getIndicatif(),
                                requete.getMotDePasseActuel()))
                .map(ResponseEntity::ok);
    }

    /**
     * Confirme le remplacement, puis previent l'ANCIEN numero.
     *
     * <p>L'alerte part au numero qui disparait, par SMS : c'est son titulaire qui
     * vient d'etre depossede, et c'est donc lui qu'il faut atteindre. Le
     * courriel sert de repli quand l'ancien numero n'etait pas verifie.
     */
    @PostMapping(AppConstants.PHONE_CHANGE_VERIFY_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> confirmerChangementDeNumero(
            @Valid @RequestBody CodeRequest requete,
            ServerWebExchange exchange) {
        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> confirmationDeContactService
                        .confirmerChangementDeTelephone(
                                userId, requete.getCode(),
                                ContexteDeSecurite.de(
                                        ReactiveRequestUtil.getClientIp(exchange),
                                        ReactiveRequestUtil.getUserAgent(exchange))))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.NUMERO_CHANGE)));
    }

    // ==================================================================
    // Alerte de nouvelle connexion
    // ==================================================================

    /**
     * Etat du reglage : suis-je prevenu a chaque connexion ?
     *
     * <p>Lu, et pas seulement ecrit. Un reglage qu'on ne peut pas relire oblige
     * l'application a garder sa propre idee de l'etat, qui divergera — et la
     * personne croira surveillee ce qui ne l'est plus.
     */
    @GetMapping(AppConstants.ALERTE_DE_CONNEXION_ENDPOINT)
    public Mono<ResponseEntity<AlerteDeConnexionResponse>> etatDeLalerteDeConnexion() {
        return compteDeLAppelant()
                .map(user -> ResponseEntity.ok(AlerteDeConnexionResponse.de(
                        Boolean.TRUE.equals(user.getAlerteNouvelleConnexion()),
                        Boolean.TRUE.equals(user.getIsEmailVerified()))));
    }

    /**
     * Active ou desactive l'alerte de nouvelle connexion.
     *
     * <h2>Pourquoi ce reglage existe, alors que les autres alertes n'en ont pas</h2>
     *
     * Une connexion est frequente et ordinaire. Alerter a chaque fois pour tout
     * le monde atteindrait un volume dont le resultat connu est que les gens
     * cessent de lire les alertes, puis les desactivent — une alerte qu'on
     * ignore protege moins qu'une alerte qu'on a choisie.
     *
     * <p>Les alertes de changement de mot de passe et d'adresse sont elles
     * <b>sans condition</b> : rares, toujours notables, et sur le chemin
     * obligatoire de tout detournement de compte durable.
     *
     * <h2>L'activation EXIGE une adresse verifiee</h2>
     *
     * Refus en {@code VAL_6008} sinon. Accepter silencieusement produirait un
     * reglage affiche comme actif qui n'enverrait jamais rien : la personne se
     * croirait prevenue de chaque connexion, et ne chercherait donc aucun autre
     * moyen de surveiller son compte. C'est la pire des deux reponses.
     *
     * <p>Aucun second facteur exige : ce reglage ne retire aucune protection, il
     * en ajoute une. Exiger un code pour <b>renforcer</b> sa securite serait un
     * obstacle au mauvais endroit — meme raison que pour
     * {@code DELETE /auth/trusted-devices}.
     */
    @PutMapping(AppConstants.ALERTE_DE_CONNEXION_ENDPOINT)
    public Mono<ResponseEntity<AlerteDeConnexionResponse>> changerLalerteDeConnexion(
            @Valid @RequestBody BasculeRequest requete) {
        log.info("Demande de changement de l'alerte de nouvelle connexion");

        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> alerteDeSecuriteService.changerLalerteDeConnexion(
                        userId, Boolean.TRUE.equals(requete.getActif())))
                // L'adresse est forcement verifiee quand l'activation aboutit :
                // le service refuse sinon.
                .map(actif -> ResponseEntity.ok(
                        AlerteDeConnexionResponse.de(actif, true)));
    }

    // ==================================================================
    // Comptes sociaux
    // ==================================================================

    @GetMapping(AppConstants.OAUTH2_ACCOUNTS_ENDPOINT)
    public Flux<CompteSocialResponse> comptesSociaux() {
        return AppelantAuthentifie.identifiant()
                .flatMapMany(comptesSociauxService::liesAuCompte);
    }

    /**
     * Delie un fournisseur.
     *
     * <p>Le fournisseur est resolu ici par {@code OAuthProvider.fromString},
     * qui leve sur une valeur inconnue. C'est la seule traduction faite dans ce
     * controleur, et elle reste du ressort de la liaison HTTP : transformer un
     * segment d'URL en type du domaine.
     */
    @DeleteMapping(AppConstants.OAUTH2_ACCOUNT_PAR_FOURNISSEUR_ENDPOINT)
    public Mono<ResponseEntity<MessageResponse>> delierCompteSocial(
            @PathVariable String provider) {
        log.info("Demande de deliaison du fournisseur {}", provider);

        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> comptesSociauxService.delier(
                        userId, OAuthProvider.fromString(provider)))
                .thenReturn(ResponseEntity.ok(MessageResponse.reussie(
                        AppConstants.COMPTE_SOCIAL_DELIE)));
    }

    /**
     * Fournisseurs utilisables sur cette installation.
     *
     * <p>Public et non authentifie : l'ecran de connexion en a besoin avant
     * toute session, et la liste des boutons affiches n'est pas un secret — elle
     * se deduit de l'ecran lui-meme.
     */
    @GetMapping(AppConstants.OAUTH2_PROVIDERS_ENDPOINT)
    public Flux<String> fournisseursDisponibles() {
        return comptesSociauxService.fournisseursDisponibles();
    }

    private Mono<com.abdatytch.auth_service.entities.User> compteDeLAppelant() {
        return AppelantAuthentifie.identifiant()
                .flatMap(userId -> userRepository.findById(userId)
                        .switchIfEmpty(Mono.error(
                                new com.abdatytch.auth_service.exception.AuthException(
                                        com.abdatytch.auth_service.constants.ErrorCode
                                                .USER_NOT_FOUND))));
    }
}
