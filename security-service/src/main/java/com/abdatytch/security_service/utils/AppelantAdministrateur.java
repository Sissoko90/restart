package com.abdatytch.security_service.utils;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.exception.SecurityPolicyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

/**
 * Identifiant de l'administrateur appelant, lu la ou il a ete etabli.
 *
 * <h2>Pourquoi l'auteur ne vient jamais du corps de la requete</h2>
 *
 * Chaque decision de ce service porte son auteur en base : qui a change un
 * seuil, qui a bloque une adresse, qui a coupe un service. Si cet auteur venait
 * du corps, n'importe quel administrateur pourrait signer ses actes du nom d'un
 * autre — et le journal d'audit, qui est la raison d'etre de ces colonnes,
 * deviendrait une source de desinformation plutot qu'une preuve.
 *
 * <p>Il vient donc du jeton, pose par {@code JwtAuthenticationWebFilter}. Meme
 * forme que {@code AppelantAuthentifie} dans auth-service, et pour la meme
 * raison : une seule source pour une seule identite.
 */
public final class AppelantAdministrateur {

    private AppelantAdministrateur() {
    }

    /**
     * @return l'identifiant de l'administrateur, ou une erreur si le contexte est
     *         vide. Ce chemin est derriere une regle d'autorisation : un contexte
     *         vide signale une regle manquante, pas un appel anonyme legitime.
     */
    public static Mono<String> identifiant() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getName)
                .filter(nom -> nom != null && !nom.isBlank())
                .switchIfEmpty(Mono.error(new SecurityPolicyException(
                        SecurityConstants.APPELANT_NON_IDENTIFIE)));
    }
}
