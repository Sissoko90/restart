package com.abdatytch.interface_service.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
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
     * Une ressource désignée par un identifiant non devinable : 404.
     *
     * <p>Déclaré <b>avant</b> {@link #handleBusinessException} dans l'ordre de
     * lecture, mais ce n'est pas l'ordre qui décide : Spring choisit le
     * gestionnaire dont le type est le plus <b>spécifique</b>. Comme
     * {@code RessourceIntrouvableException} dérive de {@code BusinessException},
     * c'est bien celui-ci qui gagne — même mécanisme que pour
     * {@link #handleProjectLimitExceeded}.
     */
    @ExceptionHandler(RessourceIntrouvableException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleRessourceIntrouvable(
            RessourceIntrouvableException ex) {
        // DEBUG et non WARN : demander une ressource absente est du trafic
        // normal — un lien perime, un projet supprime. En WARN, ces lignes
        // noieraient les avertissements qui demandent une action.
        log.debug("Ressource introuvable: {}", ex.getMessage());

        Map<String, Object> errorResponse = createErrorResponse(
            ex.getErrorCode(),
            ex.getMessage(),
            HttpStatus.NOT_FOUND
        );

        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse));
    }

    /**
     * Un refus qui porte déjà son statut : on le respecte.
     *
     * <h3>LE DÉFAUT CORRIGÉ, et il annulait un correctif précédent</h3>
     *
     * {@code ResponseStatusException} est une {@code RuntimeException}. Sans ce
     * gestionnaire, elle tombait dans {@link #handleGenericException}, déclaré
     * sur {@code Exception.class}, et <b>tout refus devenait un 500</b>.
     *
     * <p>Mesuré avec {@code WebTestClient.bindToController(...)
     * .controllerAdvice(new GlobalExceptionHandler())} :
     *
     * <pre>
     * ResponseStatusException(UNAUTHORIZED) -&gt; 500
     * ResponseStatusException(NOT_FOUND)    -&gt; 500
     * </pre>
     *
     * <p>Conséquence concrète : {@code AuthController} rend son refus de
     * rafraîchissement par {@code Mono.error(new ResponseStatusException(
     * UNAUTHORIZED, ...))}. Ce 401 — ajouté précisément pour qu'un jeton périmé
     * ne passe pas pour une panne — sortait en 500. Le correctif était juste
     * dans le contrôleur et défait par le conseil global.
     *
     * <p>Et son test ne pouvait pas le voir : il lie le contrôleur par
     * {@code bindToController} <b>sans</b> {@code .controllerAdvice(...)}, donc
     * il mesure un assemblage qui n'existe pas en production. C'est la même
     * leçon que le préfixe de liste noire — un test qui n'exerce pas le montage
     * réel atteste de ce qu'il a lui-même construit.
     *
     * <h3>Pourquoi le motif n'est pas rendu</h3>
     *
     * Le corps porte le libellé du statut, pas {@code ex.getReason()}. Les
     * appels concernés sont des refus d'authentification, et deux causes
     * différentes doivent donner la même réponse — sans quoi le statut et le
     * texte renseignent sur l'existence d'un jeton ou d'un compte. Le motif
     * réel est journalisé.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleResponseStatus(
            ResponseStatusException ex) {
        HttpStatus statut = HttpStatus.resolve(ex.getStatusCode().value());
        if (statut == null) {
            statut = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        log.warn("Refus {} : {}", statut.value(), ex.getReason());

        Map<String, Object> errorResponse = createErrorResponse(
            statut.name(),
            statut.getReasonPhrase(),
            statut
        );

        return Mono.just(ResponseEntity.status(statut).body(errorResponse));
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
