package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.VerificationResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.mappers.VerificationResponseMapper;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.dto.ContexteDeSecurite;
import com.abdatytch.auth_service.service.IAccountLockoutService;
import com.abdatytch.auth_service.service.IAlerteDeSecuriteService;
import com.abdatytch.auth_service.service.IConfirmationDeContactService;
import com.abdatytch.auth_service.service.IMotDePasseService;
import com.abdatytch.commons.notification.IMessagerieSortante;
import com.abdatytch.auth_service.service.IVerificationCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Confirmation du numero et de l'adresse : voir
 * {@link IConfirmationDeContactService} pour le pourquoi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfirmationDeContactServiceImpl implements IConfirmationDeContactService {

    private final UserRepository userRepository;
    private final IVerificationCodeService verificationCodeService;
    private final VerificationResponseMapper verificationResponseMapper;
    private final IMessagerieSortante messagerie;

    /**
     * Mot de passe actuel, exige pour remplacer le numero.
     *
     * <p>Voir {@link IConfirmationDeContactService#demanderChangementDeTelephone} :
     * le numero est le canal de la reinitialisation, donc une session seule ne
     * peut pas suffire a le remplacer.
     */
    private final IMotDePasseService motDePasseService;

    /** L'echec de mot de passe est compte, comme un echec de connexion. */
    private final IAccountLockoutService accountLockoutService;

    /** L'ANCIEN numero est prevenu du remplacement. */
    private final IAlerteDeSecuriteService alerteDeSecuriteService;

    @Override
    public Mono<VerificationResponse> demanderConfirmationEmail(String userId) {
        return compte(userId)
                .flatMap(user -> {
                    if (!estRenseigne(user.getEmail())) {
                        // Rien a confirmer. Envoyer un code vers une adresse
                        // absente echouerait plus loin, avec un message qui
                        // n'indique pas qu'il faut d'abord renseigner l'adresse.
                        return Mono.error(new AuthException(ErrorCode.VALIDATION_ERROR));
                    }
                    if (Boolean.TRUE.equals(user.getIsEmailVerified())) {
                        return Mono.error(new AuthException(ErrorCode.EMAIL_ALREADY_VERIFIED));
                    }
                    // Le code part vers l'adresse qu'il s'agit de prouver.
                    // L'envoyer ailleurs ne prouverait rien.
                    return verificationCodeService.generateAndSendCode(
                            user.getPhoneNumber(), user.getEmail(),
                            VerificationCodeType.EMAIL_CONFIRMATION);
                })
                // Le message annoncait l'envoi sans condition. L'amont ne peut
                // pas etre vide : `compte(userId)` leve sur un compte inconnu,
                // et les deux gardes ci-dessus levent aussi — donc `map` ne fait
                // disparaitre aucune reponse.
                .map(envoi -> verificationResponseMapper.toSuccessResponse(
                        IVerificationCodeService.EnvoiDuCode.message(
                                envoi.courrielAccepte(),
                                AppConstants.CONFIRMATION_EMAIL_ENVOYEE),
                        AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));
    }

    @Override
    @Transactional
    public Mono<Void> confirmerEmail(String userId, String code) {
        return compte(userId)
                .flatMap(user -> {
                    if (Boolean.TRUE.equals(user.getIsEmailVerified())) {
                        return Mono.error(new AuthException(ErrorCode.EMAIL_ALREADY_VERIFIED));
                    }
                    // validateCodeByEmail et non validateCode : la cle de
                    // recherche doit etre l'adresse. Chercher par le numero
                    // ferait echouer la confirmation des comptes inscrits par
                    // adresse seule, dont le numero est une valeur temp_.
                    return verificationCodeService.validateCodeByEmail(
                                    user.getEmail(), code,
                                    VerificationCodeType.EMAIL_CONFIRMATION)
                            .then(Mono.defer(() -> {
                                user.setIsEmailVerified(true);
                                return userRepository.save(user);
                            }));
                })
                .doOnSuccess(enregistre -> log.info(
                        "Adresse confirmee pour {}", userId))
                .then();
    }

    @Override
    public Mono<VerificationResponse> demanderConfirmationTelephone(String userId) {
        return compte(userId)
                .flatMap(user -> {
                    if (!estUnNumeroJoignable(user.getPhoneNumber())) {
                        // Numero de substitution d'une inscription par adresse
                        // seule : ce n'est pas un numero. Tenter l'envoi
                        // echouerait chez le fournisseur SMS, et la personne
                        // lirait une panne la ou il s'agit de renseigner un
                        // numero d'abord.
                        log.info("Confirmation du telephone demandee sans numero reel : {}",
                                userId);
                        return Mono.error(new AuthException(ErrorCode.VALIDATION_ERROR));
                    }
                    if (Boolean.TRUE.equals(user.getIsPhoneVerified())) {
                        return Mono.error(new AuthException(ErrorCode.PHONE_ALREADY_VERIFIED));
                    }
                    return verificationCodeService.generateAndSendCode(
                            user.getPhoneNumber(), user.getEmail(),
                            VerificationCodeType.PHONE_CONFIRMATION);
                })
                .map(envoi -> verificationResponseMapper.toSuccessResponse(
                        IVerificationCodeService.EnvoiDuCode.message(
                                envoi.smsAccepte(),
                                AppConstants.CONFIRMATION_TELEPHONE_ENVOYEE),
                        AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));
    }

    @Override
    @Transactional
    public Mono<Void> confirmerTelephone(String userId, String code) {
        return compte(userId)
                .flatMap(user -> {
                    if (Boolean.TRUE.equals(user.getIsPhoneVerified())) {
                        return Mono.error(new AuthException(ErrorCode.PHONE_ALREADY_VERIFIED));
                    }
                    return verificationCodeService.validateCode(
                                    user.getPhoneNumber(), code,
                                    VerificationCodeType.PHONE_CONFIRMATION)
                            .then(Mono.defer(() -> {
                                user.setIsPhoneVerified(true);
                                return userRepository.save(user);
                            }));
                })
                .doOnSuccess(enregistre -> log.info("Numero confirme pour {}", userId))
                .then();
    }

    // ==================================================================
    // Remplacement du numero : deux etapes, et le code part au NOUVEAU
    // ==================================================================

    @Override
    @Transactional
    public Mono<VerificationResponse> demanderChangementDeTelephone(
            String userId, String nouveauNumero, String indicatif,
            String motDePasseActuel) {
        log.info(AppConstants.LOG_INFO_CHANGEMENT_NUMERO_DEMANDE, userId);

        return compte(userId)
                // Le verrouillage s'applique : un compte verrouille ne doit pas
                // pouvoir remplacer son numero pour s'echapper par la
                // reinitialisation.
                .flatMap(accountLockoutService::assurerNonVerrouille)
                .flatMap(user -> verifierLeMotDePasse(user, motDePasseActuel))
                .flatMap(user -> verifierLeNumeroDemande(user, nouveauNumero))
                .flatMap(user -> {
                    // Le numero est POSE EN ATTENTE, et le compte garde
                    // l'actuel. C'est ce qui rend l'operation annulable par
                    // l'inaction : une faute de frappe n'enferme personne hors
                    // de la recuperation par SMS.
                    user.setNumeroEnAttente(nouveauNumero);
                    user.setIndicatifEnAttente(indicatif);
                    user.setNumeroEnAttenteJusquA(LocalDateTime.now()
                            .plusMinutes(AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));
                    return userRepository.save(user);
                })
                // Le code part au NOUVEAU numero : lui seul prouve qu'on le
                // detient. L'envoyer a l'ancien ne prouverait rien du nouveau,
                // et laisserait passer une faute de frappe.
                .flatMap(enregistre -> verificationCodeService.generateAndSendCode(
                        nouveauNumero, null, VerificationCodeType.PHONE_CHANGE))
                // Celui-ci compte double : le numero EN ATTENTE est deja
                // enregistre quand le code part. Annoncer un envoi qui n'a pas
                // eu lieu laisserait la personne attendre un SMS, sur un
                // changement de numero qu'elle croirait engage.
                .map(envoi -> verificationResponseMapper.toSuccessResponse(
                        IVerificationCodeService.EnvoiDuCode.message(
                                envoi.smsAccepte(),
                                AppConstants.CHANGEMENT_NUMERO_CODE_ENVOYE),
                        AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));
    }

    @Override
    @Transactional
    public Mono<Void> confirmerChangementDeTelephone(
            String userId, String code, ContexteDeSecurite contexte) {
        return compte(userId)
                .flatMap(user -> {
                    if (!user.aUneDemandeDeNumeroEnCours(LocalDateTime.now())) {
                        // Aucune demande, ou demande expiree. Le meme refus pour
                        // les deux : distinguer « expiree » de « inexistante »
                        // apprendrait a qui detient un jeton vole qu'une demande
                        // a eu lieu, et quel numero viser.
                        log.warn(AppConstants.LOG_WARN_CHANGEMENT_NUMERO_SANS_DEMANDE,
                                userId);
                        return Mono.error(new AuthException(ErrorCode.VALIDATION_ERROR));
                    }
                    String nouveauNumero = user.getNumeroEnAttente();

                    return verificationCodeService.validateCode(
                                    nouveauNumero, code, VerificationCodeType.PHONE_CHANGE)
                            // L'unicite est verifiee une SECONDE fois.
                            //
                            // Un autre compte a pu revendiquer ce numero entre
                            // la demande et la confirmation : la contrainte ne
                            // se verifie utilement qu'a l'instant de l'ecriture.
                            .then(userRepository.existsByPhoneNumber(nouveauNumero))
                            .flatMap(pris -> Boolean.TRUE.equals(pris)
                                    ? Mono.<User>error(new AuthException(
                                            ErrorCode.PHONE_ALREADY_EXISTS))
                                    : Mono.just(user))
                            .flatMap(valide -> remplacerLeNumero(valide, contexte));
                })
                .then();
    }

    /**
     * Pose le nouveau numero, puis previent l'ANCIEN.
     *
     * <p>L'ancien numero et son etat de verification sont lus <b>avant</b>
     * l'ecriture. Les relire apres rendrait le nouveau, et l'alerte partirait a
     * celui qui vient de le choisir : l'inversion exacte qui rend cette alerte
     * inutile, et elle s'ecrit sans y penser.
     */
    private Mono<User> remplacerLeNumero(User user, ContexteDeSecurite contexte) {
        String ancienNumero = user.getPhoneNumber();
        boolean ancienEtaitVerifie = Boolean.TRUE.equals(user.getIsPhoneVerified());

        user.setPhoneNumber(user.getNumeroEnAttente());
        user.setCountryCode(user.getIndicatifEnAttente());
        // VERIFIE : la personne vient de prouver qu'elle detient ce numero en
        // recevant le code dessus. Le laisser a faux obligerait a une
        // confirmation supplementaire sans rien prouver de plus.
        user.setIsPhoneVerified(true);
        // La demande est consommee : sans cela, le meme code pourrait resservir
        // tant que l'echeance tient.
        user.setNumeroEnAttente(null);
        user.setIndicatifEnAttente(null);
        user.setNumeroEnAttenteJusquA(null);

        return userRepository.save(user)
                .doOnSuccess(enregistre -> log.info(
                        AppConstants.LOG_INFO_NUMERO_CHANGE, enregistre.getId()))
                .flatMap(enregistre -> alerteDeSecuriteService.numeroModifie(
                                enregistre, ancienNumero, ancienEtaitVerifie, contexte)
                        .thenReturn(enregistre));
    }

    private Mono<User> verifierLeMotDePasse(User user, String motDePasseActuel) {
        if (user.getPasswordHash() == null) {
            // Compte cree par OAuth2 : aucun mot de passe a opposer.
            //
            // Laisser passer sur la seule session serait pire que refuser : le
            // numero est le canal de la reinitialisation, donc un jeton d'acces
            // vole suffirait a prendre le controle definitif du compte.
            log.warn(AppConstants.LOG_WARN_CHANGEMENT_NUMERO_SANS_MOT_DE_PASSE,
                    user.getId());
            return Mono.error(new AuthException(ErrorCode.MOT_DE_PASSE_ABSENT_DU_COMPTE));
        }
        return motDePasseService.correspond(motDePasseActuel, user.getPasswordHash())
                .flatMap(concorde -> {
                    if (Boolean.TRUE.equals(concorde)) {
                        return Mono.just(user);
                    }
                    // L'echec est COMPTE, comme un echec de connexion. Sans
                    // cela, qui tient un jeton vole eprouve les mots de passe
                    // sans frein, et ce point d'entree devient un oracle.
                    return accountLockoutService.enregistrerEchec(user)
                            .then(Mono.error(new AuthException(
                                    ErrorCode.INVALID_CREDENTIALS)));
                });
    }

    private Mono<User> verifierLeNumeroDemande(User user, String nouveauNumero) {
        if (nouveauNumero.equals(user.getPhoneNumber())) {
            // Repondre « code envoye » enverrait un SMS payant pour rien, et
            // laisserait croire a une operation en cours.
            return Mono.error(new AuthException(ErrorCode.VALIDATION_ERROR));
        }
        return userRepository.existsByPhoneNumber(nouveauNumero)
                .flatMap(pris -> Boolean.TRUE.equals(pris)
                        ? Mono.error(new AuthException(ErrorCode.PHONE_ALREADY_EXISTS))
                        : Mono.just(user));
    }

    private Mono<User> compte(String userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)));
    }

    /*
     * `messageDEnvoi()` a disparu.
     *
     * Son intention etait juste — « le message dit si le SMS est reellement
     * parti » — mais sa mesure etait fausse : `envoiSmsActif()` est un
     * interrupteur de configuration, et ne connait qu'une des quatre raisons de
     * ne rien envoyer. La reponse vient desormais d'EnvoiDuCode, qui porte le
     * resultat reel.
     */

    private static boolean estUnNumeroJoignable(String numero) {
        return estRenseigne(numero) && !numero.startsWith(AppConstants.TEMP_PHONE_PREFIX);
    }

    private static boolean estRenseigne(String valeur) {
        return valeur != null && !valeur.isBlank();
    }
}
