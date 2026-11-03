package com.abdatytch.auth_service.util;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.exception.AuthException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

/**
 * Identifiant de l'appelant, lu <b>une seule fois</b>, la ou il a ete etabli.
 *
 * <h2>Le defaut que cela remplace</h2>
 *
 * Plusieurs controleurs relisaient l'en-tete {@code Authorization} et
 * redemandaient l'identifiant a {@code IJwtService}, alors que
 * {@code JwtAuthenticationWebFilter} venait de le faire et avait pose
 * l'{@code Authentication} dans le contexte. Deux sources pour une meme
 * identite — le motif qui a deja produit plusieurs defauts dans ce depot — avec
 * trois consequences :
 *
 * <ol>
 *   <li>la verification du jeton etait faite deux fois, dont une sans tenir
 *       compte de la revocation que le filtre consulte ;</li>
 *   <li>l'absence de jeton levait une {@code IllegalArgumentException}, donc
 *       <b>400</b> au lieu de 401 — un client ne pouvait pas distinguer « mal
 *       forme » de « non authentifie » ;</li>
 *   <li>un jeton temporaire OAuth2 passait le decodage manuel sans que son type
 *       soit controle, alors que le filtre, lui, restreint ce type au seul
 *       chemin de completion de profil.</li>
 * </ol>
 *
 * <h2>Pourquoi 401 et non une erreur technique</h2>
 *
 * Ces chemins sont derriere {@code .authenticated()} : le contexte est garni
 * avant que le controleur ne tourne. Un contexte vide signale donc une regle
 * d'autorisation manquante, et {@code AUTH_1010} est la reponse juste pour le
 * client — sans lui faire croire que sa requete etait malformee.
 */
public final class AppelantAuthentifie {

    private AppelantAuthentifie() {
    }

    /**
     * @return l'identifiant pose par {@code JwtAuthenticationWebFilter}, ou une
     *         erreur {@code AUTH_1010} si le contexte est vide
     */
    public static Mono<String> identifiant() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getName)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.SESSION_ABSENTE)));
    }
}
