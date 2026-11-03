package com.abdatytch.security_service.exception;

import com.abdatytch.security_service.constant.SecurityConstants;
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
 * Traduction des refus de ce service en reponses HTTP.
 *
 * <h2>Le defaut que cette classe corrige</h2>
 *
 * Ce service n'en avait <b>aucune</b>, alors que {@code setting-service} et
 * {@code quotas-service} en ont une. {@link SecurityPolicyException} etend
 * {@code RuntimeException} : sans {@code @RestControllerAdvice}, WebFlux la
 * traduit en <b>500 Internal Server Error</b>.
 *
 * <p>Les deux controleurs existants documentaient pourtant un {@code 400} sur
 * chacun de leurs refus — code pays invalide, pays non refuse, valeur hors
 * bornes, fenetre deja terminee. Les sept endpoints rendaient donc 500 la ou ils
 * annoncaient 400, et un appelant ne pouvait pas distinguer « ma demande est
 * mauvaise » de « le service est en panne ». La difference compte : la premiere
 * se corrige, la seconde se signale.
 *
 * <h2>La forme du corps est celle des autres services</h2>
 *
 * Volontairement a l'identique de {@code setting-service} : un client qui sait
 * lire l'erreur d'un service de la plateforme sait lire celle des autres.
 * Inventer une forme ici obligerait le frontal a un cas particulier par service.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Ressource introuvable : <b>404</b>.
     *
     * <p>C'est la specificite du type qui fait choisir ce gestionnaire plutot
     * que {@link #politiqueRefusee}, pas l'ordre de declaration : Spring retient
     * le gestionnaire du type le plus derive, et
     * {@link RessourceIntrouvableException} etend
     * {@link SecurityPolicyException}.
     */
    @ExceptionHandler(RessourceIntrouvableException.class)
    public Mono<ResponseEntity<Map<String, Object>>> introuvable(
            RessourceIntrouvableException erreur) {

        // INFO et non WARN : un identifiant inconnu est le plus souvent une
        // ressource levee entre-temps, pas une tentative. Les vraies tentatives
        // se voient au TAUX, que la metrique HTTP porte deja.
        log.info("Ressource de securite introuvable : {}", erreur.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(corps(HttpStatus.NOT_FOUND,
                        SecurityConstants.CODE_RESSOURCE_INTROUVABLE, erreur.getMessage())));
    }

    /**
     * Refus metier : <b>400</b>.
     *
     * <p>WARN : un refus sur la surface d'administration de la securite merite
     * d'etre vu. Ces appels sont rares et tous authentifies en ADMIN ; un flot
     * de refus ici signale soit un frontal casse, soit quelqu'un qui cherche.
     */
    @ExceptionHandler(SecurityPolicyException.class)
    public Mono<ResponseEntity<Map<String, Object>>> politiqueRefusee(
            SecurityPolicyException erreur) {

        log.warn("Refus sur la politique de securite : {}", erreur.getMessage());
        Map<String, Object> corps = corps(HttpStatus.BAD_REQUEST,
                SecurityConstants.CODE_POLITIQUE_REFUSEE, erreur.getMessage());
        // La cle n'est ajoutee que si le refus en porte une : un frontal qui
        // surligne le champ fautif a besoin de savoir LEQUEL, et une cle nulle
        // dans le corps ne lui apprend rien.
        if (erreur.getCle() != null) {
            corps.put("cle", erreur.getCle());
        }
        return Mono.just(ResponseEntity.badRequest().body(corps));
    }

    /**
     * Validation declarative en echec : <b>400</b>.
     *
     * <p>Tous les messages sont agreges, et non le premier seul : un
     * administrateur qui pose une regle avec trois champs fautifs a besoin des
     * trois, sinon il corrige et resoumet trois fois.
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> validation(WebExchangeBindException erreur) {
        // getAllErrors et non getFieldErrors : les contraintes @AssertTrue de
        // OuvertureDeMaintenanceRequest sont des erreurs d'OBJET, pas de champ.
        // Ne lire que les erreurs de champ aurait rendu un 400 au message vide
        // pour un message de maintenance contenant « | » — le refus aurait eu
        // lieu sans que l'appelant sache pourquoi.
        String message = erreur.getBindingResult().getAllErrors().stream()
                .map(faute -> faute.getDefaultMessage())
                .filter(libelle -> libelle != null && !libelle.isBlank())
                .reduce((a, b) -> a + " ; " + b)
                .orElse(SecurityConstants.REQUETE_INVALIDE);

        // Journalise la STRUCTURE du refus — combien de fautes, et quelles
        // contraintes — et non les messages.
        //
        // Les messages sont des constantes de SecurityConstants ou des libelles
        // par defaut de Jakarta : ils ne portent pas la saisie, et le passage
        // n'etait donc pas dangereux. Mais AucunJustificatifDansLesJournauxTest
        // l'a signale, et il a raison de le faire : rien n'empeche d'ecrire un
        // jour un message avec ${validatedValue}, et la fuite serait alors
        // silencieuse. Un nom de contrainte, lui, ne pourra jamais porter de
        // donnee.
        //
        // L'appelant recoit de toute facon les messages complets dans le corps
        // de la reponse : rien n'est perdu pour qui corrige sa requete.
        String contraintes = erreur.getBindingResult().getAllErrors().stream()
                .map(faute -> faute.getCode())
                .filter(code -> code != null)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("inconnue");
        log.warn("Requete d'administration invalide : {} faute(s), contrainte(s) {}",
                erreur.getBindingResult().getAllErrors().size(), contraintes);

        return Mono.just(ResponseEntity.badRequest()
                .body(corps(HttpStatus.BAD_REQUEST,
                        SecurityConstants.CODE_REQUETE_INVALIDE, message)));
    }

    /**
     * @return un corps modifiable — {@link #politiqueRefusee} y ajoute la cle.
     *         Un {@code Map.of} serait immuable, et l'ajout leverait.
     */
    private Map<String, Object> corps(HttpStatus statut, String code, String message) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("timestamp", Instant.now().toString());
        corps.put("status", statut.value());
        corps.put("error", statut.getReasonPhrase());
        corps.put("code", code);
        corps.put("message", message);
        return corps;
    }
}
