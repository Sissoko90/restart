package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.RefreshTokenRepository;
import com.abdatytch.auth_service.service.IRefreshTokenService;
import com.abdatytch.commons.security.EmpreinteDeSecret;
import com.abdatytch.commons.security.SecretMasking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Gestion réactive des refresh tokens.
 *
 * <p>R2DBC ne gère pas les relations : le token porte désormais {@code userId}
 * et non plus l'entité {@code User}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenServiceImpl implements IRefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Override
    @Transactional
    public Mono<RefreshToken> createRefreshToken(User user, String ipAddress, String userAgent) {
        log.info(ServiceConstants.LOG_INFO_CREATING_REFRESH_TOKEN, user.getId());

        // Le secret est tire ICI et n'est jamais ecrit : seule son empreinte
        // part en base. C'est la seule occasion de le connaitre, et c'est
        // pourquoi il voyage sur le champ transitoire valeurEnClair.
        String secret = EmpreinteDeSecret.nouveauSecret();

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(user.getId())
                .empreinte(EmpreinteDeSecret.empreinte(secret))
                .expiresAt(LocalDateTime.now().plusDays(AppConstants.REFRESH_TOKEN_VALIDITY_DAYS))
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build();

        return refreshTokenRepository.save(refreshToken)
                // Repose le secret APRES l'enregistrement. save() rend l'entite
                // telle que la base la connait, donc sans le champ transitoire :
                // le poser avant serait perdu, et l'appelant transmettrait un
                // jeton vide au client.
                .map(enregistre -> {
                    enregistre.setValeurEnClair(secret);
                    return enregistre;
                });
    }

    @Override
    @Transactional
    public Mono<RefreshToken> validateAndGetRefreshToken(String token) {
        // Une EMPREINTE, jamais le jeton. Un jeton de rafraichissement dans les
        // journaux permet de renouveler une session indefiniment : c'est un
        // justificatif de longue duree, pas un identifiant.
        log.info(ServiceConstants.LOG_INFO_VALIDATING_REFRESH_TOKEN,
                SecretMasking.masquer(token));

        if (estVide(token)) {
            // EmpreinteDeSecret.empreinte leve sur une valeur vide, et cette
            // levee traverserait la chaine en 500. Or le jeton vient de
            // l'appelant : une requete sans jeton doit se REFUSER, pas produire
            // une erreur serveur. findByToken(null) rendait un Mono vide, donc
            // un TOKEN_INVALID ; le comportement est conserve ici explicitement.
            return Mono.error(new AuthException(ErrorCode.TOKEN_INVALID));
        }

        // La base ne porte que l'empreinte : c'est elle qu'on cherche, pas le
        // jeton presente. Une recherche par jeton ne rendrait plus jamais rien,
        // et chaque rafraichissement echouerait en TOKEN_INVALID.
        return refreshTokenRepository.findByEmpreinte(EmpreinteDeSecret.empreinte(token))
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.TOKEN_INVALID)))
                .flatMap(refreshToken -> {
                    // Distinguer révocation et expiration : le client doit savoir
                    // s'il peut se réauthentifier ou si le token a été invalidé.
                    if (Boolean.TRUE.equals(refreshToken.getIsRevoked())) {
                        log.warn(ServiceConstants.LOG_WARN_REFRESH_TOKEN_REVOKED,
                                SecretMasking.masquer(token));
                        return Mono.error(new AuthException(ErrorCode.TOKEN_INVALID));
                    }
                    if (refreshToken.isExpired()) {
                        // Ces deux branches utilisaient « Tentative de renvoi trop
                        // rapide pour: {} », qui ne decrit ni l'un ni l'autre. Le
                        // message faux est la raison pour laquelle la fuite est
                        // passee inaperçue.
                        log.warn(ServiceConstants.LOG_WARN_REFRESH_TOKEN_EXPIRED,
                                SecretMasking.masquer(token));
                        return Mono.error(new AuthException(ErrorCode.REFRESH_TOKEN_EXPIRED));
                    }
                    return Mono.just(refreshToken);
                });
    }

    @Override
    @Transactional
    public Mono<Void> revokeToken(String token) {
        log.info(ServiceConstants.LOG_INFO_REVOKING_REFRESH_TOKEN,
                SecretMasking.masquer(token));

        if (estVide(token)) {
            return Mono.error(new AuthException(ErrorCode.TOKEN_INVALID));
        }

        return refreshTokenRepository.findByEmpreinte(EmpreinteDeSecret.empreinte(token))
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.TOKEN_INVALID)))
                .flatMap(refreshToken -> {
                    refreshToken.setIsRevoked(true);
                    refreshToken.setRevokedAt(LocalDateTime.now());
                    return refreshTokenRepository.save(refreshToken);
                })
                .then();
    }

    /**
     * Jeton absent ou blanc.
     *
     * <p>Teste avant tout calcul d'empreinte : {@code empreinte("")} leve, et
     * cette levee deviendrait un 500 sur une entree entierement controlee par
     * l'appelant.
     */
    private static boolean estVide(String token) {
        return token == null || token.isBlank();
    }

    @Override
    @Transactional
    public Mono<Void> revokeAllUserTokens(User user) {
        log.info(ServiceConstants.LOG_INFO_REVOKING_REFRESH_TOKEN, user.getId());

        return refreshTokenRepository.findByUserIdAndIsRevokedFalse(user.getId())
                .doOnNext(token -> {
                    token.setIsRevoked(true);
                    token.setRevokedAt(LocalDateTime.now());
                })
                .flatMap(refreshTokenRepository::save)
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> cleanupExpiredTokens() {
        log.info(ServiceConstants.LOG_INFO_RESENDING_CODE, "system");
        return refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}
