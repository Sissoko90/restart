package com.abdatytch.auth_service.enums;

/**
 * Types de biométrie supportés (WebAuthn/FIDO2)
 */
public enum BiometricType {
    FINGERPRINT("Empreinte digitale"),
    FACE_ID("Reconnaissance faciale"),
    TOUCH_ID("Touch ID"),
    WINDOWS_HELLO("Windows Hello"),
    PLATFORM_AUTHENTICATOR("Authenticateur de plateforme"),
    SECURITY_KEY("Clé de sécurité physique");
    
    private final String description;
    
    BiometricType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}
