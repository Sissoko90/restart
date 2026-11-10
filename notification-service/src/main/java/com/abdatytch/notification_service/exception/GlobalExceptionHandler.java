package com.abdatytch.notification_service.exception;

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
 * <h2>{@code ResponseStatusException} est traite EXPLICITEMENT, et c'est la
 * lecon d'un defaut recent</h2>
 *
 * {@code ResponseStatusException} est une {@code RuntimeException}. Un conseil
 * global qui ne declare qu'un gestionnaire sur {@code Exception.class}
 * l'attrape donc, et <b>tout refus devient un 500</b>.
 *
 * <p>Ce n'est pas une precaution theorique : c'est exactement ce qui se passait
 * dans interface-service. Mesure faite avec
 * {@code WebTestClient.bindToController(...).controllerAdvice(...)} :
 *
 * <pre>
 * ResponseStatusException(UNAUTHORIZED) -&gt; 500
 * ResponseStatusException(NOT_FOUND)    -&gt; 500
 * </pre>
 *
 * <p>Le 401 du rafraichissement de jeton, ajoute precisement pour qu'un jeton
 * perime ne passe pas pour une panne, sortait en 500. Et son test ne pouvait pas
 * le voir : il liait le controleur <b>sans</b> le conseil, donc il exercait un
 * assemblage qui n'existe pas en production.
 *
 * <h2>Aucun message interne ne franchit la frontiere</h2>
 *
 * Les refus portent le libelle du statut, pas {@code ex.getReason()}. Deux
 * raisons : les causes d'un refus d'authentification ne doivent pas etre
 * distinguables, et un message interne nomme des classes, des tables, parfois
 * des adresses. Le motif reel va au journal.
 *
 * <h2>Une seule exception, et elle est declaree par un TYPE</h2>
 *
 * {@link RefusExplique} rend son motif a l'appelant. Il existe parce que la
 * regle ci-dessus s'appliquait aussi aux motifs REDIGES pour l'appelant, qui
 * sont d'une autre nature : {@code AdminModeleController} refusait une clef de
 * modele invalide avec un texte disant exactement ce qui n'allait pas, et
 * l'exploitant recevait « Bad Request ». Un motif precis que personne ne lit
 * revient a ne pas l'ecrire.
 *
 * <p>Un TYPE et non un drapeau : ce gestionnaire ne peut pas deviner si un
 * {@code getReason()} a ete redige pour l'appelant ou recopie d'une cause
 * interne — Spring lui-meme en pose dans ses propres refus.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Un refus qui porte deja son statut : on le respecte.
     *
     * <p>Declare AVANT le gestionnaire generique dans l'ordre de lecture, mais
     * ce n'est pas l'ordre qui decide : Spring choisit le gestionnaire dont le
     * type est le plus specifique.
     */
    /**
     * Un refus dont le motif a ete REDIGE pour l'appelant.
     *
     * <p>Le motif est rendu TEL QUEL. C'est l'exception a la regle de cette
     * classe, et elle est bornee par le type lui-meme : le constructeur de
     * {@link RefusExplique} refuse 401, 403 et tout statut 5xx. Ce qui passe
     * par ici a donc ete ecrit pour etre lu par celui qui a fait la demande.
     *
     * <p>Declare avant le gestionnaire de {@code ResponseStatusException} dont
     * {@code RefusExplique} herite. Spring choisit de toute façon le
     * gestionnaire du type le plus specifique ; l'ordre de lecture ici dit
     * l'intention.
     */
    @ExceptionHandler(RefusExplique.class)
    public Mono<ResponseEntity<Map<String, Object>>> refusExplique(RefusExplique ex) {
        HttpStatus statut = HttpStatus.resolve(ex.getStatusCode().value());
        if (statut == null) {
            statut = HttpStatus.BAD_REQUEST;
        }
        log.warn("Refus {} : {}", statut.value(), ex.getReason());
        return Mono.just(ResponseEntity.status(statut)
                .body(corps(statut.name(), ex.getReason(), statut)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> refusAvecStatut(
            ResponseStatusException ex) {
        HttpStatus statut = HttpStatus.resolve(ex.getStatusCode().value());
        if (statut == null) {
            statut = HttpStatus.INTERNAL_SERVER_ERROR;
        }

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

        Map<String, Object> reponse = corps("VALIDATION_ERROR",
                "Requete invalide", HttpStatus.BAD_REQUEST);
        // Les noms de champs et leurs messages de validation sont rendus : ils
        // decrivent la requete de l'appelant, pas l'interieur du service.
        reponse.put("champs", champs);
        return Mono.just(ResponseEntity.badRequest().body(reponse));
    }

    /**
     * Tout le reste : 500, sans divulguer le message.
     *
     * <p>Le message est journalise AVEC sa pile — c'est la seule trace
     * exploitable d'un incident — et ne part pas au client.
     */
    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> inattendue(Exception ex) {
        log.error("Erreur inattendue : {}", ex.getMessage(), ex);
        return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(corps("INTERNAL_ERROR", "Une erreur interne s'est produite",
                        HttpStatus.INTERNAL_SERVER_ERROR)));
    }

    private Map<String, Object> corps(String code, String message, HttpStatus statut) {
        Map<String, Object> reponse = new HashMap<>();
        reponse.put("timestamp", LocalDateTime.now());
        reponse.put("status", statut.value());
        reponse.put("error", statut.getReasonPhrase());
        reponse.put("errorCode", code);
        reponse.put("message", message);
        reponse.put("service", "notification-service");
        return reponse;
    }
}
