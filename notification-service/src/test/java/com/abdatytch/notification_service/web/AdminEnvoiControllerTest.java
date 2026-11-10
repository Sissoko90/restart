package com.abdatytch.notification_service.web;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.CompteRenduDeRemise;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.notification_service.exception.GlobalExceptionHandler;
import com.abdatytch.notification_service.repositories.SurchargeDeModeleRepository;
import com.abdatytch.notification_service.service.IServiceDeNotification;
import com.abdatytch.notification_service.service.impl.MoteurDeModeles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Les deux endpoints d'envoi depuis l'administration.
 *
 * <h2>Ce que cette suite verifie, et pourquoi chaque point compte</h2>
 *
 * <ul>
 *   <li>un modele absent est refuse AVANT d'envoyer, et le refus nomme les
 *       canaux fautifs — sinon un groupe produirait N refus identiques dont
 *       l'exploitant devrait deduire la cause ;</li>
 *   <li>le plafond de groupe est applique sur la liste DEDOUBLONNEE, pas sur
 *       la liste brute ;</li>
 *   <li>les doublons sont retires, sinon la seconde demande serait rejetee
 *       comme « doublon » et le rapport annoncerait un echec la ou il n'y a
 *       qu'une liste mal collee ;</li>
 *   <li>le compte rendu dit ce que chaque canal a REELLEMENT fait — ce depot a
 *       deja eu sept endpoints annoncant « code envoye » sans rien tenter ;</li>
 *   <li>les comptes du rapport de groupe sont DERIVES du detail ;</li>
 *   <li>un destinataire en erreur ne fait pas echouer le groupe.</li>
 * </ul>
 *
 * <h2>Le moteur est REEL, sur le fichier versionne</h2>
 *
 * Un moteur simule rendrait n'importe quel modele, et le controle d'existence —
 * la moitie de ce que fait ce controleur avant d'envoyer — ne porterait plus
 * sur rien. Les modeles cites ici existent donc vraiment dans
 * {@code modeles-notification.properties}.
 */
@DisplayName("AdminEnvoiController")
class AdminEnvoiControllerTest {

    /** Un modele qui existe pour COURRIEL et IN_APP dans le fichier versionne. */
    private static final String MODELE_REEL = "securite.nouvelle-connexion";

    /**
     * Un service de notification d'essai, qui note ce qu'on lui demande.
     *
     * <p>Un espion et non un mock : cette suite verifie aussi ce qui est
     * TRANSMIS — l'emetteur declare, l'urgence, le dedoublonnage — et un mock
     * verifie par {@code verify()} rendrait ces assertions moins lisibles.
     */
    private static final class ServiceEspion implements IServiceDeNotification {

        private final List<DemandeDeNotification> recues = new ArrayList<>();
        private final List<CompteRenduDeRemise.RemiseParCanal> aRendre;
        private String destinataireQuiEchoue;

        ServiceEspion(CompteRenduDeRemise.RemiseParCanal... remises) {
            this.aRendre = List.of(remises);
        }

        void faireEchouer(String destinataireId) {
            this.destinataireQuiEchoue = destinataireId;
        }

        @Override
        public Mono<CompteRenduDeRemise> traiter(DemandeDeNotification demande) {
            recues.add(demande);
            if (demande.destinataireId().equals(destinataireQuiEchoue)) {
                return Mono.error(new IllegalStateException("destinataire inconnu"));
            }
            return Mono.just(new CompteRenduDeRemise(
                    demande.demandeId(), aRendre, true, Instant.now()));
        }

        @Override
        public Mono<CompteRenduDeRemise.Etat> rejouer(UUID id, CanalDeNotification canal) {
            return Mono.empty();
        }
    }

    private static MoteurDeModeles moteurSurLeFichierSeul() {
        SurchargeDeModeleRepository sansSurcharge = mock(SurchargeDeModeleRepository.class);
        when(sansSurcharge.findAll()).thenReturn(Flux.empty());
        return new MoteurDeModeles(
                new ClassPathResource("modeles-notification.properties"), sansSurcharge);
    }

    /**
     * Le client, AVEC le conseil global.
     *
     * <h3>Pourquoi {@code controllerAdvice} n'est pas optionnel ici</h3>
     *
     * Sans lui, ce harnais exercerait un assemblage qui n'existe pas en
     * production, et ce depot s'est deja fait prendre exactement ainsi : un
     * {@code ResponseStatusException(UNAUTHORIZED)} sortait en 500 chez
     * interface-service, et son test ne pouvait pas le voir parce qu'il liait
     * le controleur seul.
     *
     * <p>Il compte doublement pour cette suite : le corps rendu a l'appelant
     * est construit par {@code GlobalExceptionHandler}, et c'est precisement
     * lui qui decide si le motif d'un refus franchit la frontiere. Un test sans
     * conseil verrait le motif que le controleur a ecrit, et non celui que
     * l'exploitant recoit.
     */
    private static WebTestClient client(ServiceEspion espion, int plafond) {
        return WebTestClient.bindToController(
                        new AdminEnvoiController(espion, moteurSurLeFichierSeul(),
                                plafond, 2))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static CompteRenduDeRemise.RemiseParCanal remis(CanalDeNotification canal) {
        return new CompteRenduDeRemise.RemiseParCanal(
                canal, CompteRenduDeRemise.Etat.REMIS, null);
    }

    private static CompteRenduDeRemise.RemiseParCanal refuse(CanalDeNotification canal,
                                                             String motif) {
        return new CompteRenduDeRemise.RemiseParCanal(
                canal, CompteRenduDeRemise.Etat.REFUSE, motif);
    }

    // ==================================================================
    @Nested
    @DisplayName("Le modele doit exister pour CHAQUE canal demande")
    class ControleDuModele {

        @Test
        @DisplayName("un modele inconnu est refuse avant tout envoi")
        void modeleInconnu() {
            ServiceEspion espion = new ServiceEspion(remis(CanalDeNotification.COURRIEL));

            client(espion, 200).post().uri(AdminEnvoiController.CHEMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataireId":"c1","canaux":["COURRIEL"],
                             "modele":"modele.qui.n.existe.pas","variables":{}}""")
                    .exchange()
                    .expectStatus().isBadRequest();

            assertThat(espion.recues)
                    .withFailMessage("""
                            Le controleur a envoye alors que le modele n'existe pas.

                            Sur un groupe, cela produirait N remises REFUSEES \
                            identiques, et l'exploitant devrait deviner que la \
                            cause est le modele et non le consentement.""")
                    .isEmpty();
        }

        @Test
        @DisplayName("le refus nomme les canaux pour lesquels le modele manque")
        void refusNommeLesCanaux() {
            // SMS n'a VOLONTAIREMENT aucun repli sur le corps du courriel : un
            // modele peut donc exister pour COURRIEL et pas pour SMS.
            ServiceEspion espion = new ServiceEspion(remis(CanalDeNotification.COURRIEL));

            byte[] corps = client(espion, 200).post().uri(AdminEnvoiController.CHEMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataireId":"c1","canaux":["COURRIEL","SMS"],
                             "modele":"%s","variables":{}}""".formatted(MODELE_REEL))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody().returnResult().getResponseBodyContent();

            assertThat(new String(corps == null ? new byte[0] : corps))
                    .withFailMessage("Le refus ne dit pas QUEL canal manque, "
                            + "donc il n'indique pas quoi corriger")
                    .contains("SMS");
            assertThat(espion.recues).isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Envoi a une personne")
    class EnvoiUnique {

        @Test
        @DisplayName("le compte rendu dit ce que chaque canal a fait")
        void compteRenduParCanal() {
            ServiceEspion espion = new ServiceEspion(
                    remis(CanalDeNotification.IN_APP),
                    refuse(CanalDeNotification.COURRIEL, "aucune adresse connue"));

            client(espion, 200).post().uri(AdminEnvoiController.CHEMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataireId":"c1","canaux":["IN_APP","COURRIEL"],
                             "modele":"%s","variables":{"circonstances":"x"}}"""
                            .formatted(MODELE_REEL))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.remis").isEqualTo(true)
                    .jsonPath("$.parCanal.IN_APP").isEqualTo("REMIS")
                    .jsonPath("$.parCanal.COURRIEL")
                    .isEqualTo("REFUSE : aucune adresse connue");
        }

        @Test
        @DisplayName("aucun canal abouti rend remis=false, et non une erreur")
        void aucunCanalAbouti() {
            ServiceEspion espion = new ServiceEspion(
                    refuse(CanalDeNotification.COURRIEL, "consentement refuse"));

            client(espion, 200).post().uri(AdminEnvoiController.CHEMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataireId":"c1","canaux":["COURRIEL"],
                             "modele":"%s","variables":{}}""".formatted(MODELE_REEL))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.remis")
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("l'emetteur declare est notification-admin, et l'urgence par defaut NORMALE")
        void emetteurEtUrgence() {
            ServiceEspion espion = new ServiceEspion(remis(CanalDeNotification.IN_APP));

            client(espion, 200).post().uri(AdminEnvoiController.CHEMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataireId":"c1","canaux":["IN_APP"],
                             "modele":"%s","variables":{}}""".formatted(MODELE_REEL))
                    .exchange()
                    .expectStatus().isOk();

            assertThat(espion.recues).hasSize(1);
            assertThat(espion.recues.get(0).serviceEmetteur())
                    .withFailMessage("""
                            L'emetteur declare etiquette la metrique \
                            notification_demandes_total. Sans valeur propre, un \
                            envoi d'exploitant serait indistinguable d'une \
                            notification systeme dans le tableau de bord.""")
                    .isEqualTo(AdminEnvoiController.EMETTEUR);
            assertThat(espion.recues.get(0).urgence())
                    .isEqualTo(DemandeDeNotification.Urgence.NORMALE);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Envoi a un groupe")
    class EnvoiGroupe {

        private static final String CHEMIN_GROUPE =
                AdminEnvoiController.CHEMIN + "/groupe";

        @Test
        @DisplayName("les doublons sont retires avant d'envoyer")
        void doublonsRetires() {
            ServiceEspion espion = new ServiceEspion(remis(CanalDeNotification.IN_APP));

            client(espion, 200).post().uri(CHEMIN_GROUPE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataires":["c1","c2","c1"],"canaux":["IN_APP"],
                             "modele":"%s","variables":{}}""".formatted(MODELE_REEL))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.demandes").isEqualTo(2)
                    .jsonPath("$.remis").isEqualTo(2);

            assertThat(espion.recues)
                    .withFailMessage("""
                            Le meme destinataire a recu deux demandes. La seconde \
                            serait rejetee comme « doublon » par l'orchestrateur, \
                            et le rapport annoncerait un echec la ou il n'y a \
                            qu'une liste mal collee.""")
                    .hasSize(2);
        }

        @Test
        @DisplayName("au-dela du plafond, l'appel est refuse sans rien envoyer")
        void plafondApplique() {
            ServiceEspion espion = new ServiceEspion(remis(CanalDeNotification.IN_APP));

            client(espion, 2).post().uri(CHEMIN_GROUPE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataires":["c1","c2","c3"],"canaux":["IN_APP"],
                             "modele":"%s","variables":{}}""".formatted(MODELE_REEL))
                    .exchange()
                    .expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONTENT_TOO_LARGE);

            assertThat(espion.recues)
                    .withFailMessage("Le plafond a ete depasse ET des messages sont "
                            + "partis : un refus partiel est le pire des deux")
                    .isEmpty();
        }

        @Test
        @DisplayName("le plafond porte sur la liste DEDOUBLONNEE")
        void plafondApresDedoublonnage() {
            ServiceEspion espion = new ServiceEspion(remis(CanalDeNotification.IN_APP));

            // Trois entrees, deux destinataires distincts, plafond a deux.
            int statut = client(espion, 2).post().uri(CHEMIN_GROUPE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataires":["c1","c2","c2"],"canaux":["IN_APP"],
                             "modele":"%s","variables":{}}""".formatted(MODELE_REEL))
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();

            assertThat(statut)
                    .withFailMessage("""
                            Statut %s : le plafond a compte la liste BRUTE. Un \
                            exploitant qui colle une liste avec des doublons se \
                            verrait refuser un envoi que le service peut tres bien \
                            faire.""", statut)
                    .isEqualTo(200);

            assertThat(espion.recues).hasSize(2);
        }

        @Test
        @DisplayName("un destinataire en erreur ne fait pas echouer le groupe")
        void erreurIsolee() {
            ServiceEspion espion = new ServiceEspion(remis(CanalDeNotification.IN_APP));
            espion.faireEchouer("c2");

            client(espion, 200).post().uri(CHEMIN_GROUPE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataires":["c1","c2","c3"],"canaux":["IN_APP"],
                             "modele":"%s","variables":{}}""".formatted(MODELE_REEL))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.demandes").isEqualTo(3)
                    .jsonPath("$.remis").isEqualTo(2)
                    .jsonPath("$.sansRemise").isEqualTo(1)
                    .jsonPath("$.parDestinataire[1].destinataireId").isEqualTo("c2")
                    .jsonPath("$.parDestinataire[1].remis").isEqualTo(false);
        }

        @Test
        @DisplayName("les comptes du rapport sont derives du detail")
        void comptesDerives() {
            ServiceEspion espion = new ServiceEspion(
                    refuse(CanalDeNotification.COURRIEL, "consentement refuse"));

            client(espion, 200).post().uri(CHEMIN_GROUPE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataires":["c1","c2"],"canaux":["COURRIEL"],
                             "modele":"%s","variables":{}}""".formatted(MODELE_REEL))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    // Deux demandes traitees, et AUCUNE remise. Un compteur tenu
                    // a cote du detail aurait annonce deux reussites : le service
                    // a bien repondu pour les deux.
                    .jsonPath("$.demandes").isEqualTo(2)
                    .jsonPath("$.remis").isEqualTo(0)
                    .jsonPath("$.sansRemise").isEqualTo(2);
        }

        @Test
        @DisplayName("l'ordre de la liste fournie est conserve dans le rapport")
        void ordreConserve() {
            ServiceEspion espion = new ServiceEspion(remis(CanalDeNotification.IN_APP));

            client(espion, 200).post().uri(CHEMIN_GROUPE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"destinataires":["zz","aa","mm"],"canaux":["IN_APP"],
                             "modele":"%s","variables":{}}""".formatted(MODELE_REEL))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    // Un tri, meme stable, rendrait le rapport illisible a cote
                    // de la liste que l'exploitant vient de coller.
                    .jsonPath("$.parDestinataire[0].destinataireId").isEqualTo("zz")
                    .jsonPath("$.parDestinataire[1].destinataireId").isEqualTo("aa")
                    .jsonPath("$.parDestinataire[2].destinataireId").isEqualTo("mm");
        }
    }
}
