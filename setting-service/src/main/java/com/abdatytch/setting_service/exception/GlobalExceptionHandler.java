package com.abdatytch.setting_service.exception;

import com.abdatytch.setting_service.constant.SettingsConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**

 * Traduction des exceptions métier en réponses HTTP.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(SettingNotFoundException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleNotFound(SettingNotFoundException ex) {
        log.warn("{}", ex.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body(HttpStatus.NOT_FOUND, SettingsConstants.ERROR_SETTING_NOT_FOUND, ex.getMessage())));
    }

    @ExceptionHandler(InvalidSettingValueException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleInvalidValue(InvalidSettingValueException ex) {
        log.warn("{}", ex.getMessage());
        return Mono.just(ResponseEntity.badRequest()
                .body(body(HttpStatus.BAD_REQUEST, SettingsConstants.ERROR_INVALID_VALUE, ex.getMessage())));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleValidation(WebExchangeBindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " : " + error.getDefaultMessage())
                .reduce((a, b) -> a + " ; " + b)
                .orElse("Requête invalide");

        return Mono.just(ResponseEntity.badRequest()
                .body(body(HttpStatus.BAD_REQUEST, SettingsConstants.ERROR_INVALID_VALUE, message)));
    }

    private Map<String, Object> body(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
