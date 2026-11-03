package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.dto.response.EtatVerrouillageResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.util.IdentifiantDeCompte;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.IAccountLockoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Verrouillage de compte, sur <b>un seul</b> magasin : la base.
 *
 * <h2>Le défaut que cette réécriture corrige</h2>
 *
 * Deux mécanismes coexistaient, chacun à moitié câblé :
 *
 * <ul>
 *   <li>cette classe écrivait dans <b>Redis</b>, et n'était appelée que par
 *       {@code AdminAccountController}. {@code recordFailedAttempt} ne l'était
 *       par aucun code ;</li>
 *   <li>{@code AuthServiceImpl} comptait les échecs et verrouillait dans les
 *       <b>colonnes de la base</b> — et c'est cette décision-là que la connexion
 *       lisait.</li>
 * </ul>
 *
 * Les deux ne se rencontraient jamais. Mesuré sur l'environnement de
 * développement :
 *
 * <pre>
 * POST /admin/accounts/{id}/lock            -> 200, clé Redis posée
 * GET  /admin/accounts/{id}/lockout-status  -> isLocked: true
 * POST /auth/admin/login                    -> 200 + jeton valide
 * </pre>
 *
 * Un administrateur verrouillait un compte, l'interface le confirmait
 * verrouillé, et le compte se connectait quand même. Une protection qui rend
 * compte d'un succès sans rien faire — le défaut le plus fréquent de ce dépôt.
 *
 * <h2>Pourquoi la base, et non Redis</h2>
 *
 * La base est le magasin que la connexion <b>lit déjà</b>. Garder Redis aurait
 * demandé d'ajouter une seconde consultation sur le chemin de connexion, donc
 * deux sources de vérité pour une même décision — et c'est précisément d'avoir
 * eu deux magasins que vient le défaut.
 *
 * <p>Le verrou porte sa propre échéance ({@code locked_until}) et s'efface de
 * lui-même : il n'y a donc rien à purger, et aucun risque d'enfermer
 * définitivement un administrateur.
 *
 * <h2>Un seul seuil</h2>
 *
 * Le seuil et la durée viennent de {@code auth.lockout.*}. Ils étaient
 * auparavant déclarés ici <b>et</b> codés en dur dans {@code AppConstants},
 * avec deux valeurs à maintenir d'accord pour une même règle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountLockoutServiceImpl implements IAccountLockoutService {

    /** Motifs de verrouillage. Valeurs BORNEES : ce sont des etiquettes Prometheus. */
    static final String MOTIF_SEUIL_ATTEINT = "seuil_tentatives_atteint";
    static final String MOTIF_SUSPENSION_ADMIN = "suspension_administrative";
    static final String SURFACE_COMPTE_VERROUILLE = "compte_verrouille";

    private final UserRepository userRepository;
    private final com.abdatytch.auth_service.metrics.AuthMetrics authMetrics;

    /**
     * Révocation des sessions, sans laquelle un verrouillage ne verrouille rien.
     *
     * <p>Verrouiller empêchait de se connecter, et <b>seulement</b> cela. Les
     * sessions déjà ouvertes continuaient de vivre : le refresh token restait
     * valable et renouvelait l'accès indéfiniment. L'administrateur voyait
     * « compte verrouillé » pendant que la personne gardait son accès — le
     * verrou portait sur la porte d'entrée, pas sur qui était déjà entré.
     *
     * <p>Aucun cycle de dépendances : {@code RefreshTokenServiceImpl} ne dépend
     * que de son dépôt.
     */
    private final com.abdatytch.auth_service.service.IRefreshTokenService refreshTokenService;

    @Value("${auth.lockout.max-attempts}")
    private int maxAttempts;

    @Value("${auth.lockout.duration-minutes}")
    private int lockoutDurationMinutes;

    @Override
    public Mono<Boolean> recordFailedAttempt(String identifier) {
        return parIdentifiant(identifier)
                .flatMap(this::enregistrerEchec)
                // Un identifiant inconnu ne doit pas lever : la connexion refuse
                // de facon identique qu'un compte existe ou non, et cette
                // methode ne doit pas devenir un revelateur d'existence.
                .defaultIfEmpty(Boolean.FALSE);
    }

    @Override
    public Mono<Boolean> enregistrerEchec(User user) {
        user.setFailedLoginAttempts(nombreDEchecs(user) + 1);

        boolean atteintLeSeuil = user.getFailedLoginAttempts() >= maxAttempts;
        if (atteintLeSeuil) {
            appliquerLeVerrou(user, lockoutDurationMinutes, MOTIF_SEUIL_ATTEINT);
            log.warn("Compte verrouillé après {} tentatives échouées : {}",
                    user.getFailedLoginAttempts(), user.getId());
        } else {
            log.info("Tentative échouée {}/{} pour : {}",
                    user.getFailedLoginAttempts(), maxAttempts, user.getId());
        }

        if (!atteintLeSeuil) {
            return userRepository.save(user).thenReturn(false);
        }
        // Le verrouillage automatique ferme les sessions comme le verrouillage
        // administratif. Sans cela, qui tient déjà une session la conserve en
        // déclenchant lui-même le verrou — un attaquant qui a réussi une
        // connexion puis épuise le compteur garderait son accès, et le verrou
        // ne gênerait que le propriétaire légitime.
        return userRepository.save(user)
                .flatMap(this::fermerLesSessions)
                .thenReturn(true);
    }

    @Override
    public Mono<Boolean> isAccountLocked(String identifier) {
        return parIdentifiant(identifier)
                .map(this::verrouEnCours)
                .defaultIfEmpty(Boolean.FALSE);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Le déverrouillage automatique à échéance est <b>persisté</b>, et non
     * seulement calculé : sans cela, chaque lecture recalculerait l'expiration
     * et les colonnes resteraient indéfiniment à « verrouillé », ce qui rend
     * tout diagnostic en base trompeur.
     */
    @Override
    public Mono<User> assurerNonVerrouille(User user) {
        if (!verrouEnCours(user)) {
            if (Boolean.TRUE.equals(user.getIsLocked())) {
                // Echeance passee : on efface, et on le garde en base.
                return libererLeVerrou(user);
            }
            return Mono.just(user);
        }
        // Point de convergence de TOUT refus pour cause de verrou : les deux
        // chemins de connexion, l'enrolement et la reinitialisation du second
        // facteur y deleguent tous.
        //
        // Compte separement du verrouillage lui-meme : les deux courbes
        // ensemble distinguent une attaque en cours (des verrouillages qui
        // montent) d'un utilisateur legitime enferme (des refus qui montent
        // sans nouveau verrouillage). Une seule des deux ne permet pas de
        // trancher.
        authMetrics.recordTentativeBloquee(SURFACE_COMPTE_VERROUILLE);
        return Mono.error(new com.abdatytch.auth_service.exception.AuthException(
                com.abdatytch.auth_service.constants.ErrorCode.ACCOUNT_LOCKED,
                minutesRestantes(user)));
    }

    @Override
    public Mono<Void> resetFailedAttempts(String identifier) {
        return parIdentifiant(identifier)
                .flatMap(user -> {
                    user.setFailedLoginAttempts(0);
                    log.info("Compteur de tentatives réinitialisé pour : {}", user.getId());
                    return userRepository.save(user);
                })
                .then();
    }

    @Override
    public Mono<Void> unlockAccount(String identifier) {
        return parIdentifiant(identifier)
                .flatMap(user -> {
                    log.info("Compte déverrouillé manuellement : {}", user.getId());
                    return libererLeVerrou(user);
                })
                .then();
    }

    @Override
    public Mono<Void> lockAccount(String identifier, int durationMinutes) {
        // Une duree nulle ou negative poserait une echeance DEJA passee, donc un
        // verrou qui ne verrouille rien — et l'appel rendrait pourtant un
        // succes. On se replie sur la duree de configuration.
        int duree = durationMinutes > 0 ? durationMinutes : lockoutDurationMinutes;

        return parIdentifiant(identifier)
                .flatMap(user -> {
                    appliquerLeVerrou(user, duree, MOTIF_SUSPENSION_ADMIN);
                    log.warn("Compte verrouillé pour {} minutes : {}", duree, user.getId());
                    return userRepository.save(user)
                            // Les sessions ouvertes sont fermées, sinon le
                            // verrou ne porte que sur les connexions futures.
                            .flatMap(this::fermerLesSessions);
                })
                .then();
    }

    /**
     * Ferme les sessions ouvertes du compte verrouillé.
     *
     * <p>Le jeton d'accès déjà émis reste valable jusqu'à son échéance — c'est
     * la nature d'un JWT, et le raccourcir demanderait une consultation en base
     * à chaque requête, ce que l'architecture de cette plateforme refuse. Ce qui
     * est coupé ici, c'est le <b>renouvellement</b> : l'accès s'éteint à
     * l'échéance du jeton courant au lieu de se prolonger indéfiniment.
     */
    private Mono<User> fermerLesSessions(User user) {
        return refreshTokenService.revokeAllUserTokens(user)
                .thenReturn(user);
    }

    @Override
    public Mono<Integer> getRemainingAttempts(String identifier) {
        return parIdentifiant(identifier)
                .map(user -> Math.max(0, maxAttempts - nombreDEchecs(user)))
                .defaultIfEmpty(maxAttempts);
    }

    /**
     * Retrouve le compte par téléphone <b>ou</b> par courriel.
     *
     * <p>Les endpoints d'administration acceptent les deux formes, et le
     * numéro est la plus courante. Chercher par un seul champ ferait répondre
     * « aucune tentative, non verrouillé » sur un identifiant parfaitement
     * valide — un succès trompeur, pas une erreur.
     */
    private Mono<User> parIdentifiant(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Mono.empty();
        }
        // Delegue : QUATRIEME copie de la regle de resolution dans le depot, et
        // la seule a interroger le telephone puis l'adresse en repli — donc
        // deux requetes pour tout identifiant qui est une adresse. Le detenteur
        // unique tranche sur l'arobase, donc une seule requete.
        return IdentifiantDeCompte.resoudre(userRepository, identifier);
    }

    @Override
    public Mono<EtatVerrouillageResponse> etatDuCompte(String identifier) {
        return parIdentifiant(identifier)
                .map(user -> EtatVerrouillageResponse.de(
                        identifier, user, verrouEnCours(user),
                        Math.max(0, maxAttempts - nombreDEchecs(user))))
                // Identifiant inconnu : non verrouille, seuil complet. Une
                // erreur ou un 404 revelerait l'inexistence du compte.
                .defaultIfEmpty(EtatVerrouillageResponse.de(
                        identifier, null, false, maxAttempts));
    }

    /** Le verrou court-il encore ? Faux si absent ou échu. */
    private boolean verrouEnCours(User user) {
        if (!Boolean.TRUE.equals(user.getIsLocked()) || user.getLockedUntil() == null) {
            return false;
        }
        return LocalDateTime.now().isBefore(user.getLockedUntil());
    }

    private long minutesRestantes(User user) {
        return java.time.Duration.between(LocalDateTime.now(), user.getLockedUntil()).toMinutes();
    }

    /**
     * Point de convergence de <b>tout</b> verrouillage : le seuil atteint comme
     * la suspension manuelle. C'est donc ici que la metrique est posee.
     *
     * <p>Elle etait dans {@code AuthServiceImpl}, sur le seul chemin de
     * connexion du SDK. Un verrouillage declenche par la connexion
     * administrateur, par l'enrolement du second facteur, par un code TOTP
     * errone ou par une suspension manuelle n'etait compte par personne : le
     * tableau de bord affichait zero pendant qu'un compte se verrouillait.
     */
    private void appliquerLeVerrou(User user, int dureeEnMinutes, String motif) {
        user.setIsLocked(true);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(dureeEnMinutes));
        authMetrics.recordAccountLockout(motif);
    }

    private Mono<User> libererLeVerrou(User user) {
        user.setIsLocked(false);
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        return userRepository.save(user);
    }

    /** Le compteur peut être nul en base sur une ligne ancienne. */
    private int nombreDEchecs(User user) {
        return user.getFailedLoginAttempts() == null ? 0 : user.getFailedLoginAttempts();
    }
}
