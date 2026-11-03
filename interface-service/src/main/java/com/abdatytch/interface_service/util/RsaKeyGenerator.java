package com.abdatytch.interface_service.util;

import java.security.*;
import java.util.Base64;

/**

 * Utilitaire pour générer une paire de clés RSA
 * Utilisez cette classe pour générer les clés RSA pour le chiffrement du recovery secret
 */
public class RsaKeyGenerator {

    private static final String RSA_ALGORITHM = "RSA";
    private static final int KEY_SIZE = 2048;

    public static void main(String[] args) throws NoSuchAlgorithmException {
        KeyPair keyPair = generateKeyPair();

        System.out.println("=== Clés RSA générées ===\n");
        System.out.println("Clé publique (Base64) - À ajouter dans .env comme RECOVERY_RSA_PUBLIC_KEY:");
        System.out.println(getPublicKeyBase64(keyPair.getPublic()));
        System.out.println("\nClé privée (Base64) - À stocker en sécurité et ajouter dans .env comme RECOVERY_RSA_PRIVATE_KEY:");
        System.out.println(getPrivateKeyBase64(keyPair.getPrivate()));
        System.out.println("\n IMPORTANT: Gardez la clé privée en sécurité! Elle est nécessaire pour déchiffrer les recovery secrets.");
    }

    public static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        keyPairGenerator.initialize(KEY_SIZE);
        return keyPairGenerator.generateKeyPair();
    }

    public static String getPublicKeyBase64(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public static String getPrivateKeyBase64(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }
}
