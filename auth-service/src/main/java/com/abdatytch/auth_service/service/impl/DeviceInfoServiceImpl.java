package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.dto.response.DeviceInfo;
import com.abdatytch.auth_service.service.IDeviceInfoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ua_parser.Client;
import ua_parser.Parser;

/**
 * Implémentation du service d'analyse des informations de l'appareil
 * Utilise UAParser pour extraire les détails du User-Agent
 */
@Service
@Slf4j
public class DeviceInfoServiceImpl implements IDeviceInfoService {
    
    private final Parser uaParser;
    
    public DeviceInfoServiceImpl() {
        this.uaParser = new Parser();
    }
    
    @Override
    public DeviceInfo parseUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return buildUnknownDeviceInfo();
        }
        
        try {
            Client client = uaParser.parse(userAgent);
            
            return DeviceInfo.builder()
                    // Browser
                    .browserFamily(client.userAgent.family)
                    .browserVersion(buildVersion(
                            client.userAgent.major,
                            client.userAgent.minor,
                            client.userAgent.patch
                    ))
                    // OS
                    .osFamily(client.os.family)
                    .osVersion(buildVersion(
                            client.os.major,
                            client.os.minor,
                            client.os.patch
                    ))
                    // Device
                    .deviceFamily(client.device.family)
                    .deviceBrand(null) // UAParser ne fournit pas brand
                    .deviceModel(null) // UAParser ne fournit pas model
                    .deviceType(determineDeviceType(client))
                    .build();
                    
        } catch (Exception e) {
            log.warn("Failed to parse user agent: {}", e.getMessage());
            return buildUnknownDeviceInfo();
        }
    }
    
    @Override
    public String getFormattedDeviceInfo(String userAgent) {
        DeviceInfo info = parseUserAgent(userAgent);
        return info.getFormattedDeviceInfo();
    }
    
    private String buildVersion(String major, String minor, String patch) {
        StringBuilder version = new StringBuilder();
        
        if (major != null && !major.isEmpty()) {
            version.append(major);
            
            if (minor != null && !minor.isEmpty()) {
                version.append(".").append(minor);
                
                if (patch != null && !patch.isEmpty()) {
                    version.append(".").append(patch);
                }
            }
        }
        
        return version.length() > 0 ? version.toString() : null;
    }
    
    private String determineDeviceType(Client client) {
        String deviceFamily = client.device.family;
        String osFamily = client.os.family;
        String browserFamily = client.userAgent.family;
        
        // Détection des bots
        if (browserFamily != null && 
            (browserFamily.toLowerCase().contains("bot") || 
             browserFamily.toLowerCase().contains("crawler") ||
             browserFamily.toLowerCase().contains("spider"))) {
            return "Bot";
        }
        
        // Détection mobile
        if (deviceFamily != null && !deviceFamily.equals("Other")) {
            if (deviceFamily.toLowerCase().contains("ipad") || 
                deviceFamily.toLowerCase().contains("tablet")) {
                return "Tablet";
            }
            if (deviceFamily.toLowerCase().contains("iphone") || 
                deviceFamily.toLowerCase().contains("mobile") ||
                deviceFamily.toLowerCase().contains("android")) {
                return "Mobile";
            }
        }
        
        // Détection par OS
        if (osFamily != null) {
            if (osFamily.contains("iOS") || osFamily.contains("Android")) {
                return "Mobile";
            }
            if (osFamily.contains("Windows") || osFamily.contains("Mac OS") || 
                osFamily.contains("Linux") || osFamily.contains("Ubuntu")) {
                return "Desktop";
            }
        }
        
        return "Desktop"; // Par défaut
    }
    
    private DeviceInfo buildUnknownDeviceInfo() {
        return DeviceInfo.builder()
                .browserFamily("Unknown")
                .osFamily("Unknown")
                .deviceFamily("Unknown")
                .deviceType("Unknown")
                .build();
    }
}
