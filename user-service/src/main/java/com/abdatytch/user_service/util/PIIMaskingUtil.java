package com.abdatytch.user_service.util;

import lombok.experimental.UtilityClass;

/**

 * Utilitaire pour masquer les données personnelles identifiables (PII) dans les logs
 * Conforme RGPD - évite l'exposition de données sensibles
 */
@UtilityClass
public class PIIMaskingUtil {

    private static final String MASK = "***";
    private static final int VISIBLE_EMAIL_PREFIX = 2;
    private static final int VISIBLE_PHONE_PREFIX = 3;
    private static final int VISIBLE_PHONE_SUFFIX = 2;

    /**

     * Masque un email pour les logs
     * Exemple: john.doe@example.com  jo***@example.com
     */
    public static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }

        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return MASK; // Email invalide
        }

        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);

        if (localPart.length() <= VISIBLE_EMAIL_PREFIX) {
            return MASK + domain;
        }

        return localPart.substring(0, VISIBLE_EMAIL_PREFIX) + MASK + domain;
    }

    /**

     * Masque un numéro de téléphone pour les logs
     * Exemple: +33612345678  +33***78
     */
    public static String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            return phoneNumber;
        }

        // Garder le code pays et les 2 derniers chiffres
        if (phoneNumber.length() <= VISIBLE_PHONE_PREFIX + VISIBLE_PHONE_SUFFIX) {
            return MASK;
        }

        String prefix = phoneNumber.substring(0, VISIBLE_PHONE_PREFIX);
        String suffix = phoneNumber.substring(phoneNumber.length() - VISIBLE_PHONE_SUFFIX);

        return prefix + MASK + suffix;
    }

    /**

     * Masque un nom complet pour les logs
     * Exemple: John Doe  J*** D*** */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }

        if (name.length() <= 1) {
            return MASK;
        }

        return name.charAt(0) + MASK;
    }

    /**

     * Masque une adresse pour les logs
     * Exemple: 123 Main Street, Paris  123 Main***, Paris
     */
    public static String maskAddress(String address) {
        if (address == null || address.isEmpty()) {
            return address;
        }

        // Garder seulement le début et la ville si présente
        String[] parts = address.split(",");
        if (parts.length > 1) {
            String street = parts[0];
            String city = parts[parts.length - 1].trim();

            if (street.length() > 10) {
                street = street.substring(0, 10) + MASK;
            }

            return street + ", "+ city;
        }

        if (address.length() > 15) {
            return address.substring(0, 15) + MASK;
        }

        return address;
    }

    /**

     * Masque un UUID pour les logs (garde seulement les 8 premiers caractères)
     * Exemple: 550e8400-e29b-41d4-a716-446655440000  550e8400-*** */
    public static String maskUUID(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return uuid;
        }

        if (uuid.length() <= 8) {
            return MASK;
        }

        return uuid.substring(0, 8) + "-" + MASK;
    }

    /**

     * Masque un token JWT pour les logs (garde seulement les 10 premiers caractères)
     * Exemple: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...  eyJhbGciOi*** */
    public static String maskToken(String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }

        if (token.length() <= 10) {
            return MASK;
        }

        return token.substring(0, 10) + MASK;
    }

    /**

     * Masque complètement une valeur sensible
     */
    public static String maskCompletely(String value) {
        return value != null && !value.isEmpty() ? MASK : value;
    }
}
