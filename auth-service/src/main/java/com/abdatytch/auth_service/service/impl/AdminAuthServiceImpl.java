package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.request.AdminLoginRequest;
import com.abdatytch.auth_service.dto.request.LoginRequest;
import com.abdatytch.auth_service.dto.request.TotpResetRequest;
import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.dto.response.TotpEnrolementResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.IAccountLockoutService;
import com.abdatytch.auth_service.service.IAdminAuthService;
import com.abdatytch.auth_service.service.IAuthService;
import com.abdatytch.auth_service.service.IAuthenticationLogService;
import com.abdatytch.auth_service.service.IMotDePasseService;
import com.abdatytch.auth_service.service.ISecondFacteurService;
import com.abdatytch.auth_service.util.IdentifiantDeCompte;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Operations propres aux comptes d'administration.
 *
 * <h2>Ce que cette classe ne fait plus</h2>
 *
 * Elle portait sa propre connexion complete : recherche du compte, verrouillage,
 * mot de passe, second facteur, journal, emission du jeton. Chacun de ces
 * controles existait aussi dans {@code AuthServiceImpl}, en deux exemplaires
 * ecrits separement — et ils avaient commence a diverger :
 *
 * <ul>
 *   <li>{@code isActive} n'etait verifie que du cote SDK, donc un administrateur
 *       desactive se connectait encore par ce chemin ;</li>
 *   <li>le second facteur n'etait oppose que de ce cote, donc un administrateur
 *       enrole se connectait par {@code /auth/login} avec le mot de passe seul.</li>
 * </ul>
 *
 * Les deux defauts ont la meme forme : une regle presente d'un cote et absente
 * de l'autre, et l'attaquant choisit le cote. La connexion est donc desormais
 * <b>deleguee</b>, et cette classe ne garde que ce qui est specifique aux
 * administrateurs : la restriction de role et le second facteur.
 *
 * <h2>Aucune divulgation</h2>
 *
 * Compte inexistant, mot de passe faux, code faux, ou compte sans role
 * d'administration donnent <b>la meme erreur</b> sur la connexion. Les
 * operations sur le second facteur, elles, repondent de façon nommee : pour les
 * atteindre, l'appelant a deja prouve le mot de passe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuthServiceImpl implements IAdminAuthService {

    private final UserRepository userRepository;
    private final IMotDePasseService motDePasseService;
    private final IAuthService authService;
    private final ISecondFacteurService secondFacteurService;
    private final IAuthenticationLogService authenticationLogService;
    private final IAccountLockoutService accountLockoutService;

    @Override
    public Mono<AuthResponse> login(AdminLoginRequest request, String ipAddress, String userAgent) {
        log.info(AppConstants.LOG_INFO_ADMIN_LOGIN_ATTEMPT);

        return IdentifiantDeCompte.resoudre(userRepository, request.getIdentifier())
                .filter(this::estAdministrateur)
                // Delegation : tous les controles de connexion sont ceux de
                // /auth/login, sans exception et sans copie.
                .flatMap(user -> authService.login(
                        LoginRequest.builder()
                                .identifier(request.getIdentifier())
                                .password(request.getPassword())
                                .totpCode(request.getTotpCode())
                                .build(),
                        ipAddress, userAgent))
                // Un `filter` qui ne passe pas produit un Mono vide : compte
                // inconnu et compte non administrateur aboutissent donc ici,
                // indistinctement.
                .switchIfEmpty(Mono.defer(() ->
                        refuser(request.getIdentifier(), ipAddress, userAgent)));
    }

    @Override
    public Mono<TotpEnrolementResponse> enrollTotp(AdminLoginRequest request) {
        return compteAutorise(request.getIdentifier(), request.getPassword())
                .flatMap(secondFacteurService::enroler);
    }

    @Override
    public Mono<TotpEnrolementResponse> reinitialiserTotp(TotpResetRequest request) {
        return compteAutorise(request.getIdentifier(), request.getPassword())
                // Le code de l'appareil ACTUEL est exige avant de le remplacer.
                // Sans lui, un mot de passe seul suffirait a substituer son
                // propre appareil : le second facteur serait contournable par
                // le premier.
                // Adresse absente : cette operation n'a pas d'echange HTTP sous
                // la main. Elle n'est utile qu'a l'audit d'un code de secours
                // consomme, et ne conditionne aucune decision.
                .flatMap(user -> secondFacteurService.verifier(
                        user, request.getTotpCode(), null))
                .flatMap(secondFacteurService::reinitialiser);
    }

    @Override
    public Mono<Void> retirerSecondFacteur(String identifier, String acteurId) {
        return IdentifiantDeCompte.resoudre(userRepository, identifier)
                .filter(this::estAdministrateur)
                .flatMap(user -> secondFacteurService.retirer(user)
                        .then(authenticationLogService.logAuthenticationEvent(
                                user.getId(), user.getPhoneNumber(),
                                EventType.LOGIN, Status.SUCCESS,
                                null, null,
                                AppConstants.LOG_AUDIT_TOTP_RETIRE + acteurId)))
                .then();
    }

    /**
     * Compte d'administration dont le mot de passe vient d'etre verifie.
     *
     * <h2>Pourquoi le verrouillage s'applique ici aussi</h2>
     *
     * {@code /totp/enroll} refusait d'un cote {@code AUTH_1001} pour un mot de
     * passe faux, et de l'autre {@code 2FA_9002} quand le second facteur etait
     * deja actif. C'est un <b>oracle de mot de passe</b> : la reponse dit si le
     * mot de passe est bon, sans qu'aucun second facteur n'ait a etre presente,
     * et sans aucun comptage. Le compteur d'echecs et la verification de verrou
     * sont donc opposes ici comme sur la connexion.
     *
     * @return le compte, ou un {@code Mono} vide — le refus uniforme est pose
     *         par l'appelant
     */
    private Mono<User> compteAutorise(String identifier, String password) {
        return IdentifiantDeCompte.resoudre(userRepository, identifier)
                .filter(this::estAdministrateur)
                .flatMap(accountLockoutService::assurerNonVerrouille)
                .flatMap(user -> motDePasseService.correspond(password, user.getPasswordHash())
                        .flatMap(concorde -> {
                            if (Boolean.TRUE.equals(concorde)) {
                                return Mono.just(user);
                            }
                            // Le `filter` d'origine rendait un Mono vide et
                            // perdait l'entite : impossible d'incrementer quoi
                            // que ce soit.
                            return accountLockoutService.enregistrerEchec(user)
                                    .then(Mono.<User>empty());
                        }))
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.INVALID_CREDENTIALS)));
    }

    /**
     * Seuls les comptes portant un role d'administration peuvent emprunter ces
     * chemins.
     *
     * <p>Sans ce controle, {@code /auth/admin/**} constituerait un second point
     * d'entree pour les utilisateurs finaux, avec des reponses nommees la ou la
     * connexion du SDK est uniforme.
     */
    private boolean estAdministrateur(User user) {
        return user.getRole() != null && user.getRole().estRoleDAdministration();
    }

    /**
     * Refus uniforme, journalise.
     *
     * <p>Le journal conserve la trace de la tentative — c'est le signal qui
     * permet de reperer une attaque sur un compte d'administration — mais la
     * reponse ne dit pas laquelle des conditions a echoue.
     */
    private <T> Mono<T> refuser(String identifiant, String ipAddress, String userAgent) {
        log.warn(AppConstants.LOG_WARN_ADMIN_LOGIN_REFUSEE);
        return authenticationLogService.logAuthenticationEvent(
                        null, identifiant, EventType.LOGIN, Status.FAILED,
                        ipAddress, userAgent, AppConstants.LOG_AUDIT_ADMIN_LOGIN_REFUSEE)
                .then(Mono.error(new AuthException(ErrorCode.INVALID_CREDENTIALS)));
    }
}
