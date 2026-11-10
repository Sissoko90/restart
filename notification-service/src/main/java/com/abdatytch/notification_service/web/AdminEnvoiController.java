package com.abdatytch.notification_service.web;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.notification_service.dto.request.EnvoiAdministrateurRequest;
import com.abdatytch.notification_service.dto.request.EnvoiGroupeRequest;
import com.abdatytch.notification_service.dto.response.EnvoiAdministrateurResponse;
import com.abdatytch.notification_service.dto.response.EnvoiGroupeResponse;
import com.abdatytch.notification_service.service.IServiceDeNotification;
import com.abdatytch.notification_service.service.impl.MoteurDeModeles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.abdatytch.notification_service.exception.RefusExplique;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Envoyer une notification depuis l'administration.
 *
 * <h2>Ce qui manquait</h2>
 *
 * Ce service savait recevoir des demandes sur le bus Kafka, et exposait pour
 * l'exploitant le solde SMS, l'URL de rappel et l'edition des modeles. Il
 * n'existait <b>aucun moyen d'envoyer une notification</b> : ni pour annoncer
 * une maintenance, ni pour prevenir une personne precise, ni pour verifier
 * qu'une correction de modele produit bien le texte attendu.
 *
 * <h2>Le meme pipeline que le bus, pas un second</h2>
 *
 * Les deux endpoints appellent {@link IServiceDeNotification#traiter}, qui est
 * exactement ce que fait le consommateur Kafka. Rien n'est reimplemente :
 * resolution des coordonnees, consentement, repli entre canaux, trace en base,
 * metriques et reprise s'appliquent a l'identique.
 *
 * <p>Ils n'ecrivent donc PAS sur Kafka pour se faire reconsommer. Le detour
 * ferait perdre le compte rendu — l'exploitant verrait « accepte » sans savoir
 * ce qui est parti — alors que l'appel direct le rend.
 *
 * <h2>Le consentement s'applique, et l'urgence n'y change rien</h2>
 *
 * C'est une propriete du code existant, et elle est importante ici :
 * {@code ResolveurDeCoordonnees.consent} decide a partir du CANAL, jamais de
 * l'urgence. Un exploitant ne peut donc pas forcer une remise en declarant son
 * message {@code CRITIQUE} — ce qui ferait de cet endpoint un contournement du
 * consentement, c'est-a-dire un outil d'envoi non sollicite.
 *
 * <p>{@code IN_APP} et {@code WEBSOCKET} ne demandent pas de consentement, et
 * c'est assume : ils remettent dans la propre boite de reception de la
 * personne.
 *
 * <h2>Ce que ces endpoints ne sont PAS</h2>
 *
 * Une infolettre. Il n'y a ici ni liste d'abonnes, ni lien de desabonnement, ni
 * campagne planifiee, ni suivi d'ouverture — et une campagne lancee par ce
 * chemin partagerait le pool de threads et la connexion SMTP avec les codes de
 * verification. Le plafond de groupe ci-dessous est la pour que ce detournement
 * ne passe pas inapercu.
 *
 * <h2>Securite</h2>
 *
 * {@code @PreAuthorize} ET la regle {@code /admin/**} de
 * {@code SecurityConfig} : les deux. Ce depot a deja trouve 33
 * {@code @PreAuthorize} inertes faute de
 * {@code @EnableReactiveMethodSecurity}, donc l'annotation DOUBLE la regle de
 * la chaine et ne la remplace pas.
 */
@RestController
@RequestMapping(AdminEnvoiController.CHEMIN)
@Tag(name = "Administration des notifications",
        description = "Envoyer une notification a une personne ou a un groupe")
public class AdminEnvoiController {

    static final String CHEMIN = "/admin/notifications";

    /** L'emetteur declare, qui etiquette la metrique {@code emetteur}. */
    static final String EMETTEUR = "notification-admin";

    private static final Logger log = LoggerFactory.getLogger(AdminEnvoiController.class);

    private final IServiceDeNotification service;
    private final MoteurDeModeles moteur;

    /**
     * Plafond du nombre de destinataires d'un seul appel.
     *
     * <h3>Pourquoi un plafond, et pourquoi il est bas</h3>
     *
     * Chaque destinataire declenche la resolution de ses coordonnees — un appel
     * a user-service — puis une remise par canal. Un appel de dix mille
     * destinataires occuperait le service pendant des minutes, et les codes de
     * verification arrives entre-temps attendraient derriere.
     *
     * <p>Le plafond ne protege pas d'une erreur de frappe : il rend visible le
     * moment ou cet endpoint cesse d'etre un outil d'exploitation pour devenir
     * un envoi de masse. Ce besoin-la demande son propre service, avec sa
     * propre file et son propre consentement.
     */
    private final int plafondDeGroupe;

    /**
     * Nombre de destinataires traites simultanement.
     *
     * <h3>Borne, et pas {@code flatMap} nu</h3>
     *
     * {@code flatMap} sans borne souscrit a TOUS les destinataires d'un coup :
     * cinq cents resolutions de coordonnees en parallele vers user-service, dont
     * le disjoncteur s'ouvrirait — et les notifications ordinaires du bus
     * echoueraient avec elles. La borne garde le cout d'un envoi de groupe
     * previsible.
     */
    private final int concurrence;

    public AdminEnvoiController(
            IServiceDeNotification service,
            MoteurDeModeles moteur,
            @Value("${notification.admin.plafond-groupe:200}") int plafondDeGroupe,
            @Value("${notification.admin.concurrence:4}") int concurrence) {
        this.service = service;
        this.moteur = moteur;
        this.plafondDeGroupe = plafondDeGroupe;
        this.concurrence = concurrence;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Envoyer une notification a une personne",
            description = "Le modele doit exister pour chaque canal demande. "
                    + "Le compte rendu indique ce que chaque canal a fait.")
    public Mono<EnvoiAdministrateurResponse> envoyer(
            @Valid @RequestBody EnvoiAdministrateurRequest requete) {

        refuserSiModeleAbsent(requete.modele(), requete.canaux(), requete.variablesOuVide());

        log.info("Envoi administrateur : modele {} vers 1 destinataire, canaux {}",
                requete.modele(), requete.canaux());

        return service.traiter(demandePour(
                        requete.destinataireId(), requete.canaux(), requete.modele(),
                        requete.variablesOuVide(), requete.urgenceOuNormale()))
                .map(EnvoiAdministrateurResponse::de);
    }

    @PostMapping("/groupe")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Envoyer une notification a un groupe de personnes",
            description = "Les doublons sont retires. Le rapport donne l'etat de "
                    + "chaque destinataire ; les comptes sont derives du detail.")
    public Mono<EnvoiGroupeResponse> envoyerAuGroupe(
            @Valid @RequestBody EnvoiGroupeRequest requete) {

        List<String> destinataires = requete.destinatairesUniques();
        if (destinataires.size() > plafondDeGroupe) {
            throw new RefusExplique(HttpStatus.CONTENT_TOO_LARGE,
                    "Ce groupe compte " + destinataires.size() + " destinataires, le "
                            + "plafond est de " + plafondDeGroupe
                            + " par appel. Decouper l'envoi, ou utiliser un service "
                            + "d'infolettre pour un envoi de masse.");
        }
        refuserSiModeleAbsent(requete.modele(), requete.canaux(), requete.variablesOuVide());

        log.info("Envoi administrateur : modele {} vers {} destinataires, canaux {}",
                requete.modele(), destinataires.size(), requete.canaux());

        return Flux.fromIterable(destinataires)
                .flatMapSequential(id -> service.traiter(demandePour(
                                id, requete.canaux(), requete.modele(),
                                requete.variablesOuVide(), requete.urgenceOuNormale()))
                        .map(rendu -> ligne(id, EnvoiAdministrateurResponse.de(rendu)))
                        // Un destinataire en erreur ne fait PAS echouer le groupe.
                        //
                        // Sinon un identifiant inexistant au milieu de la liste
                        // annulerait le rapport des cent precedents, et
                        // l'exploitant ne saurait pas lesquels sont partis.
                        .onErrorResume(panne -> {
                            log.warn("Envoi impossible pour un destinataire : {}",
                                    panne.getClass().getSimpleName());
                            return Mono.just(new EnvoiGroupeResponse.Ligne(
                                    id, false, "ERREUR : " + panne.getClass().getSimpleName()));
                        }),
                        concurrence)
                .collectList()
                .map(EnvoiGroupeResponse::de);
    }

    /**
     * Refuse l'appel si le modele n'existe pas pour UN des canaux demandes.
     *
     * <h3>Avant d'envoyer, et pour tous les canaux</h3>
     *
     * Sans ce controle, un modele absent produirait des remises REFUSEES une
     * par une — et sur un groupe, N refus identiques dont l'exploitant devrait
     * deduire la cause.
     *
     * <p>Le controle porte sur CHAQUE canal, parce que le moteur resout par le
     * couple (modele, canal) : un texte peut exister pour le courriel et pas
     * pour le SMS, qui n'a volontairement <b>aucun repli</b> — un corps de
     * courriel ne tient pas dans un SMS. Accepter l'appel dans ce cas ferait
     * croire que le message est parti sur les deux.
     */
    private void refuserSiModeleAbsent(String modele, List<CanalDeNotification> canaux,
                                       Map<String, String> variables) {
        List<String> manquants = new ArrayList<>();
        for (CanalDeNotification canal : canaux) {
            if (moteur.rendre(modele, canal, variables).isEmpty()) {
                manquants.add(canal.name());
            }
        }
        if (!manquants.isEmpty()) {
            throw new RefusExplique(HttpStatus.BAD_REQUEST,
                    "Le modele « " + modele + " » n'existe pas pour : "
                            + String.join(", ", manquants)
                            + ". Le declarer dans modeles-notification.properties, "
                            + "ou le poser par PUT " + AdminModeleController.CHEMIN + ".");
        }
    }

    private DemandeDeNotification demandePour(String destinataireId,
                                              List<CanalDeNotification> canaux,
                                              String modele,
                                              Map<String, String> variables,
                                              DemandeDeNotification.Urgence urgence) {
        return new DemandeDeNotification(
                UUID.randomUUID(), destinataireId, List.copyOf(canaux), modele,
                variables, urgence, EMETTEUR, null, Instant.now());
    }

    private static EnvoiGroupeResponse.Ligne ligne(String id,
                                                   EnvoiAdministrateurResponse rendu) {
        String detail = rendu.parCanal().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + " ; " + b)
                .orElse("aucun canal essaye");
        return new EnvoiGroupeResponse.Ligne(id, rendu.remis(), detail);
    }
}
