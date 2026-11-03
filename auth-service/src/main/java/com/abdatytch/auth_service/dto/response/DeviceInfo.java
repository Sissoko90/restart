package com.abdatytch.auth_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO contenant les informations détaillées de l'appareil et du navigateur
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceInfo {
    
    // Informations du navigateur
    private String browserFamily;
    private String browserVersion;
    
    // Informations du système d'exploitation
    private String osFamily;
    private String osVersion;
    
    // Informations de l'appareil
    private String deviceFamily;
    private String deviceBrand;
    private String deviceModel;
    
    // Type d'appareil
    private String deviceType; // Mobile, Tablet, Desktop, Bot, etc.
    
    /**
     * Formate les informations de l'appareil en chaîne lisible
     * Format: "Browser Version on OS (Device)"
     */
    public String getFormattedDeviceInfo() {
        StringBuilder info = new StringBuilder();
        
        // Browser
        if (browserFamily != null && !browserFamily.isEmpty()) {
            info.append(browserFamily);
            if (browserVersion != null && !browserVersion.isEmpty()) {
                info.append(" ").append(browserVersion);
            }
        }
        
        // OS
        if (osFamily != null && !osFamily.isEmpty()) {
            if (info.length() > 0) info.append(" on ");
            info.append(osFamily);
            if (osVersion != null && !osVersion.isEmpty()) {
                info.append(" ").append(osVersion);
            }
        }
        
        // Device
        if (deviceFamily != null && !deviceFamily.isEmpty() && !"Other".equals(deviceFamily)) {
            if (info.length() > 0) info.append(" (");
            info.append(deviceFamily);
            if (deviceModel != null && !deviceModel.isEmpty()) {
                info.append(" ").append(deviceModel);
            }
            if (info.toString().contains("(")) info.append(")");
        }
        
        return info.length() > 0 ? info.toString() : "Unknown";
    }
    
    /**
     * Détermine si l'appareil est mobile
     */
    public boolean isMobile() {
        return "Mobile".equalsIgnoreCase(deviceType) || "Tablet".equalsIgnoreCase(deviceType);
    }
    
    /**
     * Détermine si c'est un bot
     */
    public boolean isBot() {
        return "Bot".equalsIgnoreCase(deviceType) || 
               (browserFamily != null && browserFamily.toLowerCase().contains("bot"));
    }
}
