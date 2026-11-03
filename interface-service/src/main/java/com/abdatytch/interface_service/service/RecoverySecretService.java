package com.abdatytch.interface_service.service;

import java.security.*;
import java.util.Base64;
import javax.crypto.Cipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service pour la gestion du recovery secret avec chiffrement RSA
 * Le recovery secret est régénéré automatiquement toutes les 24h
 * et envoyé chiffré par email à l'admin
 */
@Service
public class RecoverySecretService {
    
    private static final Logger log = LoggerFactory.getLogger(RecoverySecretService.class);
    private static final String RSA_ALGORITHM = "RSA";
    /**
     * Chiffrement RSA avec remplissage OAEP.
     *
     * <h3>Pourquoi pas PKCS#1 v1.5</h3>
     *
     * La valeur precedente etait {@code RSA/ECB/PKCS1Padding}. Ce remplissage
     * est vulnerable aux attaques par oracle de remplissage (Bleichenbacher) :
     * un attaquant qui peut soumettre des chiffres modifies et distinguer
     * « remplissage invalide » de « dechiffrement reussi » retrouve le clair,
     * requete par requete, sans jamais connaitre la cle privee.
     *
     * <p>OAEP elimine cette classe d'attaque. Il est le choix recommande pour
     * tout nouveau chiffrement RSA.
     *
     * <p><b>Consequence.</b> Un secret chiffre avec l'ancien remplissage ne se
     * dechiffre plus. Le secret de recuperation etant renouvele periodiquement
     * par {@code RecoverySecretScheduler}, les anciennes valeurs s'eteignent
     * d'elles-memes ; il faut en revanche declencher un renouvellement au
     * deploiement pour ne pas rester sans secret utilisable.
     */
    private static final String RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final int KEY_SIZE = 2048;
    
    @Value("${recovery.rsa.private-key:}")
    private String privateKeyBase64;
    
    @Value("${recovery.rsa.public-key:}")
    private String publicKeyBase64;
    
    /**
     * Génère une nouvelle paire de clés RSA
     */
    public KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        keyPairGenerator.initialize(KEY_SIZE);
        return keyPairGenerator.generateKeyPair();
    }
    
    /**
     * Chiffre un message avec la clé publique RSA
     */
    public String encrypt(String message, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedBytes = cipher.doFinal(message.getBytes());
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }
    
    /**
     * Déchiffre un message avec la clé privée RSA
     */
    public String decrypt(String encryptedMessage, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedMessage));
        return new String(decryptedBytes);
    }
    
    /**
     * Génère un recovery secret aléatoire sécurisé
     */
    public String generateRecoverySecret() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        SecureRandom random = new SecureRandom();
        StringBuilder secret = new StringBuilder();
        
        for (int i = 0; i < 32; i++) {
            secret.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return secret.toString();
    }
    
    /**
     * Récupère la clé publique depuis la configuration
     */
    public PublicKey getPublicKey() throws Exception {
        if (publicKeyBase64 == null || publicKeyBase64.isEmpty()) {
            throw new IllegalStateException("Clé publique RSA non configurée. Veuillez définir recovery.rsa.public-key");
        }
        
        byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
        java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance(RSA_ALGORITHM);
        java.security.spec.X509EncodedKeySpec keySpec = new java.security.spec.X509EncodedKeySpec(keyBytes);
        return keyFactory.generatePublic(keySpec);
    }
    
    /**
     * Récupère la clé privée depuis la configuration
     */
    public PrivateKey getPrivateKey() throws Exception {
        if (privateKeyBase64 == null || privateKeyBase64.isEmpty()) {
            throw new IllegalStateException("Clé privée RSA non configurée. Veuillez définir recovery.rsa.private-key");
        }
        
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64);
        java.security.KeyFactory keyFactory = java.security.KeyFactory.getInstance(RSA_ALGORITHM);
        java.security.spec.PKCS8EncodedKeySpec keySpec = new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
        return keyFactory.generatePrivate(keySpec);
    }
    
    /**
     * Chiffre le recovery secret avec la clé publique configurée
     */
    public String encryptRecoverySecret(String secret) throws Exception {
        return encrypt(secret, getPublicKey());
    }
}
