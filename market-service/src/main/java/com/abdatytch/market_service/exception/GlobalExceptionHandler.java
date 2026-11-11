package com.abdatytch.market_service.exception;

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
 * Les erreurs rendues par ce service.
 *
 * <h2>{@code ResponseStatusException} est traite EXPLICITEMENT</h2>
 *
 * C'est une {@code RuntimeException}. Un conseil global qui ne declarerait
 * qu'un gestionnaire sur {@code Exception.class} l'attraperait, et <b>tout
 * refus deviendrait un 500</b>. Ce n'est pas une precaution theorique : c'est
 * exactement ce qui se passait dans interface-service, ou le 401 du
 * rafraichissement de jeton sortait en 500 — et son test ne pouvait pas le voir
 * parce qu'il liait le controleur SANS le conseil.
 *
 * <h2>Aucun message interne ne franchit la frontiere</h2>
 *
 * Les refus portent le libelle du statut, pas {@code ex.getReason()}. Un
 * message interne nomme des classes, des tables, parfois un identifiant.
 *
 * <h2>Une seule exception, declaree par un TYPE</h2>
 *
 * {@link RefusExplique} rend son motif. Un refus de boutique sans motif se
 * represente a l'identique, et fait deux fois le travail de la moderation.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Un refus dont le motif a ete REDIGE pour l'appelant.
     *
     * <p>Borne par le type lui-meme : son constructeur refuse 401, 403 et 5xx.
     * Declare avant le gestionnaire de {@code ResponseStatusException} dont il
     * herite ; Spring choisit de toute façon le type le plus specifique, et
     * l'ordre de lecture dit l'intention.
     */
    @ExceptionHandler(RefusExplique.class)
    public Mono<ResponseEntity<Map<String, Object>>> refusExplique(RefusExplique ex) {
        HttpStatus statut = resoudre(ex.getStatusCode().value(), HttpStatus.BAD_REQUEST);
        log.warn("Refus {} : {}", statut.value(), ex.getReason());
        return Mono.just(ResponseEntity.status(statut)
                .body(corps(statut.name(), ex.getReason(), statut)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> refusAvecStatut(
            ResponseStatusException ex) {
        HttpStatus statut = resoudre(ex.getStatusCode().value(),
                HttpStatus.INTERNAL_SERVER_ERROR);
        log.warn("Refus {} : {}", statut.value(), ex.getReason());
        return Mono.just(ResponseEntity.status(statut)
                .body(corps(statut.name(), statut.getReasonPhrase(), statut)));
    }

    /** Validation de requete : 400, avec le detail des champs fautifs. */
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> validation(WebExchangeBindException ex) {
        Map<String, Object> champs = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(erreur -> champs.put(erreur.getField(), erreur.getDefaultMessage()));

        log.warn("Requete invalide : {}", champs.keySet());

        Map<String, Object> reponse = corps("VALIDATION_ERROR", "Requete invalide",
                HttpStatus.BAD_REQUEST);
        // Les noms de champs et leurs messages de validation sont rendus : ils
        // decrivent la requete de l'appelant, pas l'interieur du service.
        reponse.put("champs", champs);
        return Mono.just(ResponseEntity.badRequest().body(reponse));
    }

    /**
     * Tout le reste : 500, sans divulguer le message.
     *
     * <p>Le message est journalise AVEC sa pile — seule trace exploitable d'un
     * incident — et ne part pas au client.
     */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> inattendue(Exception ex) {
        log.error("Erreur inattendue : {}", ex.getMessage(), ex);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(corps("INTERNAL_ERROR", "Une erreur interne s'est produite",
                        HttpStatus.INTERNAL_SERVER_ERROR)));
    }

    private static HttpStatus resoudre(int code, HttpStatus defaut) {
        HttpStatus statut = HttpStatus.resolve(code);
        return statut == null ? defaut : statut;
    }

    private Map<String, Object> corps(String code, String message, HttpStatus statut) {
        Map<String, Object> reponse = new HashMap<>();
        reponse.put("timestamp", LocalDateTime.now());
        reponse.put("status", statut.value());
        reponse.put("error", statut.getReasonPhrase());
        reponse.put("errorCode", code);
        reponse.put("message", message);
        reponse.put("service", "market-service");
        return reponse;
    }
}
