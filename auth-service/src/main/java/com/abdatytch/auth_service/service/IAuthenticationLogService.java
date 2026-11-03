package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.GeoLocationData;
import com.abdatytch.auth_service.entities.AuthenticationLog;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Journal d'authentification.
 *
 * <p>Les méthodes d'écriture renvoient {@code Mono<Void>} : la journalisation est un
 * effet de bord dont l'appelant décide s'il l'attend ou non. Sur les chemins d'erreur
 * (identifiants invalides), on la chaîne avant de propager l'erreur, afin de ne pas
 * perdre la trace.
 */
public interface IAuthenticationLogService {

    Mono<Void> logAuthenticationEvent(
            String userId,
            String phoneNumber,
            EventType eventType,
            Status status,
            String ipAddress,
            String userAgent,
            String errorMessage
    );

    Mono<Void> logAuthenticationEvent(
            String userId,
            String phoneNumber,
            EventType eventType,
            Status status,
            String ipAddress,
            String userAgent,
            String deviceInfo,
            String location,
            String errorMessage
    );

    Mono<Void> logAuthenticationEvent(
            String userId,
            String phoneNumber,
            EventType eventType,
            Status status,
            String ipAddress,
            String userAgent,
            GeoLocationData geoData,
            String errorMessage
    );

    Flux<AuthenticationLog> getLogsByPhoneNumber(String phoneNumber);

    Mono<Void> cleanupOldLogs(int daysToKeep);

    Mono<Long> countFailedLoginAttempts(String phoneNumber, LocalDateTime since);

    Flux<AuthenticationLog> getLogsByUserId(String userId);

    Flux<AuthenticationLog> getLogsByEventTypeAndStatus(EventType eventType, Status status);

    Flux<AuthenticationLog> getAllLogs();
}
