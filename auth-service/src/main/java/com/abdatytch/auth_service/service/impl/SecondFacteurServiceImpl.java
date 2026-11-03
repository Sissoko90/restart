package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.TotpEnrolementResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.IAccountLockoutService;
import com.abdatytch.auth_service.service.ICodeDeSecoursService;
import com.abdatytch.auth_service.service.ISecondFacteurService;
import com.abdatytch.commons.totp.TotpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Regle du second facteur, en un seul exemplaire.
 *
 * <p>Voir {@link ISecondFacteurService} pour le pourquoi de ce service et
 * l'enonce de la regle.
 */
@Service
@Slf4j
public class SecondFacteurServiceImpl implements ISecondFacteurService {

    /** Resultats du second facteur. Valeurs BORNEES : etiquettes Prometheus. */
    static final String RESULTAT_VALIDE = "valide";
    static final String RESULTAT_CODE_ABSENT = "code_absent";
    static final String RESULTAT_CODE_FAUX = "code_faux";
    static final String RESULTAT_NON_ENROLE = "non_enrole";
    /**
     * Le second facteur a ete satisfait par un code de secours.
     *
     * <p>Compte a part de {@link #RESULTAT_VALIDE} : cette courbe qui monte
     * signale des personnes qui perdent leur appareil — ou un attaquant qui
     * brule les codes d'un compte dont il detient le mot de passe. Melangee aux
     * connexions ordinaires, elle serait invisible.
     */
    static final String RESULTAT_CODE_DE_SECOURS = "code_de_secours";

    static final String OPERATION_ENROLEMENT = "enrolement";
    static final String OPERATION_REINITIALISATION = "reinitialisation";
    static final String OPERATION_RETRAIT = "retrait";

    /** Libre-service : etape 1, le secret est pose mais rien n'est actif. */
    static final String OPERATION_ENROLEMENT_DEMARRE = "enrolement_demarre";
    /** Libre-service : etape 2, le second facteur devient actif. */
    static final String OPERATION_ENROLEMENT_CONFIRME = "enrolement_confirme";
    /**
     * Libre-service : la personne retire son propre second facteur.
     *
     * <p>Comptee separement de {@link #OPERATION_RETRAIT}, qui est le retrait
     * d'AUTRUI par un super administrateur. Les deux courbes melangees
     * rendraient invisible ce qui compte : un retrait administratif est une
     * operation rare et sensible, une desactivation en libre-service est un
     * choix ordinaire. Confondues, une alerte au premier retrait se
     * declencherait sur chaque client qui change d'avis.
     */
    static final String OPERATION_DESACTIVATION_LIBRE_SERVICE = "desactivation_libre_service";

    private final UserRepository userRepository;
    private final TotpService totpService;
    private final IAccountLockoutService accountLockoutService;
    private final ICodeDeSecoursService codeDeSecoursService;
    private final AuthMetrics authMetrics;
    private final String emetteur;

    public SecondFacteurServiceImpl(
            UserRepository userRepository,
            TotpService totpService,
            IAccountLockoutService accountLockoutService,
            ICodeDeSecoursService codeDeSecoursService,
            AuthMetrics authMetrics,
            @Value("${app.totp.issuer:" + AppConstants.TOTP_EMETTEUR_DEFAUT + "}")
            String emetteur) {
        this.userRepository = userRepository;
        this.totpService = totpService;
        this.accountLockoutService = accountLockoutService;
        this.codeDeSecoursService = codeDeSecoursService;
        this.authMetrics = authMetrics;
        this.emetteur = emetteur;
    }

    @Override
    public Mono<User> verifier(User user, String code, String ipAddress) {
        // Les cinq issues sont comptees separement, et c'est ce qui rend la
        // courbe lisible : « non_enrole » qui monte signale des administrateurs
        // qui n'ont jamais enrole leur second facteur ; « code_faux » qui monte
        // signale une attaque sur le second facteur par qui detient deja le mot
        // de passe ; « code_de_secours » qui monte signale des appareils perdus.
        // Un compteur unique melangerait les trois.
        if (!Boolean.TRUE.equals(user.getTotpEnabled())) {
            log.warn(AppConstants.LOG_WARN_SECOND_FACTEUR_ABSENT, user.getId());
            authMetrics.recordSecondFacteur(RESULTAT_NON_ENROLE);
            return Mono.just(user);
        }
        // Un code errone compte comme un echec de connexion — le comptage est
        // fait par exigerUneDesDeuxPreuves, qui s'assure qu'il n'a lieu
        // qu'une fois pour une tentative. Sans frein, un million de
        // possibilites s'epuisent.
        return exigerUneDesDeuxPreuves(user, code, ipAddress);
    }

    @Override
    public Mono<TotpEnrolementResponse> enroler(User user) {
        if (Boolean.TRUE.equals(user.getTotpEnabled())) {
            // Refus NOMME, et non le refus uniforme de la connexion.
            //
            // Le refus uniforme se justifie sur la connexion : il ne doit pas
            // dire lequel des controles a echoue. Il ne se justifie pas ici —
            // pour atteindre ce point, l'appelant a deja prouve qu'il connait
            // le mot de passe.
            //
            // Mesure de son cout quand il etait uniforme : la reponse
            // « Identifiants invalides » a envoye deux personnes de suite
            // chercher du cote du mot de passe, pendant que le serveur
            // journalisait la vraie raison.
            log.warn(AppConstants.LOG_WARN_TOTP_DEJA_ACTIF, user.getId());
            return Mono.error(new AuthException(ErrorCode.TWO_FA_ALREADY_ENABLED));
        }
        if (user.getTotpSecret() == null) {
            // Un compte d'administration sans secret est une anomalie du
            // semis : il est pose a la creation. On ne le fabrique pas ici,
            // pour que l'anomalie reste visible.
            log.error(AppConstants.LOG_ERROR_TOTP_SECRET_ABSENT, user.getId());
            return Mono.error(new AuthException(ErrorCode.INVALID_CREDENTIALS));
        }
        authMetrics.recordOperationSecondFacteur(OPERATION_ENROLEMENT);
        return activerEtRemettre(user, user.getTotpSecret(),
                AppConstants.TOTP_MESSAGE_ENROLEMENT);
    }

    @Override
    public Mono<TotpEnrolementResponse> reinitialiser(User user) {
        if (!Boolean.TRUE.equals(user.getTotpEnabled())) {
            // Il n'y a rien a remplacer. Repondre « reinitialise » ferait
            // croire a l'administrateur qu'il a un second facteur actif alors
            // qu'il n'en a pas : il croirait son compte protege.
            log.warn(AppConstants.LOG_WARN_TOTP_PAS_ACTIF, user.getId());
            return Mono.error(new AuthException(ErrorCode.TWO_FA_NOT_ENABLED));
        }
        // Secret NEUF. Conserver l'ancien ferait d'une « reinitialisation » une
        // operation sans effet : l'appareil que l'administrateur voulait
        // remplacer continuerait de produire des codes valides.
        authMetrics.recordOperationSecondFacteur(OPERATION_REINITIALISATION);
        return activerEtRemettre(user, totpService.generateTotpSecret(),
                AppConstants.TOTP_MESSAGE_REINITIALISATION);
    }

    /**
     * {@inheritDoc}
     *
     * <h2>Le compromis inverse de {@code activerEtRemettre}, et pourquoi</h2>
     *
     * {@code activerEtRemettre} active <b>immediatement</b>, en assumant qu'un
     * enrolement rate coute moins cher qu'une fenetre sans second facteur. Ce
     * raisonnement vaut pour un compte d'administration, et il ne vaut pas ici :
     *
     * <ul>
     *   <li>un client n'avait <b>aucun</b> second facteur avant cet appel — il
     *       n'y a donc pas de fenetre a refermer, seulement une protection a
     *       ajouter ;</li>
     *   <li>la sortie d'un enrolement rate est {@link #retirer}, qui demande un
     *       {@code SUPER_ADMIN}. Acceptable pour une poignee
     *       d'administrateurs, intenable pour chaque client enferme hors de son
     *       compte par un telephone a l'heure decalee.</li>
     * </ul>
     */
    @Override
    public Mono<TotpEnrolementResponse> demarrerEnrolement(User user) {
        if (Boolean.TRUE.equals(user.getTotpEnabled())) {
            // Repondre un secret neuf ici remplacerait en silence un second
            // facteur actif : l'appareil en service cesserait de fonctionner
            // sans que personne l'ait demande. Le remplacement deliberе est
            // reinitialiser().
            log.warn(AppConstants.LOG_WARN_TOTP_DEJA_ACTIF, user.getId());
            return Mono.error(new AuthException(ErrorCode.TWO_FA_ALREADY_ENABLED));
        }

        // Un secret NEUF a chaque demarrage, meme si un precedent existait.
        //
        // Reprendre un secret pose lors d'un demarrage abandonne le rendrait une
        // seconde fois : il aurait alors circule deux fois, et la premiere
        // reponse — peut-etre restee dans un historique ou un journal client —
        // suffirait a produire des codes valides.
        String secret = totpService.generateTotpSecret();
        user.setTotpSecret(secret);
        user.setTotpEnabled(false);

        authMetrics.recordOperationSecondFacteur(OPERATION_ENROLEMENT_DEMARRE);
        return userRepository.save(user)
                .doOnSuccess(enregistre -> log.info(
                        "Enrolement du second facteur demarre pour {}", enregistre.getId()))
                .thenReturn(TotpEnrolementResponse.builder()
                        .qrCodeUrl(totpService.generateQrCodeUrl(
                                user.getEmail(), secret, emetteur))
                        .secret(secret)
                        .message(AppConstants.TOTP_MESSAGE_ENROLEMENT_A_CONFIRMER)
                        .build());
    }

    @Override
    public Mono<Void> confirmerEnrolement(User user, String totpCode) {
        if (Boolean.TRUE.equals(user.getTotpEnabled())) {
            return Mono.error(new AuthException(ErrorCode.TWO_FA_ALREADY_ENABLED));
        }
        if (user.getTotpSecret() == null) {
            // Aucun enrolement demarre. Repondre « confirme » laisserait croire
            // le compte protege alors qu'aucun secret n'existe.
            log.warn(AppConstants.LOG_WARN_TOTP_PAS_ACTIF, user.getId());
            return Mono.error(new AuthException(ErrorCode.TWO_FA_NOT_ENABLED));
        }
        // TOTP SEUL : l'objet de cette etape est de prouver que le nouvel
        // appareil produit les bons codes. Un code de secours ne prouverait
        // rien de cela.
        return exigerUnCodeTotp(user, totpCode)
                .flatMap(valide -> {
                    valide.setTotpEnabled(true);
                    authMetrics.recordOperationSecondFacteur(OPERATION_ENROLEMENT_CONFIRME);
                    return userRepository.save(valide)
                            .doOnSuccess(enregistre -> log.info(
                                    AppConstants.LOG_INFO_TOTP_ENROLE, enregistre.getId()));
                })
                .then();
    }

    @Override
    public Mono<Void> desactiver(User user, String code, String ipAddress) {
        if (!Boolean.TRUE.equals(user.getTotpEnabled())) {
            // Rien a desactiver. Un succes ferait croire qu'une protection
            // vient d'etre retiree, donc qu'elle existait.
            return Mono.error(new AuthException(ErrorCode.TWO_FA_NOT_ENABLED));
        }
        // Un code de secours est accepte ici : c'est le chemin de celui qui a
        // perdu son appareil, et le lui fermer ne laisserait que l'intervention
        // humaine d'un SUPER_ADMIN.
        return exigerUneDesDeuxPreuves(user, code, ipAddress)
                .flatMap(valide -> {
                    // Un secret neuf est pose, comme dans retirer() et pour la
                    // meme raison : sans cela, un re-enrolement ulterieur
                    // remettrait en service le secret que l'ancien appareil
                    // porte encore.
                    valide.setTotpSecret(totpService.generateTotpSecret());
                    valide.setTotpEnabled(false);
                    authMetrics.recordOperationSecondFacteur(
                            OPERATION_DESACTIVATION_LIBRE_SERVICE);
                    return userRepository.save(valide)
                            .doOnSuccess(enregistre -> log.info(
                                    "Second facteur desactive en libre-service par {}",
                                    enregistre.getId()));
                })
                // Le lot de codes suit le secret qu'il secourait.
                //
                // Sans cela, les codes de l'ancien appareil resteraient
                // utilisables : apres un re-enrolement — qui exige un code TOTP
                // du NOUVEL appareil, donc ne les invalide pas — ils ouvriraient
                // encore le compte. Un lot imprime et oublie resterait une clef
                // valide des annees plus tard.
                .flatMap(enregistre -> codeDeSecoursService
                        .invaliderLeLot(enregistre.getId()))
                .then();
    }

    @Override
    public Mono<Void> confirmerPourUneOperation(User user, String code, String ipAddress) {
        if (!Boolean.TRUE.equals(user.getTotpEnabled())) {
            // REFUS, et non un laisser-passer comme sur le chemin de connexion.
            //
            // Une operation sensible ne peut pas etre autorisee par une preuve
            // qui n'existe pas. Et pour l'usage principal de cette primitive —
            // accorder la confiance a un appareil — la demande n'a de toute
            // façon aucun sens : un appareil de confiance dispense du second
            // facteur, donc il ne dispense de rien quand il n'y en a pas.
            log.info(AppConstants.LOG_WARN_TOTP_PAS_ACTIF, user.getId());
            return Mono.error(new AuthException(ErrorCode.TWO_FA_NOT_ENABLED));
        }
        return exigerUneDesDeuxPreuves(user, code, ipAddress).then();
    }

    /**
     * Exige un code TOTP valide — et <b>rien d'autre</b>.
     *
     * <p>Reserve a la confirmation d'un enrolement, dont l'objet est justement
     * de prouver que le <b>nouvel</b> appareil produit les bons codes. Un code
     * de secours ne prouverait rien de cela : l'enrolement serait declare
     * confirme alors que l'application d'authentification n'a jamais ete
     * verifiee, et la personne se retrouverait avec un second facteur actif
     * qu'elle ne peut pas produire.
     *
     * <p>Le comptage de l'echec n'est pas optionnel : six chiffres font un
     * million de possibilites, epuisables par qui detient deja une session.
     */
    private Mono<User> exigerUnCodeTotp(User user, String totpCode) {
        if (totpCode == null || totpCode.isBlank()) {
            authMetrics.recordSecondFacteur(RESULTAT_CODE_ABSENT);
            return Mono.error(new AuthException(ErrorCode.TWO_FACTOR_REQUIRED));
        }
        return totpService.validateTotpCode(user.getTotpSecret(), totpCode)
                .flatMap(valide -> {
                    if (Boolean.TRUE.equals(valide)) {
                        authMetrics.recordSecondFacteur(RESULTAT_VALIDE);
                        return Mono.just(user);
                    }
                    return compterLEchecEtRefuser(user);
                });
    }

    /**
     * Exige un code TOTP <b>ou</b> un code de secours.
     *
     * <h2>Pourquoi les codes de secours valent ici</h2>
     *
     * Sans cette seconde preuve, qui perd son telephone est enferme : il entre
     * avec un code de secours, et ne peut plus rien faire ensuite — desactiver
     * le second facteur et le reinitialiser exigent tous deux un code produit
     * par l'appareil perdu. La seule sortie serait un retrait par
     * {@code SUPER_ADMIN}, c'est-a-dire un humain jugeant de l'identite de
     * quelqu'un qui ne peut plus la prouver : le chemin exact des attaques par
     * ingenierie sociale.
     *
     * <h2>Pourquoi le TOTP est essaye d'abord</h2>
     *
     * Il est gratuit — un calcul HMAC — alors qu'un code de secours coute
     * jusqu'a dix comparaisons BCrypt. Dans le cas courant, ou la personne
     * presente bien un code TOTP, rien de plus n'est paye.
     *
     * <h2>Un seul echec, compte ICI</h2>
     *
     * Une tentative est une tentative, quelle que soit la preuve presentee :
     * l'echec est compte une seule fois, et <b>a cet endroit</b> — le seul ou la
     * decision « cette tentative a echoue » est prise.
     *
     * <p>Le compter dans {@code consommer} a ete essaye, et c'etait un defaut :
     * ce service avale ses erreurs de lecture et rend {@code false} sans rien
     * compter — a raison, puisqu'une table illisible ne doit pas empecher une
     * connexion par TOTP. Mais le comptage y etant, une panne sur la table des
     * codes rendait la force brute sur le TOTP <b>gratuite</b>. Le frein
     * disparaissait sans qu'aucune erreur ne le signale.
     */
    private Mono<User> exigerUneDesDeuxPreuves(User user, String code, String ipAddress) {
        if (code == null || code.isBlank()) {
            authMetrics.recordSecondFacteur(RESULTAT_CODE_ABSENT);
            return Mono.error(new AuthException(ErrorCode.TWO_FACTOR_REQUIRED));
        }
        return totpService.validateTotpCode(user.getTotpSecret(), code)
                .flatMap(valide -> {
                    if (Boolean.TRUE.equals(valide)) {
                        authMetrics.recordSecondFacteur(RESULTAT_VALIDE);
                        return Mono.just(user);
                    }
                    return codeDeSecoursService.consommer(user, code, ipAddress)
                            .flatMap(consomme -> {
                                if (Boolean.TRUE.equals(consomme)) {
                                    // Compte a part : cette courbe qui monte
                                    // signale des personnes qui perdent leur
                                    // appareil — ou un attaquant qui brule les
                                    // codes. Melangee a « valide », elle
                                    // disparaitrait dans le bruit des
                                    // connexions ordinaires.
                                    authMetrics.recordSecondFacteur(
                                            RESULTAT_CODE_DE_SECOURS);
                                    return Mono.just(user);
                                }
                                return compterLEchecEtRefuser(user);
                            });
                });
    }

    private Mono<User> compterLEchecEtRefuser(User user) {
        authMetrics.recordSecondFacteur(RESULTAT_CODE_FAUX);
        return accountLockoutService.enregistrerEchec(user)
                .then(Mono.error(new AuthException(ErrorCode.INVALID_CREDENTIALS)));
    }

    @Override
    public Mono<Void> retirer(User user) {
        // Un secret neuf est pose en meme temps que le retrait.
        //
        // Sans cela, l'administrateur re-enrolerait l'ANCIEN secret — celui que
        // porte encore l'appareil perdu, qui est precisement la raison du
        // retrait. Le retrait annulerait alors son propre motif.
        // Retrait du second facteur d'AUTRUI par un super administrateur :
        // l'operation la plus sensible de cette classe. Comptee pour qu'une
        // alerte se declenche des la premiere occurrence, et non sur un taux —
        // une seule suffit a ouvrir un compte d'administration.
        authMetrics.recordOperationSecondFacteur(OPERATION_RETRAIT);
        user.setTotpSecret(totpService.generateTotpSecret());
        user.setTotpEnabled(false);
        return userRepository.save(user)
                .doOnSuccess(enregistre -> log.warn(
                        AppConstants.LOG_WARN_TOTP_RETIRE, enregistre.getId()))
                // Le lot de codes suit le secret retire, pour la meme raison
                // que dans desactiver() : sinon les codes de l'appareil perdu —
                // le motif meme du retrait — resteraient utilisables apres un
                // re-enrolement.
                .flatMap(enregistre -> codeDeSecoursService
                        .invaliderLeLot(enregistre.getId()))
                .then();
    }

    /**
     * Pose le secret, active le second facteur, et le remet une seule fois.
     *
     * <p>Le second facteur est actif <b>des cet appel</b>, avant toute preuve
     * que le nouvel appareil a bien ete configure. C'est deliberе : laisser le
     * compte en « non enrole » jusqu'a la premiere connexion reussie ouvrirait
     * une fenetre ou {@link #verifier} laisse passer sans code. Une fenetre
     * sans second facteur sur un compte d'administration coute plus cher qu'un
     * enrolement rate, dont la sortie existe ({@link #retirer}).
     */
    private Mono<TotpEnrolementResponse> activerEtRemettre(
            User user, String secret, String message) {
        String qrCodeUrl = totpService.generateQrCodeUrl(user.getEmail(), secret, emetteur);
        user.setTotpSecret(secret);
        user.setTotpEnabled(true);
        return userRepository.save(user)
                .doOnSuccess(enregistre -> log.info(
                        AppConstants.LOG_INFO_TOTP_ENROLE, enregistre.getId()))
                // Un lot de codes est lie au SECRET qu'il secourt.
                //
                // Ce chemin pose un secret neuf — c'est tout son objet. Les
                // codes de l'ancien doivent donc mourir avec lui : une
                // reinitialisation apres perte d'appareil laisserait sinon
                // valides les codes imprimes du temps de l'appareil perdu.
                //
                // Sans effet au premier enrolement, ou aucun lot n'existe :
                // l'invariant est plus simple a tenir que ses exceptions.
                .flatMap(enregistre -> codeDeSecoursService
                        .invaliderLeLot(enregistre.getId())
                        .thenReturn(enregistre))
                // thenReturn et non Mono.fromCallable : un mappeur qui rend nul
                // produirait un Mono VIDE, donc une reponse 200 sans corps, en
                // silence. Ce depot a deja paye cette confusion.
                .thenReturn(TotpEnrolementResponse.builder()
                        .qrCodeUrl(qrCodeUrl)
                        .secret(secret)
                        .message(message)
                        .build());
    }
}
