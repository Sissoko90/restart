package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.entity.MediaAccessLog;
import com.abdatytch.media_service.repository.MediaAccessLogRepository;
import com.abdatytch.media_service.service.GeoLocationService;
import com.abdatytch.media_service.service.UserActivityLogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Journal d'activité utilisateur, émis par logback et consigné en base.
 *
 * <p>Il écrivait lui-même dans {@code logs/<type>/<date>.log}. Deux conséquences
 * y tenaient : des entrées-sorties bloquantes sur l'event loop, et un troisième
 * magasin pour un même événement, sans rotation ni rétention, sur le disque des
 * documents téléversés. Voir {@link #writeToLogFile}.
 *
 * <p>Utilise MaxMind GeoIP2 pour la géolocalisation complète.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserActivityLogServiceImpl implements UserActivityLogService {

    private final MediaAccessLogRepository accessLogRepository;
    private final GeoLocationCacheServiceImpl geoLocationCacheService;
    private final ObjectMapper objectMapper;
    
    /**
     * Logger dédié au journal d'activité, distinct de celui de la classe.
     *
     * <p>Ce flux est de l'<b>audit applicatif</b>, pas du diagnostic technique :
     * le nommer à part permet de le router, de le filtrer dans Loki et de lui
     * donner sa propre rétention sans toucher au reste.
     */
    private static final org.slf4j.Logger JOURNAL =
            org.slf4j.LoggerFactory.getLogger("journal.activite.media");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public Mono<Void> logDocumentUpload(
            String userId,
            String documentId,
            String documentTypeCode,
            String fileName,
            Long fileSize,
            String ipAddress,
            String userAgent,
            String requestId,
            Map<String, Object> additionalContext) {
        
        return geoLocationCacheService.getLocationFromIpWithCache(ipAddress)
            .flatMap(geoInfo -> {
                Map<String, Object> logData = new HashMap<>();
                logData.put("event", "DOCUMENT_UPLOAD");
                logData.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
                logData.put("userId", userId);
                logData.put("documentId", documentId);
                logData.put("documentTypeCode", documentTypeCode);
                logData.put("fileName", fileName);
                logData.put("fileSize", fileSize);
                logData.put("ipAddress", ipAddress);
                logData.put("userAgent", userAgent);
                logData.put("requestId", requestId);
                
                // Informations de géolocalisation (HashMap pour supporter les valeurs null)
                Map<String, Object> geoData = new HashMap<>();
                geoData.put("country", geoInfo.country());
                geoData.put("countryCode", geoInfo.countryCode());
                geoData.put("city", geoInfo.city());
                geoData.put("region", geoInfo.region());
                geoData.put("location", geoInfo.getFullLocation());
                geoData.put("latitude", geoInfo.latitude());
                geoData.put("longitude", geoInfo.longitude());
                geoData.put("timezone", geoInfo.timezone());
                geoData.put("isp", geoInfo.isp());
                geoData.put("organization", geoInfo.organization());
                logData.put("geo", geoData);
                
                // Informations de sécurité
                Map<String, Object> securityData = new HashMap<>();
                securityData.put("isProxy", geoInfo.isProxy());
                securityData.put("isVpn", geoInfo.isVpn());
                securityData.put("isTor", geoInfo.isTor());
                securityData.put("isSuspicious", geoInfo.isSuspicious());
                securityData.put("connectionType", geoInfo.connectionType());
                logData.put("security", securityData);
                
                // Parsing User-Agent
                Map<String, String> deviceInfo = parseUserAgent(userAgent);
                logData.put("device", deviceInfo);
                
                // Contexte additionnel
                if (additionalContext != null && !additionalContext.isEmpty()) {
                    logData.put("context", additionalContext);
                }
                
                // Écrire dans le fichier de log
                return writeToLogFile("document-uploads", logData)
                    .then(saveToDatabase(userId, documentId, "UPLOAD", ipAddress, userAgent, 
                        requestId, geoInfo.getFullLocation(), geoInfo.isSuspicious()));
            })
            .doOnSuccess(v -> log.info(" Document upload logged - User: {}, Doc: {}, Type: {}",
                userId, documentId, documentTypeCode))
            .then();
    }

    @Override
    public Mono<Void> logDocumentDownload(
            String userId,
            String documentId,
            String ipAddress,
            String userAgent,
            String requestId) {
        
        return geoLocationCacheService.getLocationFromIpWithCache(ipAddress)
            .flatMap(geoInfo -> {
                Map<String, Object> logData = createBaseLogData(
                    "DOCUMENT_DOWNLOAD", userId, documentId, ipAddress, userAgent, requestId, geoInfo
                );
                
                return writeToLogFile("document-downloads", logData)
                    .then(saveToDatabase(userId, documentId, "DOWNLOAD", ipAddress, userAgent, 
                        requestId, geoInfo.getFullLocation(), geoInfo.isSuspicious()));
            })
            .then();
    }

    @Override
    public Mono<Void> logDocumentDeletion(
            String userId,
            String documentId,
            String reason,
            String ipAddress,
            String userAgent,
            String requestId) {
        
        return geoLocationCacheService.getLocationFromIpWithCache(ipAddress)
            .flatMap(geoInfo -> {
                Map<String, Object> logData = createBaseLogData(
                    "DOCUMENT_DELETION", userId, documentId, ipAddress, userAgent, requestId, geoInfo
                );
                logData.put("reason", reason);
                
                return writeToLogFile("document-deletions", logData)
                    .then(saveToDatabase(userId, documentId, "DELETE", ipAddress, userAgent, 
                        requestId, geoInfo.getFullLocation(), geoInfo.isSuspicious()));
            })
            .then();
    }

    @Override
    public Mono<Void> logDocumentReview(
            String adminId,
            String documentId,
            String userId,
            String action,
            String reason,
            String ipAddress,
            String userAgent,
            String requestId) {
        
        return geoLocationCacheService.getLocationFromIpWithCache(ipAddress)
            .flatMap(geoInfo -> {
                Map<String, Object> logData = createBaseLogData(
                    "DOCUMENT_REVIEW", adminId, documentId, ipAddress, userAgent, requestId, geoInfo
                );
                logData.put("targetUserId", userId);
                logData.put("action", action);
                logData.put("reason", reason);
                
                return writeToLogFile("document-reviews", logData)
                    .then(saveToDatabase(adminId, documentId, action, ipAddress, userAgent, 
                        requestId, geoInfo.getFullLocation(), false));
            })
            .then();
    }

    @Override
    public Mono<Void> logSecurityIncident(
            String userId,
            String documentId,
            String threatType,
            String severity,
            String description,
            String ipAddress,
            String userAgent,
            String requestId) {
        
        return geoLocationCacheService.getLocationFromIpWithCache(ipAddress)
            .flatMap(geoInfo -> {
                Map<String, Object> logData = createBaseLogData(
                    "SECURITY_INCIDENT", userId, documentId, ipAddress, userAgent, requestId, geoInfo
                );
                logData.put("threatType", threatType);
                logData.put("severity", severity);
                logData.put("description", description);
                logData.put("flagged", true);
                
                return writeToLogFile("security-incidents", logData)
                    .then(saveToDatabase(userId, documentId, "SECURITY_INCIDENT", ipAddress, userAgent, 
                        requestId, geoInfo.getFullLocation(), true));
            })
            .doOnSuccess(v -> log.warn(" Security incident logged - User: {}, Threat: {}, Severity: {}", 
                userId, threatType, severity))
            .then();
    }

    @Override
    public Mono<Void> logUnauthorizedAccess(
            String userId,
            String documentId,
            String attemptedAction,
            String ipAddress,
            String userAgent,
            String requestId) {
        
        return geoLocationCacheService.getLocationFromIpWithCache(ipAddress)
            .flatMap(geoInfo -> {
                Map<String, Object> logData = createBaseLogData(
                    "UNAUTHORIZED_ACCESS", userId, documentId, ipAddress, userAgent, requestId, geoInfo
                );
                logData.put("attemptedAction", attemptedAction);
                logData.put("flagged", true);
                
                return writeToLogFile("unauthorized-access", logData)
                    .then(saveToDatabase(userId, documentId, "UNAUTHORIZED_ACCESS", ipAddress, userAgent, 
                        requestId, geoInfo.getFullLocation(), true));
            })
            .doOnSuccess(v -> log.warn(" Unauthorized access logged - User: {}, Doc: {}, Action: {}", 
                userId, documentId, attemptedAction))
            .then();
    }

    @Override
    public Mono<Void> logValidationFailure(
            String userId,
            String documentTypeCode,
            String failureReason,
            String ipAddress,
            String userAgent,
            String requestId) {
        
        return geoLocationCacheService.getLocationFromIpWithCache(ipAddress)
            .flatMap(geoInfo -> {
                Map<String, Object> logData = createBaseLogData(
                    "VALIDATION_FAILURE", userId, null, ipAddress, userAgent, requestId, geoInfo
                );
                logData.put("documentTypeCode", documentTypeCode);
                logData.put("failureReason", failureReason);
                
                return writeToLogFile("validation-failures", logData);
            })
            .then();
    }

    // ========== Méthodes privées ==========

    private Map<String, Object> createBaseLogData(
            String event,
            String userId,
            String documentId,
            String ipAddress,
            String userAgent,
            String requestId,
            GeoLocationService.GeoLocationInfo geoInfo) {
        
        Map<String, Object> logData = new HashMap<>();
        logData.put("event", event);
        logData.put("timestamp", LocalDateTime.now().format(TIMESTAMP_FORMATTER));
        logData.put("userId", userId);
        logData.put("documentId", documentId);
        logData.put("ipAddress", ipAddress);
        logData.put("userAgent", userAgent);
        logData.put("requestId", requestId);
        
        // `Map.of` a ete RETIRE : il leve une NullPointerException sur toute
        // valeur nulle.
        //
        // LE DEFAUT CORRIGE, et sa portee : `GeoLocationServiceImpl` met
        // explicitement `city` et `isp` a `null` — son propre commentaire dit
        // « toujours nul avec GeoLite2 » pour le fournisseur d'acces, et
        // `infoMinimale` met la ville a nul pour toute adresse non resolue, donc
        // pour une adresse publique sans base MaxMind, et pour localhost.
        //
        // Consequence : le `flatMap` appelant levait l'exception AVANT d'ecrire
        // quoi que ce soit. Ni le journal, ni la ligne en base. SIX des sept
        // methodes de ce service etaient concernees — telechargement,
        // suppression, revue, incident de securite, acces non autorise, echec de
        // validation. Seul l'envoi fonctionnait, parce qu'il construisait sa
        // carte autrement, et son commentaire le disait : « HashMap pour
        // supporter les valeurs null ». Le risque etait donc connu, a un seul
        // endroit.
        //
        // Ce qui disparaissait en silence : la trace d'un acces non autorise et
        // celle d'un incident de securite, c'est-a-dire exactement ce qu'un
        // journal d'activite existe pour conserver.
        Map<String, Object> geo = new HashMap<>();
        geo.put("country", geoInfo.country());
        geo.put("city", geoInfo.city());
        geo.put("location", geoInfo.getFullLocation());
        geo.put("isp", geoInfo.isp());
        logData.put("geo", geo);

        // Ces trois-la sont des booleens primitifs, donc jamais nuls. Ecrits en
        // HashMap par coherence : deux facons de construire la meme carte dans
        // la meme methode invitent a remettre `Map.of` au-dessus.
        Map<String, Object> securite = new HashMap<>();
        securite.put("isSuspicious", geoInfo.isSuspicious());
        securite.put("isProxy", geoInfo.isProxy());
        securite.put("isVpn", geoInfo.isVpn());
        logData.put("security", securite);
        
        logData.put("device", parseUserAgent(userAgent));
        
        return logData;
    }

    /**
     * Émet l'enregistrement sur un logger dédié, au lieu de l'écrire à la main.
     *
     * <h3>Deux défauts que ce changement ferme</h3>
     *
     * <b>Un appel bloquant sur l'event loop.</b> La version précédente faisait
     * {@code Files.createDirectories} puis ouvrait un {@code FileWriter}, dans un
     * {@code Mono.fromRunnable} <b>sans {@code subscribeOn}</b>. Ces
     * entrées-sorties s'exécutaient donc sur le fil de l'abonné — l'event loop
     * Netty — et sur le chemin de chaque envoi de document. Le
     * {@code NoBlockingCallsTest} de ce module ne l'avait pas vu : il exerce
     * quatre services feuilles correctement isolés, et pas l'orchestration.
     *
     * <p><b>Un troisième magasin pour un même événement.</b> Le même fait était
     * écrit dans un fichier, dans MongoDB par {@code saveToDatabase}, et sur la
     * console par logback. Le fichier n'avait ni rotation, ni rétention, ni
     * expédition vers Loki, et s'accumulait sur le disque qui porte les
     * documents téléversés.
     *
     * <h3>Pourquoi un logger dédié</h3>
     *
     * Le contenu n'est pas perdu : il est plus riche que {@code MediaAccessLog}
     * — fournisseur d'accès, indicateurs proxy et VPN, navigateur et système
     * analysés. Le confier à logback lui donne la rotation, la rétention et
     * l'expédition vers Loki, déjà configurées. Un logger nommé plutôt que celui
     * de la classe, pour que ce flux reste filtrable et routable séparément de
     * la journalisation technique.
     *
     * <p>Une sérialisation qui échoue ne doit pas faire échouer l'opération
     * journalisée : l'échec est signalé et la chaîne continue.
     */
    private Mono<Void> writeToLogFile(String logType, Map<String, Object> logData) {
        return Mono.fromRunnable(() -> {
            logData.put("journal", logType);
            try {
                JOURNAL.info(objectMapper.writeValueAsString(logData));
            } catch (JsonProcessingException illisible) {
                log.warn(" Enregistrement d'activite non serialisable ({}) : {}",
                        logType, illisible.getMessage());
            }
        });
    }

    private Mono<Void> saveToDatabase(
            String userId,
            String mediaId,
            String action,
            String ipAddress,
            String userAgent,
            String requestId,
            String geolocation,
            Boolean suspicious) {
        
        MediaAccessLog accessLog = MediaAccessLog.builder()
            .userId(userId)
            .mediaId(mediaId)
            .action(action)
            .ipAddress(ipAddress)
            .userAgent(userAgent)
            .requestId(requestId)
            .timestamp(LocalDateTime.now())
            .geolocation(geolocation)
            .suspicious(suspicious)
            .build();
        
        return accessLogRepository.save(accessLog).then();
    }

    private Map<String, String> parseUserAgent(String userAgent) {
        Map<String, String> deviceInfo = new HashMap<>();
        
        if (userAgent == null || userAgent.isEmpty()) {
            deviceInfo.put("browser", "Unknown");
            deviceInfo.put("os", "Unknown");
            deviceInfo.put("device", "Unknown");
            return deviceInfo;
        }
        
        // Détection du navigateur
        if (userAgent.contains("Chrome")) {
            deviceInfo.put("browser", "Chrome");
        } else if (userAgent.contains("Firefox")) {
            deviceInfo.put("browser", "Firefox");
        } else if (userAgent.contains("Safari") && !userAgent.contains("Chrome")) {
            deviceInfo.put("browser", "Safari");
        } else if (userAgent.contains("Edge")) {
            deviceInfo.put("browser", "Edge");
        } else {
            deviceInfo.put("browser", "Other");
        }
        
        // Détection de l'OS
        if (userAgent.contains("Windows")) {
            deviceInfo.put("os", "Windows");
        } else if (userAgent.contains("Mac")) {
            deviceInfo.put("os", "macOS");
        } else if (userAgent.contains("Linux")) {
            deviceInfo.put("os", "Linux");
        } else if (userAgent.contains("Android")) {
            deviceInfo.put("os", "Android");
        } else if (userAgent.contains("iOS") || userAgent.contains("iPhone") || userAgent.contains("iPad")) {
            deviceInfo.put("os", "iOS");
        } else {
            deviceInfo.put("os", "Other");
        }
        
        // Détection du type de device
        if (userAgent.contains("Mobile") || userAgent.contains("Android") || userAgent.contains("iPhone")) {
            deviceInfo.put("device", "Mobile");
        } else if (userAgent.contains("Tablet") || userAgent.contains("iPad")) {
            deviceInfo.put("device", "Tablet");
        } else {
            deviceInfo.put("device", "Desktop");
        }
        
        deviceInfo.put("raw", userAgent);
        
        return deviceInfo;
    }
}
