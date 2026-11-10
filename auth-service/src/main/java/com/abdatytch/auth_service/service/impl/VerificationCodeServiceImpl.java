package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.entities.VerificationCode;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.VerificationCodeRepository;
import com.abdatytch.commons.notification.IMessagerieSortante;
import com.abdatytch.auth_service.service.IVerificationCodeService;
import com.abdatytch.commons.notification.CanalDeNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Génération, envoi et validation des codes de vérification (OTP).
 *
 * <p><b>Cache retiré.</b> Spring Cache ne gère pas les types réactifs : les
 * anciennes annotations {@code @Cacheable}/{@code @CacheEvict} auraient mis en cache
 * des {@code Mono} non souscrits. Sur des codes à durée de vie de quelques minutes,
 * lus une fois, le cache n'apportait rien et masquait des invalidations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationCodeServiceImpl implements IVerificationCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Préfixe des numéros factices utilisés lors d'une inscription par email
     * seul. Déplacé dans {@link AppConstants#TEMP_PHONE_PREFIX} : un second
     * service en a besoin, et deux littéraux à maintenir d'accord auraient fini
     * par diverger.
     */
    private static final String TEMP_PHONE_PREFIX = AppConstants.TEMP_PHONE_PREFIX;

    private final VerificationCodeRepository verificationCodeRepository;

    /**
     * UNE dependance d'envoi, et il y en avait trois.
     *
     * <p>{@code ISmsService}, {@code IEmailService} et
     * {@code IMessageTemplateService} : deux canaux et un moteur de modeles,
     * pour un seul besoin — faire partir un code. Les trois ont ete remplaces
     * par {@link IMessagerieSortante}, qui parle a notification-service, seul
     * detenteur des textes et des fournisseurs.
     */
    private final IMessagerieSortante messagerie;

    private final AuthMetrics authMetrics;

    @Override
    @Transactional
    public Mono<EnvoiDuCode> generateAndSendCode(String phoneNumber, String email,
                                                 VerificationCodeType type) {
        return generateAndSendCode(phoneNumber, email, type, null, null, null);
    }

    @Override
    @Transactional
    public Mono<EnvoiDuCode> generateAndSendCode(String phoneNumber, String email,
                                                 VerificationCodeType type,
                                                 String firstName, String lastName,
                                                 String countryCode) {
        log.info(ServiceConstants.LOG_INFO_GENERATING_CODE, phoneNumber, type);

        return verificationCodeRepository.findByPhoneNumberAndType(phoneNumber, type)
                // Un code encore valide interdit d'en redemander un : anti-spam.
                .flatMap(existing -> !existing.isExpired() && !Boolean.TRUE.equals(existing.getIsUsed())
                        ? Mono.<VerificationCode>error(rejectPendingCode(phoneNumber))
                        : Mono.empty())
                .then(verificationCodeRepository.deleteByPhoneNumberAndType(phoneNumber, type))
                .then(Mono.defer(() -> {
                    String code = generateCode();

                    VerificationCode verificationCode = VerificationCode.builder()
                            .phoneNumber(phoneNumber)
                            .email(email)
                            .code(code)
                            .type(type)
                            .firstName(firstName)
                            .lastName(lastName)
                            .countryCode(countryCode)
                            .expiresAt(LocalDateTime.now()
                                    .plusMinutes(AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES))
                            .build();

                    // Le code est ENREGISTRE avant toute tentative d'envoi, et
                    // c'est l'ordre voulu : un envoi qui echoue laisse un code
                    // valide que la personne peut redemander, alors qu'un envoi
                    // reussi sans enregistrement produirait un code qu'aucune
                    // validation ne reconnaitrait.
                    return verificationCodeRepository.save(verificationCode)
                            .flatMap(saved -> sendCode(phoneNumber, email, code, type))
                            .doOnNext(envoi -> {
                                compterLesEnvois(envoi);
                                // Le CODE n'est plus journalise. Il l'etait avec
                                // le numero de destination, a INFO et sans
                                // condition : chaque code a usage unique jamais
                                // emis etait lisible dans Loki, avec son
                                // destinataire.
                                log.info(ServiceConstants.LOG_INFO_CODE_GENERATED, phoneNumber);
                            });
                }));
    }

    /**
     * Compte les envois REELLEMENT acceptes, un par canal.
     *
     * <h2>Deux defauts corriges d'un coup</h2>
     *
     * <p><b>Un.</b> La metrique etait posee dans un {@code doOnSuccess} et
     * comptait <b>un</b> envoi par appel, avec un canal deduit de
     * {@code email != null ? "email" : "sms"}. Un parcours telephone+courriel —
     * le cas courant, le SMS et son doublon de courtoisie — comptait donc UN
     * envoi etiquete « email », et le SMS disparaissait de la mesure.
     *
     * <p><b>Deux.</b> Elle comptait quoi qu'il arrive. Un plafond journalier
     * atteint ou un numero hors liste autorisee produisait le meme increment
     * qu'un envoi reussi : le panneau Grafana
     * {@code sum(auth_otp_sent_total) by (channel)} montrait des envois qui
     * n'avaient pas eu lieu.
     *
     * <p>Et {@code AuthServiceImpl} en posait un SECOND, sur ses deux chemins
     * d'inscription : ceux-la comptaient donc <b>double</b>. Les deux sites ont
     * ete retires — la metrique appartient a qui envoie, seul a savoir ce qui
     * est parti. Meme regle que le verrouillage de compte, compte chez
     * {@code AccountLockoutServiceImpl} et non chez ses appelants.
     *
     * <p>Les etiquettes {@code sms} et {@code email} sont conservees telles
     * quelles : le panneau Grafana les groupe, et les renommer couperait la
     * serie.
     */
    private void compterLesEnvois(EnvoiDuCode envoi) {
        if (envoi.smsAccepte()) {
            authMetrics.recordOtpSent("sms");
        }
        if (envoi.courrielAccepte()) {
            authMetrics.recordOtpSent("email");
        }
    }

    private AuthException rejectPendingCode(String phoneNumber) {
        log.warn(ServiceConstants.LOG_WARN_CODE_RESEND_TOO_SOON, phoneNumber);
        return new AuthException(ErrorCode.VERIFICATION_CODE_PENDING);
    }

    @Override
    @Transactional
    public Mono<Void> validateCode(String phoneNumber, String code, VerificationCodeType type) {
        log.info(ServiceConstants.LOG_INFO_VERIFYING_CODE, phoneNumber);

        return verificationCodeRepository.findByPhoneNumberAndCodeAndType(phoneNumber, code, type)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.VERIFICATION_CODE_INVALID)))
                .flatMap(this::validateAndMarkUsed)
                .doOnSuccess(unused -> log.info("Code validation successful for phone: {}", phoneNumber))
                .doOnError(AuthException.class,
                        e -> log.warn("Code validation failed for phone {}: {}", phoneNumber, e.getMessage()));
    }

    @Override
    @Transactional
    public Mono<Void> validateCodeByEmail(String email, String code, VerificationCodeType type) {
        log.info(ServiceConstants.LOG_INFO_VERIFYING_CODE, email);

        return verificationCodeRepository.findByEmailAndCodeAndType(email, code, type)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.VERIFICATION_CODE_INVALID)))
                .flatMap(this::validateAndMarkUsed)
                .doOnSuccess(unused -> log.info("Code validation successful for email: {}", email))
                .doOnError(AuthException.class,
                        e -> log.warn("Code validation failed for email {}: {}", email, e.getMessage()));
    }

    /**
     * Contrôle l'état du code puis le marque comme consommé.
     * L'incrément du compteur de tentatives est conservé même en cas de succès :
     * il sert de garde-fou en cas de rejeu.
     */
    private Mono<Void> validateAndMarkUsed(VerificationCode verificationCode) {
        String identifier = verificationCode.getEmail() != null
                ? verificationCode.getEmail()
                : verificationCode.getPhoneNumber();

        if (Boolean.TRUE.equals(verificationCode.getIsUsed())) {
            log.warn("Attempt to use already used verification code for: {}", identifier);
            return Mono.error(new AuthException(ErrorCode.VERIFICATION_CODE_USED));
        }
        if (verificationCode.isExpired()) {
            log.warn("Attempt to use expired verification code for: {}", identifier);
            return Mono.error(new AuthException(ErrorCode.VERIFICATION_CODE_EXPIRED));
        }
        if (verificationCode.getAttempts() >= AppConstants.MAX_VERIFICATION_ATTEMPTS) {
            log.warn("Too many validation attempts for verification code: {}", identifier);
            return Mono.error(new AuthException(ErrorCode.TOO_MANY_ATTEMPTS));
        }

        verificationCode.setAttempts(verificationCode.getAttempts() + 1);
        verificationCode.setIsUsed(true);
        verificationCode.setUsedAt(LocalDateTime.now());

        return verificationCodeRepository.save(verificationCode)
                .doOnSuccess(saved -> log.info(
                        "Verification code successfully validated and marked as used for: {}", identifier))
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> incrementAttempts(String phoneNumber, VerificationCodeType type) {
        return verificationCodeRepository
                .findTopByPhoneNumberAndTypeOrderByCreatedAtDesc(phoneNumber, type)
                .flatMap(code -> {
                    code.setAttempts(code.getAttempts() + 1);
                    return verificationCodeRepository.save(code);
                })
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> cleanupExpiredCodes() {
        log.info(ServiceConstants.LOG_INFO_RESENDING_CODE, "system");
        return verificationCodeRepository.deleteByExpiresAtBefore(LocalDateTime.now())
                .doOnSuccess(deleted -> log.info("Cleaned up {} expired verification codes", deleted))
                .then();
    }

    @Override
    public Mono<VerificationCode> getLatestCode(String phoneNumber, VerificationCodeType type) {
        return verificationCodeRepository
                .findTopByPhoneNumberAndTypeOrderByCreatedAtDesc(phoneNumber, type);
    }

    private String generateCode() {
        return String.valueOf(RANDOM.nextInt(900000) + 100000);
    }

    /**
     * Le modele a rendre, pour TOUS les canaux.
     *
     * <h2>Une seule table, et il y en avait deux</h2>
     *
     * Ce module portait {@code getSmsTemplateType} ici et
     * {@code getTemplateTypeForVerification} dans {@code EmailServiceImpl} —
     * deux tables a tenir d'accord, pour le meme message sur deux canaux. Elles
     * decoulaient d'un {@code TemplateType} qui MELANGEAIT le canal et l'objet :
     * {@code SMS_VERIFICATION} et {@code EMAIL_VERIFICATION} nommaient le meme
     * message.
     *
     * <p>Avec le couple (modele, canal) de notification-service, l'objet est
     * nomme une fois et le canal n'est qu'une variante du texte. Les cinq
     * anciens types se ramenaient a trois modeles ; les deux dont ce service a
     * besoin sont ici.
     */
    private static String modelePour(VerificationCodeType type) {
        return switch (type) {
            case PASSWORD_RESET -> AppConstants.MODELE_REINITIALISATION;
            // Pas de `default`, volontairement : il absorberait en silence le
            // prochain type ajoute a l'enumeration, qui partirait alors avec le
            // texte d'un code de verification ordinaire.
            case REGISTRATION, PHONE_VERIFICATION, EMAIL_VERIFICATION, TWO_FACTOR_AUTH,
                 PHONE_CONFIRMATION, EMAIL_CONFIRMATION, PHONE_CHANGE ->
                    AppConstants.MODELE_CODE_DE_VERIFICATION;
        };
    }

    /**
     * Achemine le code selon le canal disponible.
     *
     * <p>Un numéro préfixé {@code temp_} signale une inscription par email seul :
     * il n'y a alors pas de SMS à envoyer.
     *
     * <h2>Ce service ne compose plus aucun texte</h2>
     *
     * Il nomme un modele et fournit ses variables ; notification-service rend.
     * Le meme modele sert aux deux canaux, et c'est lui qui choisit sa variante
     * courte pour le SMS — ce service n'a donc plus a savoir qu'un SMS tient en
     * 160 caracteres.
     *
     * <h2>Aucun journal de succes ici</h2>
     *
     * {@code doOnSuccess} sur un {@code Mono<Void>} se declenchait AUSSI quand la
     * source se terminait vide, c'est-a-dire quand rien n'etait parti :
     * {@code sms.enabled = false}, ou repli du disjoncteur. Ce site ecrivait donc
     * « SMS envoyé avec succès » juste apres « Disjoncteur SMS ouvert » — un
     * journal qui affirme le contraire de ce qui s'est passe, et precisement
     * pendant un incident.
     *
     * <p>Le client journalise desormais les NON-remises avec leur motif, ce que
     * {@code Mono<Boolean>} rend possible et que {@code Mono<Void>} interdisait.
     */
    private Mono<EnvoiDuCode> sendCode(String phoneNumber, String email, String code,
                                       VerificationCodeType type) {
        boolean emailOnly = phoneNumber != null && phoneNumber.startsWith(TEMP_PHONE_PREFIX);

        String modele = modelePour(type);
        Map<String, String> variables = Map.of(
                ServiceConstants.TEMPLATE_VAR_CODE, code,
                ServiceConstants.TEMPLATE_VAR_MINUTES,
                String.valueOf(AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));

        if (emailOnly) {
            if (email == null || email.isEmpty()) {
                return Mono.error(new AuthException(ErrorCode.MISSING_REQUIRED_FIELD, "email"));
            }
            // AUCUNE ERREUR LEVEE, et le resultat REMONTE.
            //
            // Cette branche levait EMAIL_SERVICE_ERROR quand l'envoi echouait,
            // et se terminait en silence quand `email.enabled` etait faux. Avec
            // un booleen, les deux cas se confondent : un canal DELIBEREMENT
            // inactif rend le meme `false` qu'un serveur SMTP en panne. Lever
            // sur ce `false` casserait l'inscription par courriel en
            // developpement, ou l'envoi est coupe par choix.
            //
            // Le code est enregistre et l'utilisateur peut en redemander un. Ce
            // qui a change : le booleen remonte desormais jusqu'a la REPONSE
            // HTTP, qui ne pretend plus avoir envoye. Le journal reste, pour
            // l'exploitant.
            return messagerie.envoyer(IMessagerieSortante.Demande.critique(
                            CanalDeNotification.COURRIEL, email, modele, variables))
                    .doOnNext(parti -> {
                        if (!Boolean.TRUE.equals(parti)) {
                            // L'adresse n'est PAS journalisee : donnee
                            // personnelle, et ce journal part vers Loki.
                            log.warn("Inscription par courriel seul : le code n'a "
                                    + "pas pu etre remis. L'utilisateur devra en "
                                    + "redemander un.");
                        }
                    })
                    .map(EnvoiDuCode::parCourrielSeul);
        }

        Mono<Boolean> sms = messagerie.envoyer(IMessagerieSortante.Demande.critique(
                CanalDeNotification.SMS, phoneNumber, modele, variables));

        if (email == null || email.isEmpty()) {
            return sms.map(EnvoiDuCode::parSmsSeul);
        }

        // L'email est un doublon de courtoisie : son échec ne doit pas faire
        // échouer l'envoi alors que le SMS est parti.
        //
        // Le rattrapage est garde malgre le contrat d'IMessagerieSortante, qui
        // promet de ne JAMAIS lever. Raisonner sur l'implementation plutot que
        // sur le contrat serait une erreur ici : une implementation future qui
        // violerait cette promesse ferait echouer l'inscription APRES
        // l'enregistrement du code, et le code serait perdu pour l'utilisateur
        // alors qu'il est en base.
        //
        // Ce n'est pas du code defensif mort : un test le traverse, en
        // simulant precisement cette violation.
        //
        // `flatMap` et NON `then`, et c'est le coeur de la correction : `then`
        // JETAIT le booleen du SMS. Le sort du canal principal etait donc perdu
        // avant de remonter, et la reponse HTTP ne pouvait que le deviner depuis
        // un interrupteur de configuration. Les deux resultats sont desormais
        // conserves ensemble.
        return sms.flatMap(smsParti ->
                messagerie.envoyer(IMessagerieSortante.Demande.critique(
                                CanalDeNotification.COURRIEL, email, modele, variables))
                        .onErrorResume(erreur -> {
                            log.warn("Le courriel de courtoisie a leve {} alors que "
                                            + "le contrat l'interdit ; le SMS est parti, "
                                            + "l'inscription continue",
                                    erreur.getClass().getSimpleName());
                            return Mono.just(false);
                        })
                        .map(courrielParti -> new EnvoiDuCode(smsParti, courrielParti)));
    }

    // ==========================================
    // Replis Resilience4j
    // ==========================================

    @SuppressWarnings("unused")


    /**
     * Une erreur métier (code déjà en attente, champ manquant) ne doit pas être
     * masquée en « service indisponible » : elle est propagée telle quelle.
     */
    private Mono<VerificationCode> serviceUnavailable(String phoneNumber, Throwable e) {
        if (e instanceof AuthException authException) {
            return Mono.error(authException);
        }
        log.error("Fallback: Failed to generate verification code for {}: {}", phoneNumber, e.getMessage());
        return Mono.error(new AuthException(ErrorCode.SERVICE_UNAVAILABLE,
                "Service temporairement indisponible. Veuillez réessayer."));
    }
}
