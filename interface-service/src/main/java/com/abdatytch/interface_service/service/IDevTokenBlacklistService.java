package com.abdatytch.interface_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de blacklist des tokens JWT pour les développeurs
 */
public interface IDevTokenBlacklistService {
    
    /**
     * Révoque un token spécifique
     */
    Mono<Void> revokeToken(String tokenId, long expirationMs);
    
    /**
     * Vérifie si un token est révoqué
     */
    Mono<Boolean> isTokenRevoked(String tokenId);
    
    /**
     * Révoque tous les tokens d'un développeur émis AVANT maintenant.
     *
     * <p>Enregistre l'instant de la révocation. Les jetons émis après restent
     * valides : voir {@link #revocationGlobalePosterieureA}.
     */
    Mono<Void> revokeAllDeveloperTokens(String developerId);

    /**
     * Une révocation globale frappe-t-elle un jeton émis à cet instant ?
     *
     * <h3>Le défaut corrigé</h3>
     *
     * La méthode s'appelait {@code hasDeveloperRevokedTokens(String)} et ne
     * faisait qu'un {@code hasKey} : elle <b>ignorait l'horodatage</b> que
     * {@code revokeAllDeveloperTokens} enregistre pourtant, avec une durée de
     * vie de sept jours.
     *
     * <p>Conséquence : révoquer les jetons d'un développeur l'aurait verrouillé
     * <b>sept jours</b>, reconnexion comprise — tout jeton neuf était rejeté
     * tant que la clé existait. L'horodatage était écrit pour éviter exactement
     * cela, et n'était jamais relu.
     *
     * <p>Le défaut n'était pas actif : {@code revokeAllDeveloperTokens} n'avait
     * aucun appelant hors de sa propre façade. Mais le consommateur, lui,
     * tournait à <b>chaque</b> validation — le brancher aurait provoqué la
     * panne. C'est le motif de ce dépôt : un mécanisme à moitié construit, dont
     * le côté qui lit est vivant et le côté qui écrit est mort.
     *
     * @param developerId identifiant du développeur, le sujet du jeton
     * @param emisLe      instant d'émission du jeton — son claim {@code iat}.
     *                    {@code null} signifie « inconnu », et la réponse est
     *                    alors <b>true</b> : un jeton dont on ne peut pas
     *                    prouver qu'il postdate la révocation doit être refusé.
     * @return {@code true} si une révocation existe et qu'elle est postérieure
     *         à l'émission, donc si le jeton est frappé
     */
    Mono<Boolean> revocationGlobalePosterieureA(String developerId, java.time.Instant emisLe);
}
