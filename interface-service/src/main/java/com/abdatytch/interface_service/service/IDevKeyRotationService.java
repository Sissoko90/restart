package com.abdatytch.interface_service.service;

import reactor.core.publisher.Mono;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;

/**
 * Service de rotation des clés RSA pour JWT des développeurs
 * 
 * Stratégie de rotation:
 * 1. Générer une nouvelle paire de clés
 * 2. Commencer à signer avec la nouvelle clé privée
 * 3. Garder l'ancienne clé publique pour valider les tokens existants
 * 4. Après expiration de tous les anciens tokens, supprimer l'ancienne clé
 */
public interface IDevKeyRotationService {
    
    /**
     * Récupère la clé privée actuelle pour signer les nouveaux tokens
     */
    PrivateKey getCurrentPrivateKey();
    
    /**
     * Récupère la clé publique actuelle
     */
    PublicKey getCurrentPublicKey();
    
    /**
     * Récupère toutes les clés publiques valides (actuelle + anciennes non expirées)
     */
    List<PublicKey> getAllValidPublicKeys();

    /**
     * Récupère toutes les clés publiques valides indexées par leur Key ID (kid).
     * Source unique de vérité pour l'endpoint JWKS : le `kid` publié doit être
     * exactement celui inscrit dans l'en-tête des tokens signés.
     */
    java.util.Map<String, PublicKey> getAllValidPublicKeysById();
    
    /**
     * Récupère l'ID de la clé actuelle (Key ID - kid)
     */
    String getCurrentKeyId();
    
    /**
     * Effectue une rotation des clés
     */
    Mono<Void> rotateKeys();
    
    /**
     * Vérifie si une rotation est nécessaire
     */
    boolean isRotationNeeded();
    
    /**
     * Récupère une clé publique par son ID
     */
    PublicKey getPublicKeyById(String keyId);
    
    /**
     * Nettoie les anciennes clés expirées
     */
    Mono<Integer> cleanupExpiredKeys();
}
