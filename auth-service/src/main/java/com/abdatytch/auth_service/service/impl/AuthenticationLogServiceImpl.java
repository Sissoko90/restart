package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.dto.request.GeoLocationData;
import com.abdatytch.auth_service.dto.response.DeviceInfo;
import com.abdatytch.auth_service.entities.AuthenticationLog;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import com.abdatytch.auth_service.repository.AuthenticationLogRepository;
import com.abdatytch.auth_service.service.IAuthenticationLogService;
import com.abdatytch.auth_service.service.IDeviceInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Journal d'authentification, en écriture réactive.
 *
 * <p>Aucune erreur d'écriture du journal ne doit faire échouer une authentification :
 * les écritures sont donc terminées par un {@code onErrorResume} qui journalise
 * localement et poursuit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationLogServiceImpl implements IAuthenticationLogService {

    private final AuthenticationLogRepository authenticationLogRepository;
    private final IDeviceInfoService deviceInfoService;

    @Override
    @Transactional
    public Mono<Void> logAuthenticationEvent(
            String userId,
            String phoneNumber,
            EventType eventType,
            Status status,
            String ipAddress,
            String userAgent,
            String errorMessage
    ) {
        return logAuthenticationEvent(userId, phoneNumber, eventType, status,
                ipAddress, userAgent, null, null, errorMessage);
    }

    @Override
    @Transactional
    public Mono<Void> logAuthenticationEvent(
            String userId,
            String phoneNumber,
            EventType eventType,
            Status status,
            String ipAddress,
            String userAgent,
            String deviceInfo,
            String location,
            String errorMessage
    ) {
        return Mono.fromCallable(() -> {
            log.info(ServiceConstants.LOG_INFO_LOGGING_AUTH_EVENT, eventType, userId, status);

            DeviceInfo parsedDeviceInfo = parseDeviceInfo(userAgent);
            String finalDeviceInfo = deviceInfo != null
                    ? deviceInfo
                    : describeDevice(parsedDeviceInfo, userAgent);

            AuthenticationLog.AuthenticationLogBuilder builder = baseBuilder(
                    userId, phoneNumber, eventType, status, ipAddress, userAgent,
                    finalDeviceInfo, errorMessage);

            builder.location(location != null ? location : "Unknown");
            applyDeviceDetails(builder, parsedDeviceInfo);

            return builder.build();
        }).flatMap(this::persist);
    }

    @Override
    @Transactional
    public Mono<Void> logAuthenticationEvent(
            String userId,
            String phoneNumber,
            EventType eventType,
            Status status,
            String ipAddress,
            String userAgent,
            GeoLocationData geoData,
            String errorMessage
    ) {
        return Mono.fromCallable(() -> {
            log.info(ServiceConstants.LOG_INFO_LOGGING_AUTH_EVENT, eventType, userId, status);

            DeviceInfo parsedDeviceInfo = parseDeviceInfo(userAgent);

            AuthenticationLog.AuthenticationLogBuilder builder = baseBuilder(
                    userId, phoneNumber, eventType, status, ipAddress, userAgent,
                    describeDevice(parsedDeviceInfo, userAgent), errorMessage);

            applyDeviceDetails(builder, parsedDeviceInfo);

            if (geoData != null) {
                builder.location(geoData.getFormattedLocation())
                       .country(geoData.getCountry())
                       .countryCode(geoData.getCountryCode())
                       .region(geoData.getRegion())
                       .city(geoData.getCity())
                       .latitude(geoData.getLatitude())
                       .longitude(geoData.getLongitude())
                       .timezone(geoData.getTimezone())
                       .isp(geoData.getIsp())
                       .organization(geoData.getOrganization())
                       .asn(geoData.getAsn());
            } else {
                builder.location("Unknown");
            }

            return builder.build();
        }).flatMap(this::persist);
    }

    /**
     * Écrit le journal sans jamais propager d'erreur : une panne d'écriture ne doit
     * pas transformer une authentification réussie en échec.
     */
    private Mono<Void> persist(AuthenticationLog authLog) {
        return authenticationLogRepository.save(authLog)
                .then()
                .onErrorResume(error -> {
                    log.error("Échec d'écriture du journal d'authentification : {}", error.getMessage());
                    return Mono.empty();
                });
    }

    private AuthenticationLog.AuthenticationLogBuilder baseBuilder(
            String userId, String phoneNumber, EventType eventType, Status status,
            String ipAddress, String userAgent, String deviceInfo, String errorMessage) {

        return AuthenticationLog.builder()
                .userId(userId)
                .phoneNumber(phoneNumber)
                .eventType(eventType)
                .status(status)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .deviceInfo(deviceInfo)
                .errorMessage(errorMessage)
                .timestamp(LocalDateTime.now());
    }

    private DeviceInfo parseDeviceInfo(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        try {
            return deviceInfoService.parseUserAgent(userAgent);
        } catch (Exception e) {
            log.warn("Failed to parse device info: {}", e.getMessage());
            return null;
        }
    }

    private String describeDevice(DeviceInfo parsed, String userAgent) {
        if (parsed != null) {
            return parsed.getFormattedDeviceInfo();
        }
        return userAgent != null ? extractDeviceInfo(userAgent) : null;
    }

    private void applyDeviceDetails(AuthenticationLog.AuthenticationLogBuilder builder, DeviceInfo parsed) {
        if (parsed == null) {
            return;
        }
        builder.browserFamily(parsed.getBrowserFamily())
               .browserVersion(parsed.getBrowserVersion())
               .osFamily(parsed.getOsFamily())
               .osVersion(parsed.getOsVersion())
               .deviceFamily(parsed.getDeviceFamily())
               .deviceType(parsed.getDeviceType());
    }

    /**
     * Repli si l'analyse fine du User-Agent échoue.
     */
    private String extractDeviceInfo(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return "Unknown Device";
        }

        if (userAgent.contains("Mobile") || userAgent.contains("Android") || userAgent.contains("iPhone")) {
            if (userAgent.contains("Android")) {
                return "Android Mobile";
            } else if (userAgent.contains("iPhone")) {
                return "iPhone";
            } else if (userAgent.contains("iPad")) {
                return "iPad";
            }
            return "Mobile Device";
        } else if (userAgent.contains("Windows")) {
            return "Windows Desktop";
        } else if (userAgent.contains("Macintosh") || userAgent.contains("Mac OS")) {
            return "Mac Desktop";
        } else if (userAgent.contains("Linux")) {
            return "Linux Desktop";
        }

        return "Desktop Browser";
    }

    @Override
    public Mono<Long> countFailedLoginAttempts(String phoneNumber, LocalDateTime since) {
        return authenticationLogRepository.countByPhoneNumberAndStatusAndTimestampAfter(
                phoneNumber, Status.FAILED, since);
    }

    @Override
    public Flux<AuthenticationLog> getLogsByUserId(String userId) {
        log.info(ServiceConstants.LOG_INFO_LOGGING_AUTH_EVENT, "GET", userId, "ALL");
        return authenticationLogRepository.findByUserIdOrderByTimestampDesc(userId);
    }

    @Override
    public Flux<AuthenticationLog> getLogsByPhoneNumber(String phoneNumber) {
        log.info(ServiceConstants.LOG_INFO_LOGGING_AUTH_EVENT, "GET", phoneNumber, "ALL");
        return authenticationLogRepository.findByPhoneNumberOrderByTimestampDesc(phoneNumber);
    }

    @Override
    public Flux<AuthenticationLog> getLogsByEventTypeAndStatus(EventType eventType, Status status) {
        log.info(ServiceConstants.LOG_INFO_LOGGING_AUTH_EVENT, eventType, "ALL", status);
        return authenticationLogRepository.findByEventTypeAndStatusOrderByTimestampDesc(eventType, status);
    }

    @Override
    public Flux<AuthenticationLog> getAllLogs() {
        log.info(ServiceConstants.LOG_INFO_LOGGING_AUTH_EVENT, "GET_ALL", "ALL", "ALL");
        return authenticationLogRepository.findAll();
    }

    @Override
    @Transactional
    public Mono<Void> cleanupOldLogs(int daysToKeep) {
        log.info(ServiceConstants.LOG_INFO_RESENDING_CODE, String.valueOf(daysToKeep));
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);
        return authenticationLogRepository.deleteByTimestampBefore(cutoffDate);
    }
}
