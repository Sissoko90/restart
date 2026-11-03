package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.SessionResponse;
import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.RefreshTokenRepository;
import com.abdatytch.auth_service.service.ISessionService;
import com.abdatytch.commons.security.EmpreinteDeSecret;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Objects;

/**
 * Sessions ouvertes : voir {@link ISessionService} pour le pourquoi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionServiceImpl implements ISessionService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Override
    public Flux<SessionResponse> sessions(String userId, String jetonCourant) {
        return refreshTokenRepository.findByUserIdAndIsRevokedFalse(userId)
                // Une session echue est toujours en base jusqu'au nettoyage
                // periodique. La montrer ferait croire a un acces ouvert qui
                // n'existe plus, et pousserait a « fermer » une session deja
                // morte — en laissant la vraie ouverte.
                .filter(session -> !estEchue(session))
                .sort(Comparator.comparing(RefreshToken::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(session -> SessionResponse.de(session,
                        estLaCourante(session, jetonCourant)));
    }

    @Override
    public Mono<Void> fermer(String userId, String sessionId) {
        return refreshTokenRepository.findById(sessionId)
                // L'appartenance est verifiee, et le refus est un 404.
                //
                // Rendre 403 sur la session d'autrui confirmerait qu'elle
                // existe : en parcourant des identifiants, on denombrerait les
                // sessions de la plateforme. C'est la regle de ce depot, deja
                // appliquee sur la passerelle.
                .filter(session -> userId.equals(session.getUserId()))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Fermeture refusee : session {} absente ou d'un autre compte "
                            + "que {}", sessionId, userId);
                    return Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND));
                }))
                .flatMap(this::revoquer)
                .then();
    }

    @Override
    public Mono<Long> fermerToutes(String userId, boolean conserverLaCourante,
                                   String jetonCourant) {
        if (conserverLaCourante && (jetonCourant == null || jetonCourant.isBlank())) {
            // Sans le jeton de l'appelant, « conserver la courante » ne peut
            // rien conserver : l'operation fermerait TOUT en ayant promis le
            // contraire, et deconnecterait la personne qui demandait justement
            // a rester connectee. Mieux vaut refuser.
            return Mono.error(new AuthException(ErrorCode.INVALID_REQUEST));
        }

        return refreshTokenRepository.findByUserIdAndIsRevokedFalse(userId)
                .filter(session -> !(conserverLaCourante
                        && estLaCourante(session, jetonCourant)))
                .flatMap(this::revoquer)
                .count()
                .doOnSuccess(nombre -> log.info(
                        "{} session(s) fermee(s) pour {} (courante conservee : {})",
                        nombre, userId, conserverLaCourante));
    }

    private Mono<RefreshToken> revoquer(RefreshToken session) {
        session.setIsRevoked(true);
        session.setRevokedAt(LocalDateTime.now());
        return refreshTokenRepository.save(session);
    }

    private static boolean estEchue(RefreshToken session) {
        return session.getExpiresAt() != null
                && session.getExpiresAt().isBefore(LocalDateTime.now());
    }

    /**
     * La session de l'appelant.
     *
     * <p>La comparaison porte sur des <b>empreintes</b> : la base ne garde plus
     * le secret, seulement son SHA-256. On calcule donc l'empreinte du jeton
     * presente pour la confronter a celle de la ligne. Comparer le jeton
     * presente a la valeur stockee — ce que faisait ce code — ne rendrait plus
     * jamais vrai, et « conserver la session courante » fermerait <b>toutes</b>
     * les sessions, y compris celle de l'appelant.
     *
     * <p>Comparaison par {@link Objects#equals} et non par {@code ==} : les deux
     * chaines viennent de sources differentes — l'une de la base, l'autre du
     * corps de la requete — et ne sont jamais le meme objet.
     *
     * <p>Une comparaison en temps constant n'est pas necessaire ici : ce qui est
     * compare est une empreinte, et la connaitre ne permet pas de
     * s'authentifier — il faudrait son antecedent. Et cette methode ne decide
     * pas d'un acces, seulement de quelle session echappe a une fermeture
     * demandee par son proprietaire.
     *
     * <p>Un {@code jetonCourant} nul ne marque aucune session, ce qui est le
     * comportement voulu : l'appelant ne le presente pas toujours.
     */
    private static boolean estLaCourante(RefreshToken session, String jetonCourant) {
        return jetonCourant != null && !jetonCourant.isBlank()
                && Objects.equals(session.getEmpreinte(),
                        EmpreinteDeSecret.empreinte(jetonCourant));
    }
}
