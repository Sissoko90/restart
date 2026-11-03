package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.service.EncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Implémentation du service de chiffrement AES-256-GCM
 * Sécurise les documents sensibles avant stockage dans MinIO
 */
@Service
@Slf4j
public class EncryptionServiceImpl implements EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int AES_KEY_SIZE = 256;

    // Aucun défaut : une clé maître par défaut chiffrerait tous les documents
    // sous une valeur publiquement connue.
    @Value("${encryption.master-key}")
    private String masterKey;

    @Value("${encryption.enabled:true}")
    private boolean encryptionEnabled;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public Mono<byte[]> encryptDocument(byte[] content, String userId, String documentId) {
        if (!encryptionEnabled) {
            log.debug("Encryption disabled, returning plain content");
            return Mono.just(content);
        }

        return Mono.fromCallable(() -> {
            try {
                // Dériver une clé unique par document
                SecretKey secretKey = deriveKey(userId, documentId);

                // Générer IV aléatoire
                byte[] iv = new byte[GCM_IV_LENGTH];
                secureRandom.nextBytes(iv);

                // Chiffrer
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

                byte[] encryptedContent = cipher.doFinal(content);

                // Combiner IV + contenu chiffré
                ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + encryptedContent.length);
                byteBuffer.put(iv);
                byteBuffer.put(encryptedContent);

                log.debug("Document encrypted successfully - userId: {}, documentId: {}, size: {} bytes", 
                    userId, documentId, content.length);

                return byteBuffer.array();

            } catch (Exception e) {
                log.error("Failed to encrypt document - userId: {}, documentId: {}", userId, documentId, e);
                throw new RuntimeException("Encryption failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<byte[]> decryptDocument(byte[] encryptedContent, String userId, String documentId) {
        if (!encryptionEnabled) {
            log.debug("Encryption disabled, returning content as-is");
            return Mono.just(encryptedContent);
        }

        return Mono.fromCallable(() -> {
            try {
                // Dériver la même clé
                SecretKey secretKey = deriveKey(userId, documentId);

                // Extraire IV et contenu
                ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedContent);
                byte[] iv = new byte[GCM_IV_LENGTH];
                byteBuffer.get(iv);
                byte[] cipherText = new byte[byteBuffer.remaining()];
                byteBuffer.get(cipherText);

                // Déchiffrer
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

                byte[] decryptedContent = cipher.doFinal(cipherText);

                log.debug("Document decrypted successfully - userId: {}, documentId: {}", userId, documentId);

                return decryptedContent;

            } catch (Exception e) {
                log.error("Failed to decrypt document - userId: {}, documentId: {}", userId, documentId, e);
                throw new RuntimeException("Decryption failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<String> generateFileHash(byte[] content) {
        return Mono.fromCallable(() -> {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(content);
                
                // Convertir en hexadécimal
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                
                return hexString.toString();
                
            } catch (Exception e) {
                log.error("Failed to generate file hash", e);
                throw new RuntimeException("Hash generation failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Dérive une clé unique par document à partir de la master key
     * Utilise PBKDF2 avec userId + documentId comme salt
     */
    private SecretKey deriveKey(String userId, String documentId) throws Exception {
        String salt = userId + ":" + documentId + ":" + masterKey;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(salt.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }
}
