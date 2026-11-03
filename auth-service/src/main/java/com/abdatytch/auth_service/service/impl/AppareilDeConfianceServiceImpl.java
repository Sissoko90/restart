package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.AppareilDeConfianceResponse;
import com.abdatytch.auth_service.dto.response.ConfianceAccordeeResponse;
import com.abdatytch.auth_service.dto.response.DeviceInfo;
import com.abdatytch.auth_service.entities.AppareilDeConfiance;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.AppareilDeConfianceRepository;
import com.abdatytch.auth_service.service.IAppareilDeConfianceService;
import com.abdatytch.auth_service.service.IDeviceInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.UUID;

/**
 * Appareils de confiance : voir {@link IAppareilDeConfianceService}.
 */
@Service
@Slf4j
public class AppareilDeConfianceServiceImpl implements IAppareilDeConfianceService {

    /** 32 octets : assez pour qu'un jeton ne soit pas devinable. */
    private static final int OCTETS_DU_JETON = 32;

    private static final SecureRandom ALEA = new SecureRandom();

    private final AppareilDeConfianceRepository appareils;
    private final IDeviceInfoService deviceInfoService;
    private final long validiteEnJours;

    public AppareilDeConfianceServiceImpl(
            AppareilDeConfianceRepository appareils,
            IDeviceInfoService deviceInfoService,
            @Value("${auth.appareil-de-confiance.validite-jours:30}") long validiteEnJours) {
        this.appareils = appareils;
        this.deviceInfoService = deviceInfoService;
        this.validiteEnJours = validiteEnJours;
    }

    @Override
    @Transactional
    public Mono<ConfianceAccordeeResponse> accorder(User user, String nomDemande,
                                                    String userAgent, String ipAddress) {
        // Mono.defer : la generation du jeton et le hachage sont faits ici, et
        // une levee a l'assemblage sortirait de la chaine. Piege corrige
        // plusieurs fois dans ce depot.
        return Mono.defer(() -> accorderMaintenant(user, nomDemande, userAgent, ipAddress));
    }

    private Mono<ConfianceAccordeeResponse> accorderMaintenant(User user, String nomDemande,
                                                                String userAgent,
                                                                String ipAddress) {
        byte[] octets = new byte[OCTETS_DU_JETON];
        ALEA.nextBytes(octets);
        String jeton = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);

        DeviceInfo appareil = deviceInfoService.parseUserAgent(userAgent);
        LocalDateTime maintenant = LocalDateTime.now();

        AppareilDeConfiance ligne = AppareilDeConfiance.builder()
                .userId(user.getId())
                .empreinteJeton(empreinte(jeton))
                .nom(nomLisible(nomDemande, appareil))
                .navigateur(navigateur(appareil))
                .systeme(systeme(appareil))
                .typeAppareil(appareil != null ? appareil.getDeviceType() : null)
                .ipAccordee(ipAddress)
                .accordeLe(maintenant)
                // L'echeance est posee a la creation et jamais prolongee par
                // l'usage : une confiance qui se renouvelle a chaque connexion
                // ne s'eteint jamais, ce qui revient a une confiance
                // perpetuelle.
                .expireLe(maintenant.plusDays(validiteEnJours))
                .build();
        ligne.setId(UUID.randomUUID().toString());

        return appareils.save(ligne)
                // Le JETON n'est pas journalise : il dispense du second
                // facteur, donc un journal qui le contient permet de se
                // connecter sans code. Seul l'identifiant de la ligne l'est.
                .doOnSuccess(enregistre -> log.info(
                        AppConstants.LOG_INFO_CONFIANCE_ACCORDEE,
                        enregistre.getId(), user.getId()))
                .map(enregistre -> ConfianceAccordeeResponse.builder()
                        .id(enregistre.getId())
                        .jeton(jeton)
                        .nom(enregistre.getNom())
                        .expireLe(enregistre.getExpireLe())
                        .message(AppConstants.CONFIANCE_JETON_UNIQUE)
                        .build());
    }

    @Override
    public Mono<Boolean> estDeConfiance(String userId, String jeton) {
        if (jeton == null || jeton.isBlank()) {
            // Aucun jeton presente : le cas courant d'une premiere connexion
            // depuis un appareil. Pas une erreur — le second facteur sera
            // simplement demande.
            return Mono.just(false);
        }

        return appareils.findByEmpreinteJeton(empreinte(jeton))
                .flatMap(appareil -> {
                    LocalDateTime maintenant = LocalDateTime.now();

                    // L'appartenance est verifiee AVANT l'echeance.
                    //
                    // Un jeton valide presente sur un AUTRE compte ne doit rien
                    // accorder : sans ce controle, qui obtient le jeton d'un
                    // appareil se dispenserait du second facteur sur n'importe
                    // quel compte dont il connait le mot de passe.
                    if (!userId.equals(appareil.getUserId())) {
                        log.warn(AppConstants.LOG_WARN_CONFIANCE_AUTRE_COMPTE,
                                appareil.getId(), userId);
                        return Mono.just(false);
                    }
                    if (!appareil.estDeConfiance(maintenant)) {
                        return Mono.just(false);
                    }

                    appareil.setUtiliseLe(maintenant);
                    // L'echeance n'est PAS prolongee : voir accorderMaintenant.
                    return appareils.save(appareil).thenReturn(true);
                })
                // Jeton inconnu : switchIfEmpty sur le Mono TYPE, et non sur un
                // Mono<Void> final. La regle corrigee une douzaine de fois dans
                // ce depot.
                .switchIfEmpty(Mono.just(false))
                .onErrorResume(erreur -> {
                    // Une panne de base ne doit pas faire echouer la connexion :
                    // elle doit faire demander le second facteur. Refuser
                    // transformerait un incident de base en impossibilite de se
                    // connecter, alors que le parcours complet reste possible.
                    log.error(AppConstants.LOG_ERROR_CONFIANCE_ILLISIBLE,
                            erreur.getClass().getSimpleName());
                    return Mono.just(false);
                });
    }

    @Override
    public Flux<AppareilDeConfianceResponse> duCompte(String userId) {
        LocalDateTime maintenant = LocalDateTime.now();
        return appareils.findByUserIdAndRevoqueLeIsNull(userId)
                // Les appareils echus sont ecartes : les montrer ferait croire a
                // une confiance encore active, et pousserait a « revoquer » ce
                // qui ne vaut plus rien pendant que le vrai appareil reste.
                .filter(appareil -> appareil.estDeConfiance(maintenant))
                .sort(Comparator.comparing(AppareilDeConfiance::getUtiliseLe,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(appareil -> AppareilDeConfianceResponse.de(appareil, maintenant));
    }

    @Override
    @Transactional
    public Mono<Void> revoquer(String userId, String appareilId) {
        return appareils.findById(appareilId)
                .filter(appareil -> userId.equals(appareil.getUserId()))
                // 404 et non 403 : un 403 confirmerait que l'appareil existe et
                // appartient a quelqu'un d'autre, ce qui se parcourt.
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn(AppConstants.LOG_WARN_CONFIANCE_REVOCATION_REFUSEE,
                            appareilId, userId);
                    return Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND));
                }))
                .flatMap(appareil -> revoquerLigne(appareil, AppConstants.REVOQUE_PAR_PROPRIETAIRE))
                .then();
    }

    @Override
    @Transactional
    public Mono<Long> revoquerTous(String userId) {
        LocalDateTime maintenant = LocalDateTime.now();
        return appareils.findByUserIdAndRevoqueLeIsNull(userId)
                .filter(appareil -> appareil.estDeConfiance(maintenant))
                .flatMap(appareil -> revoquerLigne(appareil,
                        AppConstants.REVOQUE_PAR_PROPRIETAIRE))
                .count()
                .doOnSuccess(nombre -> log.info(
                        AppConstants.LOG_INFO_CONFIANCES_REVOQUEES, nombre, userId));
    }

    private Mono<AppareilDeConfiance> revoquerLigne(AppareilDeConfiance appareil,
                                                    String par) {
        // La ligne est conservee : savoir qu'un appareil a eu la confiance et
        // l'a perdue, quand et par qui, est ce qu'un journal d'audit doit
        // montrer. « Effacer n'est pas supprimer la ligne ».
        appareil.setRevoqueLe(LocalDateTime.now());
        appareil.setRevoquePar(par);
        return appareils.save(appareil);
    }

    /**
     * SHA-256 du jeton, en hexadecimal.
     *
     * <h2>Pourquoi SHA-256 et non BCrypt</h2>
     *
     * Le jeton fait 32 octets aleatoires : il n'y a aucune attaque par
     * dictionnaire a ralentir. BCrypt couterait les 241 ms mesures dans ce
     * depot, a chaque connexion presentant un jeton, pour aucune protection
     * supplementaire. C'est le meme raisonnement que pour un jeton de session.
     *
     * <p>Et SHA-256 est du calcul pur : aucun portage sur
     * {@code boundedElastic} n'est necessaire, contrairement a BCrypt.
     */
    static String empreinte(String jeton) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] condensat = sha256.digest(jeton.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexa = new StringBuilder(condensat.length * 2);
            for (byte octet : condensat) {
                hexa.append(String.format("%02x", octet));
            }
            return hexa.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 est exige par la specification de la plateforme Java :
            // cette branche ne peut pas se produire. Une IllegalStateException
            // plutot qu'un repli silencieux, qui stockerait un jeton en clair.
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /**
     * Nom lisible de l'appareil.
     *
     * <p>Celui de la personne s'il est fourni, sinon un nom deduit. Sans nom
     * lisible, une liste d'appareils ne permet pas de reconnaitre celui qu'on
     * veut revoquer.
     */
    private static String nomLisible(String nomDemande, DeviceInfo appareil) {
        if (nomDemande != null && !nomDemande.isBlank()) {
            return nomDemande.trim();
        }
        String navigateur = navigateur(appareil);
        String systeme = systeme(appareil);
        if (navigateur == null && systeme == null) {
            return AppConstants.APPAREIL_NOM_INCONNU;
        }
        return (navigateur != null ? navigateur : AppConstants.APPAREIL_NOM_INCONNU)
                + (systeme != null ? " / " + systeme : "");
    }

    private static String navigateur(DeviceInfo appareil) {
        if (appareil == null || appareil.getBrowserFamily() == null) {
            return null;
        }
        return appareil.getBrowserVersion() != null
                ? appareil.getBrowserFamily() + " " + appareil.getBrowserVersion()
                : appareil.getBrowserFamily();
    }

    private static String systeme(DeviceInfo appareil) {
        if (appareil == null || appareil.getOsFamily() == null) {
            return null;
        }
        return appareil.getOsVersion() != null
                ? appareil.getOsFamily() + " " + appareil.getOsVersion()
                : appareil.getOsFamily();
    }
}
