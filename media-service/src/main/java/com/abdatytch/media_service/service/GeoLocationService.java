package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de géolocalisation via MaxMind GeoIP2
 * Fournit des informations détaillées sur la localisation des utilisateurs
 */
public interface GeoLocationService {
    
    /**
     * Obtenir les informations de géolocalisation à partir d'une adresse IP
     */
    Mono<GeoLocationInfo> getLocationFromIp(String ipAddress);
    
    /**
     * Informations de géolocalisation
     */
    record GeoLocationInfo(
        String ipAddress,
        String country,
        String countryCode,
        String city,
        String region,
        String postalCode,
        Double latitude,
        Double longitude,
        String timezone,
        String isp,
        String organization,
        String asn,
        Boolean isProxy,
        Boolean isVpn,
        Boolean isTor,
        String connectionType
    ) {
        public String getFullLocation() {
            StringBuilder location = new StringBuilder();
            if (city != null) location.append(city).append(", ");
            if (region != null) location.append(region).append(", ");
            if (country != null) location.append(country);
            return location.toString();
        }
        
        public boolean isSuspicious() {
            return Boolean.TRUE.equals(isProxy) || 
                   Boolean.TRUE.equals(isVpn) || 
                   Boolean.TRUE.equals(isTor);
        }
    }
}
