package com.abdatytch.gateway_service.util;

import java.util.regex.Pattern;

/**
 * Utilitaire pour logs sécurisés
 * Masque les données sensibles avant logging
 * 
 * Protège contre:
 * - Fuite de tokens JWT
 * - Fuite de secrets/API keys
 * - Fuite de mots de passe
 * - Fuite de données personnelles
 */
public class SecurityLogUtil {
    
    private static final Pattern JWT_PATTERN = Pattern.compile("Bearer\\s+[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_.+/=]*");
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(api[_-]?key|apikey|secret[_-]?key)\\s*[:=]\\s*['\"]?([A-Za-z0-9_-]{20,})['\"]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(password|passwd|pwd)\\s*[:=]\\s*['\"]?([^'\"\\s,}]{3,})['\"]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\+?\\d{10,15}");
    
    /**
     * Masque les données sensibles dans une chaîne
     */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        String sanitized = value;
        
        // Masquer JWT tokens
        sanitized = JWT_PATTERN.matcher(sanitized).replaceAll("Bearer ***");
        
        // Masquer API keys et secrets
        sanitized = API_KEY_PATTERN.matcher(sanitized).replaceAll("$1=***");
        
        // Masquer passwords
        sanitized = PASSWORD_PATTERN.matcher(sanitized).replaceAll("$1=***");
        
        return sanitized;
    }
    
    /**
     * Masque partiellement un email (garde le domaine)
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        
        String[] parts = email.split("@");
        if (parts.length != 2) {
            return email;
        }
        
        String localPart = parts[0];
        String domain = parts[1];
        
        if (localPart.length() <= 2) {
            return "**@" + domain;
        }
        
        return localPart.charAt(0) + "***" + localPart.charAt(localPart.length() - 1) + "@" + domain;
    }
    
    /**
     * Masque partiellement un numéro de téléphone
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) {
            return phone;
        }
        
        return phone.substring(0, 3) + "***" + phone.substring(phone.length() - 2);
    }
    
    /**
     * Masque un token (garde début et fin)
     */
    public static String maskToken(String token) {
        if (token == null || token.length() < 20) {
            return "***";
        }
        
        return token.substring(0, 10) + "***" + token.substring(token.length() - 5);
    }
    
    /**
     * Masque une valeur longue (secret, key, etc.)
     */
    public static String maskSecret(String secret) {
        if (secret == null) {
            return null;
        }
        
        if (secret.length() <= 10) {
            return "***";
        }
        
        return secret.substring(0, 4) + "***" + secret.substring(secret.length() - 4);
    }
    
    /**
     * Vérifie si une chaîne contient des données sensibles
     */
    public static boolean containsSensitiveData(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        
        return JWT_PATTERN.matcher(value).find() ||
               API_KEY_PATTERN.matcher(value).find() ||
               PASSWORD_PATTERN.matcher(value).find();
    }
}
