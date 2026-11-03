package com.abdatytch.media_service.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Gestionnaire global des exceptions pour WebFlux
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MediaNotFoundException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleMediaNotFound(MediaNotFoundException ex) {
        log.error("Media not found: {}", ex.getMessage());
        return Mono.just(ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage())));
    }

    @ExceptionHandler(UnauthorizedAccessException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleUnauthorizedAccess(UnauthorizedAccessException ex) {
        log.error("Unauthorized access: {}", ex.getMessage());
        return Mono.just(ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(buildErrorResponse(HttpStatus.FORBIDDEN, ex.getMessage())));
    }

    @ExceptionHandler(InvalidFileTypeException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleInvalidFileType(InvalidFileTypeException ex) {
        log.error("Invalid file type: {}", ex.getMessage());
        return Mono.just(ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage())));
    }

    @ExceptionHandler(StorageException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleStorageException(StorageException ex) {
        log.error("Storage error: {}", ex.getMessage(), ex);
        return Mono.just(ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage())));
    }

    @ExceptionHandler(org.springframework.web.server.MissingRequestValueException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleMissingRequestValue(
            org.springframework.web.server.MissingRequestValueException ex) {
        log.error("Missing request value: {}", ex.getMessage());
        return Mono.just(ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getReason())));
    }

    @ExceptionHandler(org.springframework.web.bind.support.WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleValidationException(
            org.springframework.web.bind.support.WebExchangeBindException ex) {
        log.error("Validation error: {}", ex.getMessage());
        String message = ex.getBindingResult().getAllErrors().stream()
            .map(error -> error.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Erreur de validation");
        return Mono.just(ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse(HttpStatus.BAD_REQUEST, message)));
    }

    @ExceptionHandler(org.springframework.web.server.ServerWebInputException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleServerWebInputException(
            org.springframework.web.server.ServerWebInputException ex) {
        log.error("Invalid input: {}", ex.getMessage());
        String message = ex.getReason() != null ? ex.getReason() : "Données d'entrée invalides";
        
        // Extraire les détails de la cause
        if (ex.getCause() != null) {
            Throwable cause = ex.getCause();
            
            // Cas 1: TypeMismatchException (conversion de type)
            if (cause instanceof org.springframework.beans.TypeMismatchException) {
                org.springframework.beans.TypeMismatchException tmEx = (org.springframework.beans.TypeMismatchException) cause;
                if (tmEx.getRequiredType() != null && tmEx.getRequiredType().isEnum()) {
                    Object[] enumConstants = tmEx.getRequiredType().getEnumConstants();
                    String validValues = java.util.Arrays.stream(enumConstants)
                        .map(Object::toString)
                        .collect(java.util.stream.Collectors.joining(", "));
                    message = String.format("Valeur invalide: '%s'. Valeurs autorisées: %s", 
                        tmEx.getValue(), validValues);
                } else {
                    message = String.format("Type de paramètre invalide: '%s' ne peut pas être converti en %s", 
                        tmEx.getValue(), 
                        tmEx.getRequiredType() != null ? tmEx.getRequiredType().getSimpleName() : "type attendu");
                }
            }
            // Cas 2: IllegalArgumentException (souvent pour les enums)
            else if (cause instanceof IllegalArgumentException && cause.getMessage() != null) {
                String causeMsg = cause.getMessage();
                if (causeMsg.contains("No enum constant")) {
                    String enumValue = causeMsg.substring(causeMsg.lastIndexOf('.') + 1);
                    message = String.format("Valeur enum invalide: '%s'. Vérifiez l'orthographe (ex: RESTAURANT, DRIVER, USER).", enumValue);
                } else {
                    message = causeMsg;
                }
            }
            // Cas 3: Autres erreurs de parsing
            else if (cause.getMessage() != null) {
                String causeMsg = cause.getMessage();
                if (causeMsg.contains("JSON")) {
                    message = "Format JSON invalide: " + causeMsg;
                } else if (causeMsg.contains("Required request body")) {
                    message = "Le corps de la requête est obligatoire";
                } else if (!causeMsg.equals("Type mismatch.")) {
                    message = causeMsg;
                }
            }
        }
        
        return Mono.just(ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse(HttpStatus.BAD_REQUEST, message)));
    }
    
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.error("Failed to read HTTP message: {}", ex.getMessage());
        String message = "Format de données invalide";
        
        if (ex.getMessage() != null) {
            if (ex.getMessage().contains("JSON")) {
                message = "Format JSON invalide. Vérifiez la syntaxe de votre requête.";
            } else if (ex.getMessage().contains("Required request body")) {
                message = "Le corps de la requête est obligatoire";
            }
        }
        
        return Mono.just(ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse(HttpStatus.BAD_REQUEST, message)));
    }
    
    @ExceptionHandler(org.springframework.web.server.UnsupportedMediaTypeStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleUnsupportedMediaType(
            org.springframework.web.server.UnsupportedMediaTypeStatusException ex) {
        log.error("Unsupported media type: {}", ex.getMessage());
        return Mono.just(ResponseEntity
            .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(buildErrorResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, 
                "Type de contenu non supporté. Utilisez 'application/json' ou 'multipart/form-data'")));
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleIllegalArgument(IllegalArgumentException ex) {
        log.error("Illegal argument: {}", ex.getMessage());
        String message = ex.getMessage();
        
        if (message != null && message.contains("No enum constant")) {
            String enumValue = message.substring(message.lastIndexOf('.') + 1);
            message = String.format("Valeur invalide: '%s'. Vérifiez l'orthographe et les valeurs autorisées.", enumValue);
        }
        
        return Mono.just(ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse(HttpStatus.BAD_REQUEST, message)));
    }
    
    @ExceptionHandler(org.springframework.beans.TypeMismatchException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleTypeMismatch(
            org.springframework.beans.TypeMismatchException ex) {
        log.error("Type mismatch: {}", ex.getMessage());
        String message = String.format("Type de paramètre invalide: '%s' ne peut pas être converti en %s", 
            ex.getValue(), 
            ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "type attendu");
        
        if (ex.getCause() instanceof IllegalArgumentException && ex.getCause().getMessage() != null) {
            String causeMsg = ex.getCause().getMessage();
            if (causeMsg.contains("No enum constant")) {
                String enumValue = causeMsg.substring(causeMsg.lastIndexOf('.') + 1);
                message = String.format("Valeur enum invalide: '%s'. Vérifiez l'orthographe (ex: RESTAURANT, DRIVER, USER).", enumValue);
            }
        }
        
        return Mono.just(ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse(HttpStatus.BAD_REQUEST, message)));
    }
    
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleMethodArgumentTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.error("Method argument type mismatch: {}", ex.getMessage());
        String message = String.format("Paramètre '%s' invalide: '%s' n'est pas une valeur valide", 
            ex.getName(), 
            ex.getValue());
        
        if (ex.getRequiredType() != null && ex.getRequiredType().isEnum()) {
            Object[] enumConstants = ex.getRequiredType().getEnumConstants();
            String validValues = java.util.Arrays.stream(enumConstants)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.joining(", "));
            message = String.format("Valeur invalide pour '%s': '%s'. Valeurs autorisées: %s", 
                ex.getName(), ex.getValue(), validValues);
        }
        
        return Mono.just(ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse(HttpStatus.BAD_REQUEST, message)));
    }
    
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleResponseStatusException(
            org.springframework.web.server.ResponseStatusException ex) {
        log.error("Response status exception: {}", ex.getMessage());
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        
        // Extraire les détails de l'erreur de conversion de type
        if (ex.getCause() != null) {
            Throwable cause = ex.getCause();
            if (cause instanceof org.springframework.beans.TypeMismatchException) {
                org.springframework.beans.TypeMismatchException tmEx = (org.springframework.beans.TypeMismatchException) cause;
                if (tmEx.getRequiredType() != null && tmEx.getRequiredType().isEnum()) {
                    Object[] enumConstants = tmEx.getRequiredType().getEnumConstants();
                    String validValues = java.util.Arrays.stream(enumConstants)
                        .map(Object::toString)
                        .collect(java.util.stream.Collectors.joining(", "));
                    message = String.format("Valeur invalide: '%s'. Valeurs autorisées: %s", 
                        tmEx.getValue(), validValues);
                }
            } else if (cause.getMessage() != null && cause.getMessage().contains("No enum constant")) {
                String causeMsg = cause.getMessage();
                String enumValue = causeMsg.substring(causeMsg.lastIndexOf('.') + 1);
                message = String.format("Valeur enum invalide: '%s'. Vérifiez l'orthographe (ex: RESTAURANT, DRIVER, USER).", enumValue);
            }
        }
        
        return Mono.just(ResponseEntity
            .status(ex.getStatusCode())
            .body(buildErrorResponse(HttpStatus.valueOf(ex.getStatusCode().value()), message)));
    }

    @ExceptionHandler(NullPointerException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleNullPointer(NullPointerException ex) {
        log.error("Null pointer exception: {}", ex.getMessage(), ex);
        
        String message = "Données manquantes ou invalides";
        if (ex.getMessage() != null) {
            if (ex.getMessage().contains("documentType")) {
                message = "Type de document invalide ou manquant. Vérifiez le paramètre 'documentTypeCode'.";
            } else if (ex.getMessage().contains("userId")) {
                message = "Utilisateur non identifié. Authentification requise.";
            } else {
                message = "Données manquantes: " + ex.getMessage();
            }
        }
        
        return Mono.just(ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse(HttpStatus.BAD_REQUEST, message)));
    }
    
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleDuplicateKey(
            org.springframework.dao.DuplicateKeyException ex) {
        log.error("Duplicate key exception: {}", ex.getMessage());
        
        String message = "Conflit de données - un enregistrement similaire existe déjà";
        if (ex.getMessage() != null) {
            if (ex.getMessage().contains("idx_user_doctype_code")) {
                message = "Vous avez déjà uploadé ce type de document. Supprimez l'ancien document avant d'en uploader un nouveau.";
            } else if (ex.getMessage().contains("duplicate key")) {
                message = "Cette donnée existe déjà dans le système.";
            }
        }
        
        return Mono.just(ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(buildErrorResponse(HttpStatus.CONFLICT, message)));
    }
    
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        // Retourner le vrai message d'erreur au lieu d'un message générique
        String message = ex.getMessage() != null ? ex.getMessage() : "Une erreur inattendue s'est produite";
        return Mono.just(ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, message)));
    }

    private Map<String, Object> buildErrorResponse(HttpStatus status, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", status.value());
        error.put("error", status.getReasonPhrase());
        error.put("message", message);
        return error;
    }
}
