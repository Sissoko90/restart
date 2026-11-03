package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.response.SessionResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Les sessions ouvertes d'un compte, vues et fermees par son proprietaire.
 *
 * <h2>Pourquoi cette surface manquait</h2>
 *
 * Le depot savait deja tout faire : {@code findByUserIdAndIsRevokedFalse} et
 * {@code revokeAllUserTokens} existaient et etaient utilises — mais uniquement
 * en interne, a la reinitialisation du mot de passe. Aucun endpoint ne les
 * exposait.
 *
 * <p>Consequence concrete : une personne qui se connecte depuis un appareil
 * public, ou qui soupçonne un acces, n'a aucun moyen de voir ses sessions ni
 * d'en fermer une. La seule sortie etait de changer de mot de passe — ce qui
 * ferme tout, y compris les sessions legitimes, et demande un SMS puisque le
 * changement authentifie n'existait pas non plus.
 *
 * <h2>Ce qu'une session peut et ne peut pas</h2>
 *
 * Fermer une session revoque son jeton de rafraichissement. Le jeton d'acces
 * deja emis reste valable jusqu'a son echeance : c'est la nature d'un JWT, et le
 * raccourcir demanderait une consultation en base a chaque requete, ce que la
 * politique de cette plateforme refuse. Ce qui est coupe, c'est le
 * renouvellement — l'acces s'eteint a l'echeance du jeton courant.
 *
 * <p>Cette limite doit etre dite a l'appelant, sinon « session fermee » promet
 * plus que ce qui se produit.
 */
public interface ISessionService {

    /**
     * Sessions ouvertes du compte, la plus recente d'abord.
     *
     * @param userId compte, pris du jeton et jamais d'un parametre
     * @param jetonCourant jeton de rafraichissement de l'appelant, pour marquer
     *        sa propre session. Peut etre nul : l'appelant ne le presente pas
     *        toujours, et son absence ne doit rien empecher
     */
    Flux<SessionResponse> sessions(String userId, String jetonCourant);

    /**
     * Ferme une session designee par son identifiant.
     *
     * <p>L'appartenance est verifiee : fermer la session d'autrui en changeant
     * un identifiant dans l'URL est l'IDOR le plus direct. Le refus rend
     * <b>404</b> et non 403, conformement a la regle de ce depot — un 403
     * confirmerait que la session existe.
     */
    Mono<Void> fermer(String userId, String sessionId);

    /**
     * Ferme toutes les sessions du compte.
     *
     * @param conserverLaCourante vrai pour garder la session de l'appelant
     *        ouverte. Les deux usages existent et ne se confondent pas :
     *        « deconnecter mes autres appareils » doit laisser la personne
     *        connectee, « tout fermer » apres un soupçon doit la deconnecter
     *        aussi
     * @param jetonCourant jeton de l'appelant, requis si
     *        {@code conserverLaCourante} est vrai
     * @return le nombre de sessions fermees
     */
    Mono<Long> fermerToutes(String userId, boolean conserverLaCourante, String jetonCourant);
}
