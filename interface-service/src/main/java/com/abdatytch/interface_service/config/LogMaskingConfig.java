package com.abdatytch.interface_service.config;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.context.annotation.Configuration;

import java.util.regex.Pattern;

/**
 * Configuration pour masquer les données sensibles dans les logs
 * Empêche l'exposition accidentelle de mots de passe, tokens, secrets, etc.
 */
@Configuration
public class LogMaskingConfig {

    /**
     * Layout personnalisé pour masquer les données sensibles
     */
    public static class MaskingPatternLayout extends PatternLayout {
        
        // Patterns pour détecter les données sensibles
        private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(password[\"'\\s]*[:=][\"'\\s]*)(\\S+)", Pattern.CASE_INSENSITIVE);
        
        private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(token[\"'\\s]*[:=][\"'\\s]*)(\\S+)", Pattern.CASE_INSENSITIVE);
        
        private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(secret[\"'\\s]*[:=][\"'\\s]*)(\\S+)", Pattern.CASE_INSENSITIVE);
        
        private static final Pattern TOTP_PATTERN = Pattern.compile(
            "(totp[\"'\\s]*[:=][\"'\\s]*)(\\S+)", Pattern.CASE_INSENSITIVE);
        
        private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "(api[_-]?key[\"'\\s]*[:=][\"'\\s]*)(\\S+)", Pattern.CASE_INSENSITIVE);
        
        private static final Pattern JWT_PATTERN = Pattern.compile(
            "(Bearer\\s+)(eyJ[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_.+/=]*)", 
            Pattern.CASE_INSENSITIVE);

        @Override
        public String doLayout(ILoggingEvent event) {
            String message = super.doLayout(event);
            return maskSensitiveData(message);
        }

        /**
         * Masque les données sensibles dans les messages de log
         */
        private String maskSensitiveData(String message) {
            if (message == null) {
                return null;
            }

            String masked = message;
            
            // Masquer les mots de passe
            masked = PASSWORD_PATTERN.matcher(masked)
                .replaceAll("$1****");
            
            // Masquer les tokens
            masked = TOKEN_PATTERN.matcher(masked)
                .replaceAll("$1****");
            
            // Masquer les secrets
            masked = SECRET_PATTERN.matcher(masked)
                .replaceAll("$1****");
            
            // Masquer les codes TOTP
            masked = TOTP_PATTERN.matcher(masked)
                .replaceAll("$1****");
            
            // Masquer les clés API
            masked = API_KEY_PATTERN.matcher(masked)
                .replaceAll("$1****");
            
            // Masquer les JWT tokens
            masked = JWT_PATTERN.matcher(masked)
                .replaceAll("$1****");

            return masked;
        }
    }
}
