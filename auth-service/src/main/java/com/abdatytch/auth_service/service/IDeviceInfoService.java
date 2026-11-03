package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.response.DeviceInfo;

/**
 * Service d'analyse des informations de l'appareil et du navigateur
 * Utilise UAParser pour extraire les détails du User-Agent
 */
public interface IDeviceInfoService {
    
    /**
     * Analyse le User-Agent pour extraire les informations de l'appareil
     * 
     * @param userAgent La chaîne User-Agent du client
     * @return Les informations détaillées de l'appareil
     */
    DeviceInfo parseUserAgent(String userAgent);
    
    /**
     * Formate les informations de l'appareil en chaîne lisible
     * Format: "Browser Version on OS (Device)"
     * 
     * @param userAgent La chaîne User-Agent du client
     * @return Les informations formatées ou "Unknown" si non disponible
     */
    String getFormattedDeviceInfo(String userAgent);
}
