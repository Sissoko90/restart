package com.abdatytch.auth_service.client;

import com.abdatytch.auth_service.dto.request.GeoLocationData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Client pour récupérer les données de géolocalisation depuis le gateway-service
 * Utilise le service GeoIP du gateway qui exploite MaxMind GeoLite2
 */
@Component
@Slf4j
public class GatewayGeoLocationClient {
    
    private final WebClient webClient;
    
    @Value("${gateway.url}")
    private String gatewayUrl;
    
    public GatewayGeoLocationClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }
    
    /**
     * Récupère les données de géolocalisation pour une IP donnée.
     *
     * <p>Réactif : plus de {@code .block()} interne. Le contrat réseau vers le
     * gateway est inchangé, seule la signature Java évolue.
     *
     * <p>La géolocalisation est un enrichissement, jamais un motif d'échec :
     * toute erreur ou expiration est convertie en {@link Mono#empty()}, et
     * l'appelant poursuit sans données de localisation.
     *
     * @param ipAddress L'adresse IP à géolocaliser
     * @return les données de géolocalisation, ou un Mono vide
     */
    public Mono<GeoLocationData> getGeoLocation(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            log.debug("IP address is null or empty, skipping geolocation");
            return Mono.empty();
        }

        // Ignorer les IPs locales
        if (isLocalIp(ipAddress)) {
            log.debug("Local IP detected: {}, skipping geolocation", ipAddress);
            return Mono.just(buildLocalGeoData());
        }

        log.debug("Fetching geolocation for IP: {}", ipAddress);

        return webClient
                .get()
                .uri(gatewayUrl + "/admin/geo/ip/{ipAddress}", ipAddress)
                .retrieve()
                .bodyToMono(GeoLocationResponse.class)
                .timeout(Duration.ofSeconds(2))
                .map(response -> {
                    log.debug("Geolocation retrieved for IP {}: {}, {}",
                            ipAddress, response.getCity(), response.getCountry());

                    return GeoLocationData.builder()
                            .country(response.getCountry())
                            .countryCode(response.getCountryCode())
                            .region(response.getRegionName())
                            .city(response.getCity())
                            .latitude(response.getLat())
                            .longitude(response.getLon())
                            .timezone(response.getTimezone())
                            .isp(response.getIsp())
                            .organization(response.getOrg())
                            .asn(response.getAs())
                            .build();
                })
                .onErrorResume(error -> {
                    log.warn("Failed to fetch geolocation for IP {}: {}", ipAddress, error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Vérifie si une IP est locale
     */
    private boolean isLocalIp(String ipAddress) {
        return ipAddress.equals("127.0.0.1") ||
               ipAddress.equals("localhost") ||
               ipAddress.equals("0:0:0:0:0:0:0:1") ||
               ipAddress.equals("::1") ||
               ipAddress.startsWith("192.168.") ||
               ipAddress.startsWith("10.") ||
               ipAddress.startsWith("172.16.") ||
               ipAddress.startsWith("172.17.") ||
               ipAddress.startsWith("172.18.") ||
               ipAddress.startsWith("172.19.") ||
               ipAddress.startsWith("172.20.") ||
               ipAddress.startsWith("172.21.") ||
               ipAddress.startsWith("172.22.") ||
               ipAddress.startsWith("172.23.") ||
               ipAddress.startsWith("172.24.") ||
               ipAddress.startsWith("172.25.") ||
               ipAddress.startsWith("172.26.") ||
               ipAddress.startsWith("172.27.") ||
               ipAddress.startsWith("172.28.") ||
               ipAddress.startsWith("172.29.") ||
               ipAddress.startsWith("172.30.") ||
               ipAddress.startsWith("172.31.");
    }
    
    /**
     * Construit des données de géolocalisation pour une IP locale
     */
    private GeoLocationData buildLocalGeoData() {
        return GeoLocationData.builder()
            .country("Local")
            .countryCode("LO")
            .region("Local Network")
            .city("Local")
            .build();
    }
    
    /**
     * DTO pour la réponse du gateway GeoIP
     * Correspond à GeoLocationInfo du gateway-service
     */
    private static class GeoLocationResponse {
        private String query;
        private String country;
        private String countryCode;
        private String region;
        private String regionName;
        private String city;
        private String zip;
        private Double lat;
        private Double lon;
        private String timezone;
        private String isp;
        private String org;
        private String as;
        private Integer asn;
        private Boolean blocked;
        private String source;
        
        // Getters
        public String getQuery() { return query; }
        public String getCountry() { return country; }
        public String getCountryCode() { return countryCode; }
        public String getRegion() { return region; }
        public String getRegionName() { return regionName; }
        public String getCity() { return city; }
        public String getZip() { return zip; }
        public Double getLat() { return lat; }
        public Double getLon() { return lon; }
        public String getTimezone() { return timezone; }
        public String getIsp() { return isp; }
        public String getOrg() { return org; }
        public String getAs() { return as; }
        public Integer getAsn() { return asn; }
        public Boolean getBlocked() { return blocked; }
        public String getSource() { return source; }
        
        // Setters
        public void setQuery(String query) { this.query = query; }
        public void setCountry(String country) { this.country = country; }
        public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
        public void setRegion(String region) { this.region = region; }
        public void setRegionName(String regionName) { this.regionName = regionName; }
        public void setCity(String city) { this.city = city; }
        public void setZip(String zip) { this.zip = zip; }
        public void setLat(Double lat) { this.lat = lat; }
        public void setLon(Double lon) { this.lon = lon; }
        public void setTimezone(String timezone) { this.timezone = timezone; }
        public void setIsp(String isp) { this.isp = isp; }
        public void setOrg(String org) { this.org = org; }
        public void setAs(String as) { this.as = as; }
        public void setAsn(Integer asn) { this.asn = asn; }
        public void setBlocked(Boolean blocked) { this.blocked = blocked; }
        public void setSource(String source) { this.source = source; }
    }
}
