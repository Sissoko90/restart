package com.abdatytch.quotas_service.exception;

import com.abdatytch.quotas_service.constant.QuotasConstants;
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

    @ExceptionHandler(QuotaExceededException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleQuotaExceeded(QuotaExceededException ex) {
        log.warn("{}", ex.getMessage());

        Map<String, Object> body = baseBody(HttpStatus.TOO_MANY_REQUESTS,
                QuotasConstants.ERROR_QUOTA_EXCEEDED, ex.getMessage());
        body.put("resource", ex.getResource());
        body.put("used", ex.getUsed());
        body.put("limit", ex.getLimit());
        body.put("requested", ex.getRequested());

        return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body));
    }

    @ExceptionHandler(QuotaNotFoundException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleQuotaNotFound(QuotaNotFoundException ex) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(baseBody(HttpStatus.NOT_FOUND, QuotasConstants.ERROR_QUOTA_NOT_FOUND, ex.getMessage())));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleValidation(WebExchangeBindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " : " + error.getDefaultMessage())
                .reduce((a, b) -> a + " ; " + b)
                .orElse("Requête invalide");

        return Mono.just(ResponseEntity.badRequest()
                .body(baseBody(HttpStatus.BAD_REQUEST, QuotasConstants.ERROR_INVALID_RESOURCE, message)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleIllegalArgument(IllegalArgumentException ex) {
        return Mono.just(ResponseEntity.badRequest()
                .body(baseBody(HttpStatus.BAD_REQUEST, QuotasConstants.ERROR_INVALID_RESOURCE, ex.getMessage())));
    }

    private Map<String, Object> baseBody(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
