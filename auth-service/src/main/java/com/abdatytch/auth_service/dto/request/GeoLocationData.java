package com.abdatytch.auth_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO pour transmettre les données de géolocalisation depuis le gateway
 * Ces données proviennent du service GeoIP du gateway-service
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoLocationData {
    
    private String country;
    private String countryCode;
    private String region;
    private String city;
    private Double latitude;
    private Double longitude;
    private String timezone;
    private String isp;
    private String organization;
    private String asn;
    
    /**
     * Formate la localisation en chaîne lisible
     * Format: "Ville, Région, Pays"
     */
    public String getFormattedLocation() {
        StringBuilder location = new StringBuilder();
        
        if (city != null && !city.isEmpty()) {
            location.append(city);
        }
        
        if (region != null && !region.isEmpty()) {
            if (location.length() > 0) location.append(", ");
            location.append(region);
        }
        
        if (country != null && !country.isEmpty()) {
            if (location.length() > 0) location.append(", ");
            location.append(country);
        }
        
        return location.length() > 0 ? location.toString() : "Unknown";
    }
}
