package com.abdatytch.interface_service.service;

import com.abdatytch.interface_service.entity.User;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Service JWT pour les développeurs
 * Génère et valide les tokens JWT signés avec les clés RSA de interface-service
 */
public interface IDevJwtService {
    
    /**
     * Génère un token JWT pour un développeur
     */
    String generateToken(User developer);
    
    /**
     * Génère un token de rafraîchissement
     */
    String generateRefreshToken(User developer);
    
    /**
     * Valide un token JWT de manière réactive
     */
    Mono<Boolean> validateTokenReactive(String token);
    
    /**
     * Extrait l'ID du développeur depuis le token
     */
    String getDeveloperIdFromToken(String token);
    
    /**
     * Extrait l'email du développeur depuis le token
     */
    String getEmailFromToken(String token);
    
    /**
     * Extrait le rôle depuis le token
     */
    String getRoleFromToken(String token);
    
    /**
     * Extrait l'ID du token (jti)
     */
    String getTokenIdFromToken(String token);
    
    /**
     * Extrait tous les claims du token
     */
    Map<String, Object> extractAllClaims(String token);
    
    /**
     * Révoque un token
     */
    void revokeToken(String token);
    
    /**
     * Révoque tous les tokens d'un développeur
     */
    void revokeAllDeveloperTokens(String developerId);
    
    /**
     * Durée d'expiration du token en ms
     */
    long getExpirationMs();
    
    /**
     * Durée d'expiration du refresh token en ms
     */
    long getRefreshExpirationMs();
    
    // ==========================================
    // Méthodes pour les utilisateurs finaux (Auth-as-a-Service)
    // Utilisées par auth-service pour signer les JWT des users finaux
    // ==========================================
    
    /**
     * Génère un token JWT pour un utilisateur final (appelé par auth-service)
     * @param userId ID de l'utilisateur final
     * @param projectId ID du projet du développeur
     * @param email Email de l'utilisateur
     * @param username Nom d'utilisateur
     * @param roles Rôles de l'utilisateur
     * @param customClaims Claims personnalisés
     * @return Token JWT signé
     */
    String generateTokenForUser(String userId, String projectId, String email, 
                                String username, java.util.List<String> roles,
                                Map<String, Object> customClaims);
    
    /**
     * Génère un refresh token pour un utilisateur final
     */
    String generateRefreshTokenForUser(String userId, String projectId);

    /**
     * Génère un jeton de <b>maintenance</b>, dont le type est posé par
     * l'autorité et non par l'appelant.
     *
     * <h3>Pourquoi cette méthode existe</h3>
     *
     * {@code MaintenanceController} obtenait ce jeton en glissant
     * {@code type: "maintenance"} dans les {@code customClaims} de
     * {@link #generateTokenForUser}, qui les appliquait <b>en dernier</b> et
     * écrasait donc le type décidé par l'autorité.
     *
     * <p>Cela fonctionnait, mais au prix d'une faille de conception : si un
     * appelant peut fixer {@code type}, il peut aussi le fixer à
     * {@code "user"} sur un jeton de rafraîchissement, ou à
     * {@code "maintenance"} sur n'importe quoi. Or ce claim est la seule chose
     * qui distingue un jeton d'accès d'un jeton de longue durée, et c'est
     * {@code MaintenanceJwtFilter} qui s'en sert pour garder la maintenance.
     *
     * <p>L'autorité refuse désormais toute réécriture de ses invariants. Les
     * types légitimes passent par des méthodes nommées — celle-ci en est une.
     */
    String generateMaintenanceToken(String userId, String email, String username);
    
    /**
     * Extrait l'ID utilisateur du token
     */
    String extractUserId(String token);
    
    /**
     * Extrait un claim spécifique du token
     */
    String extractClaim(String token, String claimName);
    
    /**
     * Durée d'expiration du access token en secondes
     */
    long getAccessTokenExpiration();
}
