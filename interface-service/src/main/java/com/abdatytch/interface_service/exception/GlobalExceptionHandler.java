package com.abdatytch.interface_service.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Gestionnaire global des exceptions pour l'interface-service
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Gère les exceptions métier
     */
    @ExceptionHandler(BusinessException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleBusinessException(BusinessException ex) {
        log.warn("Exception métier: {} - Code: {}", ex.getMessage(), ex.getErrorCode());
        
        Map<String, Object> errorResponse = createErrorResponse(
            ex.getErrorCode(),
            ex.getMessage(),
            HttpStatus.BAD_REQUEST
        );
        
        return Mono.just(ResponseEntity.badRequest().body(errorResponse));
    }

    /**
     * Gère les exceptions de limite de projets
     */
    @ExceptionHandler(ProjectLimitExceededException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleProjectLimitExceeded(ProjectLimitExceededException ex) {
        log.warn("Limite de projets atteinte: {}", ex.getMessage());
        
        Map<String, Object> errorResponse = createErrorResponse(
            ex.getErrorCode(),
            ex.getMessage(),
            HttpStatus.FORBIDDEN
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse));
    }

    /**
     * Gère les erreurs de validation
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleValidationException(WebExchangeBindException ex) {
        log.warn("Erreur de validation: {}", ex.getMessage());
        
        Map<String, Object> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> 
            errors.put(error.getField(), error.getDefaultMessage())
        );
        
        Map<String, Object> errorResponse = createErrorResponse(
            "VALIDATION_ERROR",
            "Erreurs de validation",
            HttpStatus.BAD_REQUEST
        );
        errorResponse.put("fieldErrors", errors);
        
        return Mono.just(ResponseEntity.badRequest().body(errorResponse));
    }

    /**
     * Gère les exceptions génériques
     */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleGenericException(Exception ex) {
        log.error("Erreur inattendue: {}", ex.getMessage(), ex);
        
        Map<String, Object> errorResponse = createErrorResponse(
            "INTERNAL_ERROR",
            "Une erreur interne s'est produite",
            HttpStatus.INTERNAL_SERVER_ERROR
        );
        
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse));
    }

    /**
     * Crée une réponse d'erreur standardisée
     */
    private Map<String, Object> createErrorResponse(String errorCode, String message, HttpStatus status) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", status.value());
        errorResponse.put("error", status.getReasonPhrase());
        errorResponse.put("errorCode", errorCode);
        errorResponse.put("message", message);
        errorResponse.put("service", "interface-service");
        
        return errorResponse;
    }
}
