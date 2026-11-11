package com.abdatytch.interface_service.controller;

import com.abdatytch.commons.erasure.UserErasureRequestedEvent;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent.ErasureReason;
import com.abdatytch.interface_service.erasure.DeveloperErasurePublisher;
import com.abdatytch.interface_service.erasure.DeveloperErasureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**

 * Droit à l'effacement pour les développeurs du portail (RGPD art. 17).
 *
 * <p>Les développeurs constituent une population distincte des utilisateurs
 * finaux : leur identité vit dans interface-service, non dans auth-service. Ils
 * ne disposaient jusqu'ici <b>d'aucun</b> moyen d'exercer ce droit — pas même
 * d'une suppression de compte.
 *
 * <p>L'effacement local est immédiat ; celui de leurs quotas et préférences est
 * propagé par événement, d'où la réponse 202 et l'identifiant de demande remis
 * à la personne.
 */
@RestController
@RequestMapping("/dev/erasure")
@Tag(name = "Effacement développeur",
        description = "Droit à l'effacement des données du compte développeur")
public class DeveloperErasureController {

    private static final Logger log = LoggerFactory.getLogger(DeveloperErasureController.class);

    private final DeveloperErasureService erasureService;
    private final DeveloperErasurePublisher publisher;

    public DeveloperErasureController(DeveloperErasureService erasureService,
                                      DeveloperErasurePublisher publisher) {
        this.erasureService = erasureService;
        this.publisher = publisher;
    }

    @PostMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Effacer son compte développeur et ses données",
            description = "Irréversible. Efface le compte, les projets, le secret TOTP et "
                    + "les jetons, puis propage l'effacement aux quotas et préférences.")
    public Mono<ResponseEntity<Map<String, Object>>> eraseOwnAccount() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getName)
                .flatMap(developerId -> {
                    log.info("Effacement demandé par le développeur {}", developerId);

                    UserErasureRequestedEvent event = UserErasureRequestedEvent.of(
                            developerId, ErasureReason.DEMANDE_DE_LA_PERSONNE);

                    // L'effacement local d'abord : si la publication échoue
                    // ensuite, les données du portail sont déjà parties et
                    // l'événement pourra être rejoué. L'ordre inverse laisserait
                    // les autres services effacer pendant que le compte
                    // développeur, lui, resterait intact.
                    return erasureService.erase(developerId)
                            .then(publisher.publish(event))
                            .thenReturn(event.requestId().toString());
                })
                .map(requestId -> ResponseEntity.accepted().body(reponse(requestId)));
    }

    private Map<String, Object> reponse(String requestId) {
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("requestId", requestId);
        corps.put("message", "Effacement engagé. Conservez cet identifiant : il permet "
                    + "de justifier de votre demande.");
        corps.put("irreversible", true);
        return corps;
    }
}
