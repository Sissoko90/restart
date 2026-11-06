package com.abdatytch.notification_service.web;

import com.abdatytch.notification_service.dto.response.SoldeSmsResponse;
import com.abdatytch.notification_service.dto.response.UrlDeRappelResponse;
import com.abdatytch.notification_service.service.sms.IAdministrationSms;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;


/**
 * Administration du fournisseur SMS : solde, et déclaration de l'URL de rappel.
 *
 * <p>Protégé par {@code /admin/**} dans {@code SecurityConfig}, qui exige le
 * rôle ADMIN — c'est cette règle de chemin qui fait foi. Les
 * {@code @PreAuthorize} la doublent et ne la remplacent pas : ce dépôt a déjà
 * trouvé 33 {@code @PreAuthorize} inertes faute de
 * {@code @EnableReactiveMethodSecurity}, qui est depuis en place.
 *
 * <p><b>Ce paragraphe a été faux.</b> La règle {@code /admin/**} n'existait pas
 * dans ce module — cinq autres la portaient, pas celui-ci — donc ces chemins
 * tombaient dans {@code anyExchange().authenticated()} et seule l'annotation
 * refusait. Elle fonctionnait, il n'y avait donc pas de trou ouvert ; mais la
 * couche décrite comme « celle qui fait foi » était absente, et la seule
 * protection réelle était celle présentée comme un simple doublon.
 *
 * <p>La règle est désormais posée, et {@code CouvertureDesCheminsAdminTest}
 * garde sa présence et sa position : une affirmation de ce genre doit être
 * vérifiée par un test, pas crue sur parole dans un commentaire.
 */
@RestController
@RequestMapping("/admin/sms")
@RequiredArgsConstructor
@Slf4j
public class AdminSmsController {

    private final IAdministrationSms administration;

    /**
     * Consulte le solde chez le fournisseur, par pays.
     *
     * <p>Le seul moyen de voir venir la panne la plus silencieuse de la chaîne
     * d'inscription : un crédit épuisé ne produit aucune erreur HTTP, les envois
     * répondent 200 avec un code de refus. Un solde à zéro ou négatif signifie
     * que les SMS ne partent plus.
     *
     * <p>{@code GET /admin/sms/solde}
     */
    @GetMapping("/solde")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<SoldeSmsResponse>> consulterSolde() {
        log.info("Admin - consultation du solde SMS");

        return administration.consulterSolde()
                .map(SoldeSmsResponse::de)
                .map(ResponseEntity::ok);
    }

    /**
     * Déclare à AfrikSMS l'URL de rappel pour les accusés de réception.
     *
     * <p><b>Sans corps de requête, et c'est délibéré.</b> L'URL déclarée vient
     * de {@code sms.afriksms.notify-url}, c'est-à-dire de la configuration de
     * déploiement. Accepter une URL dans le corps permettrait à qui détient un
     * jeton d'administration de rediriger nos accusés de réception vers un
     * serveur de son choix — donc d'observer qui reçoit des SMS et quand. Cet
     * appel ne fait que pousser la valeur configurée.
     *
     * <p>À exécuter une fois par environnement, et de nouveau si l'URL change.
     * AfrikSMS ne retient qu'une seule URL pour tout le compte.
     *
     * <p>{@code POST /admin/sms/url-de-rappel}
     */
    @PostMapping("/url-de-rappel")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<UrlDeRappelResponse>> declarerUrlDeRappel() {
        log.info("Admin - déclaration de l'URL de rappel SMS");

        return administration.declarerUrlDeRappel()
                .map(UrlDeRappelResponse::de)
                .map(ResponseEntity::ok);
    }
}
