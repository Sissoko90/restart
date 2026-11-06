package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.request.BiometricRegistrationRequest;
import com.abdatytch.auth_service.dto.request.BiometricVerificationRequest;
import com.abdatytch.auth_service.dto.response.BiometricCredentialResponse;
import com.abdatytch.auth_service.dto.response.BiometricRegistrationResponse;
import com.abdatytch.auth_service.dto.response.BiometricVerificationResponse;
import com.abdatytch.auth_service.entities.BiometricCredential;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.BiometricType;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.util.IdentifiantDeCompte;
import com.abdatytch.auth_service.repository.BiometricCredentialRepository;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.IBiometricAuthService;
import com.abdatytch.auth_service.service.IJwtService;
import com.abdatytch.auth_service.service.IRefreshTokenService;
import com.abdatytch.auth_service.security.webauthn.WebAuthnVerifier;
import com.abdatytch.auth_service.security.webauthn.WebAuthnVerificationException;
import com.abdatytch.commons.security.SecretComparisonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**

 * Authentification biométrique (WebAuthn/FIDO2), en réactif.
 *
 * <p>Le challenge transite par Redis via {@link ReactiveRedisTemplate} : la version
 * bloquante {@code RedisTemplate} retenait un thread de l'EventLoop.
 *
 * <p>La vérification est déléguée à
 * {@link com.abdatytch.auth_service.security.webauthn.WebAuthnVerifier}, qui
 * s'appuie sur {@code webauthn4j}. Elle remplace un placeholder qui encodait en
 * Base64 la concaténation des champs : l'appelant fournissant tous ces champs
 * sauf la clé publique — qui n'est pas un secret — il pouvait se faire émettre
 * un jeton au nom d'autrui.
 *
 * <p><b>Les credentials enregistrées avant ce changement sont inutilisables</b> :
 * leur clé publique venait du client et n'est pas un {@code AttestedCredentialData}
 * exploitable. Elles doivent être ré-enregistrées.
 */
@Slf4j
@Service
@RefreshScope
@RequiredArgsConstructor
public class BiometricAuthServiceImpl implements IBiometricAuthService {

    private static final int CHALLENGE_LENGTH = 32;
    private static final String BIOMETRIC_CHALLENGE_PREFIX = "biometric:challenge:";
    private static final Duration CHALLENGE_EXPIRATION = Duration.ofMinutes(10);

    private final BiometricCredentialRepository biometricCredentialRepository;
    private final UserRepository userRepository;
    private final IJwtService jwtService;
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final WebAuthnVerifier webAuthnVerifier;
    private final IRefreshTokenService refreshTokenService;

    /**
     * Interrupteur d'exploitation du parcours biometrique. Actif par defaut.
     *
     * <p>Il a d'abord servi de garde-fou, le temps que la verification soit
     * reellement implementee : {@code verifyAuthentication} emet un jeton JWT,
     * et l'ancienne verification n'etait qu'un Base64 de concatenation que tout
     * appelant connaissant la cle publique pouvait reproduire.
     *
     * <p>La verification passe desormais par {@link WebAuthnVerifier}, qui
     * delegue a {@code webauthn4j}. Le drapeau reste, mais comme moyen de couper
     * le parcours en exploitation — pas comme protection contre son propre code.
     */
    @Value("${biometric.authentication.enabled:true}")
    private boolean authentificationActivee;

    @Override
    @Transactional
    public Mono<BiometricRegistrationResponse> initiateRegistration(String userId, BiometricType type) {
        log.info("Initiation de l'enregistrement biométrique {} pour l'utilisateur: {}", type, userId);

        return requireUser(userId)
                .flatMap(user -> {
                    String challenge = generateChallenge();

                    // Le challenge est indexe par UTILISATEUR, et non par un
                    // credentialId genere ici.
                    //
                    // L'ancienne version fabriquait un UUID cote serveur et s'en
                    // servait de cle. Or en WebAuthn le credentialId est produit
                    // par l'AUTHENTIFICATEUR, pas par le serveur : cet UUID
                    // n'etait qu'un jeton de session deguise, et le vrai
                    // identifiant arrivait ensuite du client, qui pouvait le
                    // choisir. Il est maintenant extrait de l'attestation
                    // verifiee.
                    return reactiveRedisTemplate.opsForValue()
                            .set(cleChallengeEnregistrement(userId), challenge, CHALLENGE_EXPIRATION)
                            .thenReturn(BiometricRegistrationResponse.builder()
                                    .biometricType(type)
                                    .challenge(challenge)
                                    .message("Challenge généré. Veuillez compléter l'enregistrement biométrique.")
                                    .success(true)
                                    .build());
                });
    }

    @Override
    @Transactional
    public Mono<BiometricRegistrationResponse> completeRegistration(
            String userId, BiometricRegistrationRequest request) {
        log.info("Finalisation de l'enregistrement biométrique {} pour l'utilisateur: {}",
                request.getType(), userId);

        String cleChallenge = cleChallengeEnregistrement(userId);

        return requireUser(userId)
                .then(reactiveRedisTemplate.opsForValue().get(cleChallenge))
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.INVALID_INPUT,
                        "Challenge expiré ou invalide. Veuillez réinitier l'enregistrement.")))
                .flatMap(challenge -> verifierAttestation(request, challenge))
                .flatMap(verifie -> biometricCredentialRepository
                        .findByCredentialId(verifie.credentialId())
                        .flatMap(existante -> Boolean.TRUE.equals(existante.getIsActive())
                                ? Mono.<BiometricCredential>error(new AuthException(
                                        ErrorCode.INVALID_INPUT, "Cette credential existe déjà"))
                                : Mono.<BiometricCredential>empty())
                        .switchIfEmpty(Mono.defer(() -> biometricCredentialRepository.save(
                                BiometricCredential.builder()
                                        .userId(userId)
                                        .credentialId(verifie.credentialId())
                                        // Le materiel de cle vient de
                                        // l'attestation verifiee, et JAMAIS du
                                        // champ publicKey de la requete :
                                        // accepter une cle publique de la main
                                        // du client revient a lui laisser
                                        // choisir la serrure.
                                        .publicKey(verifie.donneesCredential())
                                        .biometricType(request.getType())
                                        .deviceName(request.getDeviceName() != null
                                                ? request.getDeviceName() : "Unknown Device")
                                        .isActive(true)
                                        .counter(verifie.compteurInitial())
                                        .registeredAt(LocalDateTime.now())
                                        .build()))))
                // Le challenge est a usage unique : le laisser vivre jusqu'a son
                // expiration autoriserait un second enregistrement avec la meme
                // preuve de fraicheur.
                .flatMap(credential -> reactiveRedisTemplate.delete(cleChallenge)
                        .thenReturn(credential))
                // La traduction en DTO est faite ICI, et non par l'appelant.
                //
                // L'entite porte publicKey, aaguid et counter. La rendre telle
                // quelle confiait a chaque appelant le soin de ne pas les
                // divulguer : le controleur le faisait, mais rien ne l'y
                // obligeait.
                .map(credential -> BiometricRegistrationResponse.builder()
                        .credentialId(credential.getCredentialId())
                        .biometricType(credential.getBiometricType())
                        .deviceName(credential.getDeviceName())
                        .registeredAt(credential.getRegisteredAt())
                        .message(AppConstants.BIOMETRIE_ENREGISTREMENT_REUSSI)
                        .success(true)
                        .build());
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<BiometricVerificationResponse> initiateAuthenticationParIdentifiant(
            String identifiant) {
        return IdentifiantDeCompte.resoudre(userRepository, identifiant)
                // Identifiant inconnu : MEME erreur qu'un compte sans
                // authentificateur, formulee par initiateAuthentication. Deux
                // reponses distinctes enumereraient les comptes.
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.INVALID_INPUT,
                        AppConstants.BIOMETRIE_INDISPONIBLE)))
                .flatMap(user -> initiateAuthentication(user.getId()));
    }

    /**
     * Verifie l'attestation, en isolant l'appel bloquant de {@code webauthn4j}.
     *
     * <p>La verification est du calcul cryptographique synchrone : elle est
     * portee sur {@code boundedElastic} pour ne pas retenir un thread de
     * l'EventLoop, conformement a la politique non bloquante du depot.
     */
    private Mono<WebAuthnVerifier.ResultatEnregistrement> verifierAttestation(
            BiometricRegistrationRequest request, String challenge) {
        return Mono.fromCallable(() -> webAuthnVerifier.verifierEnregistrement(
                        request.getAttestationObject(), request.getClientDataJSON(), challenge))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(WebAuthnVerificationException.class, e -> new AuthException(
                        ErrorCode.INVALID_INPUT, "Attestation biométrique invalide"));
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<BiometricVerificationResponse> initiateAuthentication(String userId) {
        log.info("Initiation de l'authentification biométrique pour l'utilisateur: {}", userId);

        return requireUser(userId)
                .then(biometricCredentialRepository.findByUserIdAndIsActive(userId, true).hasElements())
                .flatMap(aDesCredentials -> {
                    if (!Boolean.TRUE.equals(aDesCredentials)) {
                        return Mono.error(new AuthException(ErrorCode.INVALID_INPUT,
                                "Aucune credential biométrique active trouvée"));
                    }

                    String challenge = generateChallenge();

                    // LE CHALLENGE EST ENFIN ENREGISTRE.
                    //
                    // La version precedente appelait generateChallenge() en
                    // ligne, renvoyait la valeur, et ne la conservait nulle
                    // part. Rien ne pouvait donc verifier qu'une assertion
                    // repondait bien a un challenge emis par ce serveur : la
                    // fraicheur n'etait pas prouvee, et une assertion capturee
                    // restait rejouable indefiniment.
                    //
                    // Le parcours d'enregistrement, lui, stockait deja le sien.
                    // L'asymetrie entre les deux etait involontaire.
                    return reactiveRedisTemplate.opsForValue()
                            .set(cleChallengeAuthentification(userId), challenge, CHALLENGE_EXPIRATION)
                            .thenReturn(BiometricVerificationResponse.builder()
                                    .success(true)
                                    .challenge(challenge)
                                    .message("Challenge généré. Veuillez compléter l'authentification biométrique.")
                                    .build());
                });
    }

    @Override
    @Transactional
    public Mono<BiometricVerificationResponse> verifyAuthentication(BiometricVerificationRequest request) {
        if (!authentificationActivee) {
            log.warn("Authentification biometrique refusee : parcours desactive par configuration");
            return Mono.error(new AuthException(ErrorCode.INVALID_CREDENTIALS,
                    "Authentification biometrique indisponible"));
        }

        log.info("Vérification de l'authentification biométrique");

        return biometricCredentialRepository.findByCredentialId(request.getCredentialId())
                // Refus UNIFORME : le meme message que pour une credential
                // desactivee ou une signature invalide. Distinguer ces cas
                // apprendrait a l'appelant quels credentialId existent.
                .switchIfEmpty(Mono.error(refus()))
                .flatMap(credential -> Boolean.TRUE.equals(credential.getIsActive())
                        ? Mono.just(credential)
                        : Mono.<BiometricCredential>error(refus()))
                .flatMap(credential -> requireUser(credential.getUserId())
                        .flatMap(user -> Boolean.TRUE.equals(user.getIsActive())
                                ? verifierAssertionEtEmettre(credential, user, request)
                                : Mono.<BiometricVerificationResponse>error(
                                        new AuthException(ErrorCode.ACCOUNT_DISABLED))));
    }

    private Mono<BiometricVerificationResponse> verifierAssertionEtEmettre(
            BiometricCredential credential, User user, BiometricVerificationRequest request) {

        String cleChallenge = cleChallengeAuthentification(user.getId());

        return reactiveRedisTemplate.opsForValue().get(cleChallenge)
                // Sans challenge enregistre, aucune fraicheur n'est prouvable :
                // on refuse plutot que d'accepter une assertion dont on ne sait
                // pas a quoi elle repond.
                .switchIfEmpty(Mono.error(refus()))
                .flatMap(challenge -> Mono.fromCallable(() -> webAuthnVerifier.verifierAssertion(
                                credential.getPublicKey(),
                                credential.getCounter() == null ? 0L : credential.getCounter(),
                                request.getCredentialId(),
                                request.getUserHandle(),
                                request.getAuthenticatorData(),
                                request.getClientDataJSON(),
                                request.getSignature(),
                                challenge))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorMap(WebAuthnVerificationException.class, e -> refus()))
                .flatMap(nouveauCompteur -> {
                    // Le compteur rendu par webauthn4j est celui de l'assertion,
                    // et non un increment local. C'est ce qui permet de detecter
                    // un authentificateur clone : la bibliotheque refuse un
                    // compteur qui ne progresse pas, mais encore faut-il
                    // persister celui qu'elle a valide.
                    credential.setCounter(nouveauCompteur);
                    credential.setLastUsedAt(LocalDateTime.now());

                    return biometricCredentialRepository.save(credential)
                            // Challenge a usage unique : le supprimer empeche de
                            // rejouer la meme assertion pendant sa duree de vie.
                            .then(reactiveRedisTemplate.delete(cleChallenge))
                            .then(Mono.defer(() -> buildVerificationResponse(user, credential)));
                });
    }

    /**
     * Refus uniforme de l'authentification biometrique.
     *
     * <p>Credential inconnue, credential desactivee, challenge absent et
     * signature invalide donnent le <b>meme</b> message. L'ancienne version les
     * distinguait, ce qui permettait d'apprendre quels {@code credentialId}
     * existent — un oracle d'enumeration.
     */
    private AuthException refus() {
        return new AuthException(ErrorCode.INVALID_CREDENTIALS, "Vérification biométrique échouée");
    }

    private Mono<BiometricVerificationResponse> buildVerificationResponse(User user,
                                                                          BiometricCredential credential) {
        // Le jeton de rafraichissement est un VRAI jeton, distinct du jeton
        // d'acces.
        //
        // La version precedente ecrivait `.refreshToken(accessToken)` : le
        // meme jeton servait aux deux usages. Consequences : il portait la
        // duree de vie courte d'un jeton d'acces, il n'etait pas enregistre
        // dans la table des jetons de rafraichissement donc ni revocable ni
        // tracable, et un jeton presente comme « de rafraichissement » etait
        // directement utilisable comme jeton d'acces.
        return jwtService.generateToken(user)
                .flatMap(accessToken -> refreshTokenService
                        .createRefreshToken(user, null, null)
                        .map(jetonRafraichissement -> {
                            log.info("Authentification biométrique réussie pour l'utilisateur: {}",
                                    user.getId());
                            return BiometricVerificationResponse.builder()
                                    .success(true)
                                    .message("Authentification biométrique réussie")
                                    .credentialId(credential.getCredentialId())
                                    .accessToken(accessToken)
                                    // Le SECRET, pas l'empreinte : voir
                                    // AuthResponseMapper.
                                    .refreshToken(
                                            jetonRafraichissement.valeurAEnvoyerAuClient())
                                    .expiresIn(jwtService.getExpirationMs())
                                    .build();
                        }));
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<BiometricCredentialResponse> getUserCredentials(String userId) {
        log.info("Récupération des credentials biométriques pour l'utilisateur: {}", userId);
        return biometricCredentialRepository.findByUserIdAndIsActive(userId, true)
                .map(BiometricCredentialResponse::de);
    }

    @Override
    @Transactional
    public Mono<Void> deleteCredential(String userId, String credentialId) {
        log.info("Suppression de la credential biométrique {} pour l'utilisateur: {}", credentialId, userId);

        return biometricCredentialRepository.findByCredentialId(credentialId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.INVALID_INPUT,
                        "Credential biométrique non trouvée")))
                .flatMap(credential -> {
                    // Contrôle de propriété : une credential ne peut être supprimée
                    // que par son porteur.
                    if (!credential.getUserId().equals(userId)) {
                        return Mono.error(new AuthException(ErrorCode.INVALID_INPUT,
                                "Vous n'êtes pas autorisé à supprimer cette credential"));
                    }
                    credential.setIsActive(false);
                    return biometricCredentialRepository.save(credential);
                })
                .doOnSuccess(saved -> log.info("Credential biométrique {} désactivée avec succès", credentialId))
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> deleteAllUserCredentials(String userId) {
        log.info("Suppression de toutes les credentials biométriques pour l'utilisateur: {}", userId);

        return biometricCredentialRepository.findByUserIdAndIsActive(userId, true)
                .doOnNext(credential -> credential.setIsActive(false))
                .flatMap(biometricCredentialRepository::save)
                .count()
                .doOnSuccess(count -> log.info(
                        "{} credentials biométriques désactivées pour l'utilisateur: {}", count, userId))
                .then();
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<Boolean> hasActiveCredentials(String userId) {
        return biometricCredentialRepository.countByUserIdAndIsActive(userId, true)
                .map(count -> count > 0);
    }

    private Mono<User> requireUser(String userId) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)));
    }

    private String generateChallenge() {
        byte[] challengeBytes = new byte[CHALLENGE_LENGTH];
        new SecureRandom().nextBytes(challengeBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes);
    }

    /** Clef Redis du challenge d'enregistrement, par utilisateur. */
    private static String cleChallengeEnregistrement(String userId) {
        return BIOMETRIC_CHALLENGE_PREFIX + "register:" + userId;
    }

    /** Clef Redis du challenge d'authentification, par utilisateur. */
    private static String cleChallengeAuthentification(String userId) {
        return BIOMETRIC_CHALLENGE_PREFIX + "authenticate:" + userId;
    }
}
