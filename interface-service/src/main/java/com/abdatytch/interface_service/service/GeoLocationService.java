package com.abdatytch.interface_service.service;

import com.abdatytch.interface_service.dto.GeoLocationInfo;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.CountryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**

 * Service de géolocalisation des IPs avec MaxMind GeoIP2
 * Pour récupérer toutes les informations détaillées sur une tentative de connexion
 */
@Service
public class GeoLocationService {

    private static final Logger log = LoggerFactory.getLogger(GeoLocationService.class);

    @Value("${geolocation.database.city.path:/opt/geoip/GeoLite2-City.mmdb}")
    private String cityDatabasePath;

    @Value("${geolocation.database.asn.path:/opt/geoip/GeoLite2-ASN.mmdb}")
    private String asnDatabasePath;

    @Value("${geolocation.enabled:true}")
    private boolean geoLocationEnabled;

    private DatabaseReader cityDatabaseReader;
    private DatabaseReader asnDatabaseReader;

    @PostConstruct
    public void init() {
        if (!geoLocationEnabled) {
            log.info(" GeoLocation is DISABLED");
            return;
        }

        try {
            // Charger City database
            File cityDatabase = new File(cityDatabasePath);
            if (cityDatabase.exists()) {
                cityDatabaseReader = new DatabaseReader.Builder(cityDatabase).build();
                log.info(" GeoIP2 City database loaded successfully from: {}", cityDatabasePath);
            } else {
                log.warn(" GeoIP2 City database not found at: {}. City info will be unavailable.", cityDatabasePath);
            }

            // Charger ASN database
            File asnDatabase = new File(asnDatabasePath);
            if (asnDatabase.exists()) {
                asnDatabaseReader = new DatabaseReader.Builder(asnDatabase).build();
                log.info(" GeoIP2 ASN database loaded successfully from: {}", asnDatabasePath);
            } else {
                log.warn(" GeoIP2 ASN database not found at: {}. ISP info will be unavailable.", asnDatabasePath);
            }

        } catch (IOException e) {
            log.error(" Failed to load GeoIP2 database: {}", e.getMessage());
            geoLocationEnabled = false;
        }
    }

    /**

     * Vérifie si une IP est locale (localhost, 127.0.0.1, ::1, etc.)
     */
    private boolean isLocalIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return false;
        }

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

     * Obtient les informations détaillées de géolocalisation d'une IP
     * Combine les données de GeoLite2-City et GeoLite2-ASN
     */
    public Mono<GeoLocationInfo> getDetailedGeoInfo(String ipAddress, String userAgent) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String id = UUID.randomUUID().toString();

        // Gérer les IPs locales
        if (isLocalIp(ipAddress)) {
            GeoLocationInfo info = new GeoLocationInfo();
            info.setId(id);
            info.setCreatedAt(timestamp);
            info.setUpdatedAt(timestamp);
            info.setIpAddress(ipAddress);
            info.setCountry("Local");
            info.setCountryCode("LOCAL");
            info.setCity("Local");
            info.setEventType("ADMIN_LOGIN_ATTEMPT");
            info.setStatus("LOCAL_IP");
            info.setTimestamp(timestamp);
            info.setDeviceType(parseUserAgentDeviceType(userAgent));
            info.setBrowserFamily(parseUserAgentBrowser(userAgent));
            info.setOsFamily(parseUserAgentOS(userAgent));
            return Mono.just(info);
        }

        if (!geoLocationEnabled) {
            GeoLocationInfo info = new GeoLocationInfo();
            info.setId(id);
            info.setCreatedAt(timestamp);
            info.setUpdatedAt(timestamp);
            info.setIpAddress(ipAddress);
            info.setCountry("Unknown");
            info.setCountryCode("UNKNOWN");
            info.setEventType("ADMIN_LOGIN_ATTEMPT");
            info.setStatus("GEOLOCATION_DISABLED");
            info.setTimestamp(timestamp);
            info.setDeviceType(parseUserAgentDeviceType(userAgent));
            info.setBrowserFamily(parseUserAgentBrowser(userAgent));
            info.setOsFamily(parseUserAgentOS(userAgent));
            return Mono.just(info);
        }

        return Mono.fromCallable(() -> {
            GeoLocationInfo info = new GeoLocationInfo();
            info.setId(id);
            info.setCreatedAt(timestamp);
            info.setUpdatedAt(timestamp);
            info.setIpAddress(ipAddress);
            info.setEventType("ADMIN_LOGIN_ATTEMPT");
            info.setStatus("DETECTED");
            info.setTimestamp(timestamp);

            // Parser User-Agent
            info.setDeviceType(parseUserAgentDeviceType(userAgent));
            info.setBrowserFamily(parseUserAgentBrowser(userAgent));
            info.setBrowserVersion(parseUserAgentBrowserVersion(userAgent));
            info.setOsFamily(parseUserAgentOS(userAgent));
            info.setOsVersion(parseUserAgentOSVersion(userAgent));
            info.setDeviceInfo(userAgent);

            try {
                InetAddress inetAddress = InetAddress.getByName(ipAddress);

                // City info (si disponible)
                if (cityDatabaseReader != null) {
                    try {
                        CityResponse cityResponse = cityDatabaseReader.city(inetAddress);

                        // Ville
                        if (cityResponse.getCity() != null && cityResponse.getCity().getName() != null) {
                            info.setCity(cityResponse.getCity().getName());
                        }

                        // Pays
                        if (cityResponse.getCountry() != null) {
                            String countryCode = cityResponse.getCountry().getIsoCode();
                            String countryName = cityResponse.getCountry().getName();
                            info.setCountryCode(countryCode != null ? countryCode : "UNKNOWN");
                            info.setCountry(countryName != null ? countryName : "Unknown");
                        }

                        // Région
                        if (cityResponse.getMostSpecificSubdivision() != null) {
                            info.setRegion(cityResponse.getMostSpecificSubdivision().getIsoCode());
                        }

                        // Coordonnées
                        if (cityResponse.getLocation() != null) {
                            info.setLatitude(cityResponse.getLocation().getLatitude());
                            info.setLongitude(cityResponse.getLocation().getLongitude());
                            info.setTimezone(cityResponse.getLocation().getTimeZone());
                        }

                        log.debug(" IP {} resolved to city: {}, {}", ipAddress, info.getCity(), info.getCountry());
                    } catch (Exception e) {
                        log.debug(" Failed to get city for IP {}: {}", ipAddress, e.getMessage());
                    }
                }

                // ASN info (si disponible)
                if (asnDatabaseReader != null) {
                    try {
                        AsnResponse asnResponse = asnDatabaseReader.asn(inetAddress);

                        if (asnResponse.getAutonomousSystemNumber() != null) {
                            info.setAsn(asnResponse.getAutonomousSystemNumber().intValue());
                        }

                        if (asnResponse.getAutonomousSystemOrganization() != null) {
                            String org = asnResponse.getAutonomousSystemOrganization();
                            info.setOrganization(org);
                            info.setIsp(org);
                        }

                        log.debug(" IP {} resolved to ASN: {} ({})", ipAddress, info.getAsn(), info.getOrganization());
                    } catch (Exception e) {
                        log.debug(" Failed to get ASN for IP {}: {}", ipAddress, e.getMessage());
                    }
                }

            } catch (IOException e) {
                log.debug(" Failed to resolve IP {}: {}", ipAddress, e.getMessage());
                info.setCountryCode("UNKNOWN");
                info.setCountry("Unknown");
            }

            return info;
        });
    }

    /**

     * Parse le type d'appareil depuis le User-Agent
     */
    private String parseUserAgentDeviceType(String userAgent) {
        if (userAgent == null) return "Unknown";

        String ua = userAgent.toLowerCase();
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")) {
            return "Mobile";
        } else if (ua.contains("tablet") || ua.contains("ipad")) {
            return "Tablet";
        } else if (ua.contains("bot") || ua.contains("crawler") || ua.contains("spider")) {
            return "Bot";
        }
        return "Desktop";
    }

    /**

     * Parse le navigateur depuis le User-Agent
     */
    private String parseUserAgentBrowser(String userAgent) {
        if (userAgent == null) return "Unknown";

        String ua = userAgent.toLowerCase();
        if (ua.contains("chrome") && !ua.contains("edg")) {
            return "Chrome";
        } else if (ua.contains("firefox")) {
            return "Firefox";
        } else if (ua.contains("safari") && !ua.contains("chrome")) {
            return "Safari";
        } else if (ua.contains("edg")) {
            return "Edge";
        } else if (ua.contains("opera")) {
            return "Opera";
        }
        return "Unknown";
    }

    /**

     * Parse la version du navigateur depuis le User-Agent
     */
    private String parseUserAgentBrowserVersion(String userAgent) {
        if (userAgent == null) return "Unknown";

        String ua = userAgent.toLowerCase();
        try {
            if (ua.contains("chrome") && !ua.contains("edg")) {
                int start = ua.indexOf("chrome/");
                if (start != -1) {
                    String version = ua.substring(start + 7).split("[\\s\\)]")[0];
                    return version;
                }
            } else if (ua.contains("firefox")) {
                int start = ua.indexOf("firefox/");
                if (start != -1) {
                    String version = ua.substring(start + 8).split("[\\s\\)]")[0];
                    return version;
                }
            } else if (ua.contains("safari") && !ua.contains("chrome")) {
                int start = ua.indexOf("version/");
                if (start != -1) {
                    String version = ua.substring(start + 8).split("[\\s\\)]")[0];
                    return version;
                }
            }
        } catch (Exception e) {
            return "Unknown";
        }
        return "Unknown";
    }

    /**

     * Parse l'OS depuis le User-Agent
     */
    private String parseUserAgentOS(String userAgent) {
        if (userAgent == null) return "Unknown";

        String ua = userAgent.toLowerCase();
        if (ua.contains("windows")) {
            return "Windows";
        } else if (ua.contains("mac os") || ua.contains("macos")) {
            return "macOS";
        } else if (ua.contains("linux")) {
            return "Linux";
        } else if (ua.contains("android")) {
            return "Android";
        } else if (ua.contains("iphone") || ua.contains("ios")) {
            return "iOS";
        }
        return "Unknown";
    }

    /**

     * Parse la version de l'OS depuis le User-Agent
     */
    private String parseUserAgentOSVersion(String userAgent) {
        if (userAgent == null) return "Unknown";

        String ua = userAgent.toLowerCase();
        try {
            if (ua.contains("windows nt")) {
                int start = ua.indexOf("windows nt");
                String version = ua.substring(start).split("[;\\)]")[0];
                return version.replace("windows nt", "").trim();
            } else if (ua.contains("mac os x")) {
                int start = ua.indexOf("mac os x");
                String version = ua.substring(start).split("[;\\)]")[0];
                return version.replace("mac os x", "").trim();
            } else if (ua.contains("android")) {
                int start = ua.indexOf("android");
                String version = ua.substring(start).split("[;\\)]")[0];
                return version.replace("android", "").trim();
            }
        } catch (Exception e) {
            return "Unknown";
        }
        return "Unknown";
    }
}
