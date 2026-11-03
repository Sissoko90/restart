package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de chiffrement/déchiffrement des documents
 * Utilise AES-256-GCM pour sécurité maximale
 */
public interface EncryptionService {

    /**
     * Chiffre un document avant stockage
     * 
     * @param content Contenu du document en clair
     * @param userId ID utilisateur (utilisé pour dériver la clé)
     * @param documentId ID du document
     * @return Contenu chiffré
     */
    Mono<byte[]> encryptDocument(byte[] content, String userId, String documentId);

    /**
     * Déchiffre un document après récupération
     * 
     * @param encryptedContent Contenu chiffré
     * @param userId ID utilisateur
     * @param documentId ID du document
     * @return Contenu en clair
     */
    Mono<byte[]> decryptDocument(byte[] encryptedContent, String userId, String documentId);

    /**
     * Génère un hash SHA-256 du contenu pour détection de duplicatas
     * 
     * @param content Contenu du document
     * @return Hash SHA-256 en hexadécimal
     */
    Mono<String> generateFileHash(byte[] content);
}
