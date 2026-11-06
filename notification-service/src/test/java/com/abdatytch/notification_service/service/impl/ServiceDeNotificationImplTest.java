package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.CompteRenduDeRemise;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.notification_service.client.ClientContactsUtilisateur;
import com.abdatytch.notification_service.client.ClientPreferencesUtilisateur;
import com.abdatytch.notification_service.entities.Notification;
import com.abdatytch.notification_service.entities.RemiseParCanal;
import com.abdatytch.notification_service.metrics.NotificationMetrics;
import com.abdatytch.notification_service.repositories.NotificationRepository;
import com.abdatytch.notification_service.repositories.RemiseParCanalRepository;
import com.abdatytch.notification_service.service.CanalDeRemise;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le chemin de remise, sur la distinction qui decide du sort d'une notification.
 *
 * <h2>Pourquoi des pieces REELLES plutot que des bouchons</h2>
 *
 * Le moteur de modeles, le resolveur et le registre de metriques sont les vrais.
 * Seuls les depots et les canaux sont simules — ce sont les frontieres
 * d'entree-sortie.
 *
 * <p>Un moteur bouchonne aurait rendu « un contenu » sans verifier qu'un modele
 * existe vraiment pour le canal demande ; or « modele introuvable » est l'un des
 * refus que ce test doit pouvoir distinguer des autres.
 */
@DisplayName("Remise d'une notification par canaux")
class ServiceDeNotificationImplTest {

    private static final String PORTEUR = "11111111-1111-1111-1111-111111111111";
    private static final String MODELE = "securite.contact-modifie";

    private NotificationRepository notifications;
    private RemiseParCanalRepository remises;
    private ClientContactsUtilisateur client;
    private ClientPreferencesUtilisateur prefs;
    private List<RemiseParCanal> ecrites;

    /**
     * Un canal qui rend ce qu'on lui dit, et retient ce qu'il a recu.
     *
     * <p>Les assertions portent sur l'etat ECRIT, donc sur ce que la reprise
     * lira. Un canal qui compterait seulement ses appels ne dirait rien du sort
     * de la notification.
     */
    private static final class CanalEspion implements CanalDeRemise {
        private final CanalDeNotification canal;
        private final Resultat resultat;
        private MessageARemettre recu;

        CanalEspion(CanalDeNotification canal, Resultat resultat) {
            this.canal = canal;
            this.resultat = resultat;
        }

        @Override
        public CanalDeNotification canal() {
            return canal;
        }

        @Override
        public boolean estActif() {
            return true;
        }

        @Override
        public Mono<Resultat> remettre(MessageARemettre message) {
            this.recu = message;
            return Mono.just(resultat);
        }
    }

    @BeforeEach
    void preparer() {
        notifications = mock(NotificationRepository.class);
        remises = mock(RemiseParCanalRepository.class);
        client = mock(ClientContactsUtilisateur.class);
        prefs = mock(ClientPreferencesUtilisateur.class);
        when(prefs.pour(anyString())).thenReturn(Mono.just(java.util.Map.of()));
        ecrites = new ArrayList<>();

        when(notifications.parDemandeId(any())).thenReturn(Mono.empty());
        // Rendu tel quel : `Notification.depuis` genere deja l'identifiant —
        // c'est ce que demande Persistable pour un id cote client. Lui en poser
        // un ici masquerait une regression de cette generation.
        when(notifications.save(any()))
                .thenAnswer(appel -> Mono.just((Notification) appel.getArgument(0)));
        when(remises.parNotificationEtCanal(any(), anyString())).thenReturn(Mono.empty());
        when(remises.save(any())).thenAnswer(appel -> {
            RemiseParCanal r = appel.getArgument(0);
            ecrites.add(r);
            return Mono.just(r);
        });
    }

    private ServiceDeNotificationImpl service(CanalEspion... canaux) {
        return new ServiceDeNotificationImpl(
                notifications,
                remises,
                new MoteurDeModeles(new ClassPathResource("modeles-notification.properties")),
                new ResolveurDeCoordonnees(client, prefs, true, false, false),
                new NotificationMetrics(new SimpleMeterRegistry()),
                new ObjectMapper(),
                List.of(canaux));
    }

    private static DemandeDeNotification demande(List<CanalDeNotification> canaux) {
        return new DemandeDeNotification(UUID.randomUUID(), PORTEUR, canaux, MODELE,
                Map.of("date", "2026-11-06"),
                DemandeDeNotification.Urgence.CRITIQUE, "test", null, Instant.now());
    }

    private CompteRenduDeRemise.Etat etatDe(CanalDeNotification canal) {
        return ecrites.stream()
                .filter(r -> canal == r.getCanal())
                .map(RemiseParCanal::getEtat)
                .findFirst()
                .orElse(null);
    }

    @Nested
    @DisplayName("Quand user-service est injoignable")
    class UserServiceInjoignable {

        @BeforeEach
        void panne() {
            when(client.pour(anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("injoignable")));
        }

        /**
         * ECHOUE et non REFUSE, et c'est toute la difference.
         *
         * <p>Rien ne dit que cette personne n'a pas d'adresse : user-service n'a
         * pas repondu. La marquer REFUSE la rendrait non reessayable —
         * {@code aReprendre} exclut explicitement REFUSE —, donc chaque incident
         * de user-service perdrait silencieusement des notifications, y compris
         * des alertes de securite.
         */
        @Test
        @DisplayName("un canal à coordonnée est marqué ECHOUE, donc repris")
        void canalACoordonneeEchoue() {
            var courriel = new CanalEspion(CanalDeNotification.COURRIEL,
                    CanalDeRemise.Resultat.remis());

            service(courriel)
                    .traiter(demande(List.of(CanalDeNotification.COURRIEL)))
                    .block();

            assertThat(etatDe(CanalDeNotification.COURRIEL))
                    .isEqualTo(CompteRenduDeRemise.Etat.ECHOUE);
            // Le canal n'a MEME PAS ete appele : inutile de solliciter un
            // serveur SMTP pour une adresse qu'on n'a pas.
            assertThat(courriel.recu).isNull();
        }

        /**
         * Le defaut que ce test empeche de revenir.
         *
         * <p>La garde « coordonnees indisponibles » marquait d'abord TOUS les
         * canaux ECHOUE, y compris IN_APP et WEBSOCKET qui routent sur
         * l'identifiant du porteur et n'ont besoin d'aucune adresse. Une panne
         * chez un voisin couchait donc le seul chemin qui ne depend de personne.
         */
        @Test
        @DisplayName("la boîte de réception aboutit quand même")
        void boiteDeReceptionAboutit() {
            var inApp = new CanalEspion(CanalDeNotification.IN_APP,
                    CanalDeRemise.Resultat.remis());
            var courriel = new CanalEspion(CanalDeNotification.COURRIEL,
                    CanalDeRemise.Resultat.remis());

            var compteRendu = service(inApp, courriel)
                    .traiter(demande(List.of(CanalDeNotification.COURRIEL,
                            CanalDeNotification.IN_APP)))
                    .block();

            assertThat(etatDe(CanalDeNotification.COURRIEL))
                    .isEqualTo(CompteRenduDeRemise.Etat.ECHOUE);
            assertThat(etatDe(CanalDeNotification.IN_APP))
                    .isEqualTo(CompteRenduDeRemise.Etat.REMIS);

            assertThat(compteRendu).isNotNull();
            // `rendu` reste VRAI : la notification est arrivee quelque part.
            // Le dire faux aurait compte une CRITIQUE perdue, et declenche une
            // alerte sur une notification que la personne a pourtant recue.
            assertThat(compteRendu.rendu()).isTrue();
        }
    }

    @Nested
    @DisplayName("Quand la personne n'a pas de coordonnée")
    class SansCoordonnee {

        /**
         * REFUSE et non ECHOUE : definitif.
         *
         * <p>user-service a repondu, et il n'a pas d'adresse pour cette
         * personne. Reessayer ne trouverait rien de plus, et une reprise sans
         * fin sur un compte sans adresse ferait grossir la file de reprise sans
         * jamais la vider.
         */
        @Test
        @DisplayName("le canal refuse, et le refus est définitif")
        void refusDefinitif() {
            when(client.pour(anyString()))
                    .thenReturn(Mono.just(ClientContactsUtilisateur.AUCUN));

            var courriel = new CanalEspion(CanalDeNotification.COURRIEL,
                    CanalDeRemise.Resultat.refuse("aucune adresse connue"));

            service(courriel)
                    .traiter(demande(List.of(CanalDeNotification.COURRIEL)))
                    .block();

            assertThat(etatDe(CanalDeNotification.COURRIEL))
                    .isEqualTo(CompteRenduDeRemise.Etat.REFUSE);
            // Le canal EST appele ici, et c'est la difference avec le cas
            // precedent : il decide lui-meme, avec son propre message de refus.
            assertThat(courriel.recu).isNotNull();
            assertThat(courriel.recu.coordonnee()).isNull();
        }
    }

    @Nested
    @DisplayName("Quand tout va bien")
    class CheminNormal {

        @Test
        @DisplayName("la coordonnée résolue parvient au canal, avec le texte du modèle")
        void coordonneeEtTexteParviennentAuCanal() {
            when(client.pour(anyString())).thenReturn(Mono.just(
                    new ClientContactsUtilisateur.Contacts("personne@exemple.test", null)));

            var courriel = new CanalEspion(CanalDeNotification.COURRIEL,
                    CanalDeRemise.Resultat.remis());

            service(courriel)
                    .traiter(demande(List.of(CanalDeNotification.COURRIEL)))
                    .block();

            assertThat(etatDe(CanalDeNotification.COURRIEL))
                    .isEqualTo(CompteRenduDeRemise.Etat.REMIS);
            assertThat(courriel.recu).isNotNull();
            assertThat(courriel.recu.coordonnee()).isEqualTo("personne@exemple.test");
            // Le modele REEL a ete rendu, variable substituee : c'est ce qui
            // verifie que la chaine complete tient, et non seulement ses bords.
            assertThat(courriel.recu.corps()).contains("2026-11-06");
            assertThat(courriel.recu.sujet()).isNotBlank();
        }

        /**
         * Une notification qui ne vise que la boite de reception ne doit PAS
         * faire appel a user-service : elle n'a besoin d'aucune adresse, et un
         * appel inutile la rendrait dependante d'un autre service.
         */
        @Test
        @DisplayName("une notification sans canal à coordonnée n'interroge pas user-service")
        void aucunAppelInutile() {
            var inApp = new CanalEspion(CanalDeNotification.IN_APP,
                    CanalDeRemise.Resultat.remis());

            service(inApp)
                    .traiter(demande(List.of(CanalDeNotification.IN_APP)))
                    .block();

            assertThat(etatDe(CanalDeNotification.IN_APP))
                    .isEqualTo(CompteRenduDeRemise.Etat.REMIS);
            verify(client, never()).pour(anyString());
        }
    }

    @Nested
    @DisplayName("Quand la personne a refusé le canal")
    class CanalRefuseParLaPersonne {

        /**
         * Le consentement est-il REELLEMENT applique.
         *
         * <p>Ce test existe parce que le contraire etait vrai : la garde de
         * consentement pouvait etre retiree de l'orchestrateur sans qu'aucun
         * test n'echoue. Le consentement etait lu, et personne ne verifiait
         * qu'il decidait de quoi que ce soit — exactement la forme de defaut
         * que ce depot passe son temps a deterrer.
         *
         * <p>Deux assertions, et la seconde porte autant que la premiere :
         * l'etat ecrit doit etre REFUSE, et le canal ne doit <b>pas avoir ete
         * appele</b>. Sans la seconde, un canal qui enverrait puis rendrait un
         * refus passerait le test — et le SMS serait parti.
         */
        @Test
        @DisplayName("le canal est REFUSÉ, et n'est même pas appelé")
        void canalRefuseEtJamaisAppele() {
            when(client.pour(anyString())).thenReturn(Mono.just(
                    new ClientContactsUtilisateur.Contacts("personne@exemple.test", null)));
            when(prefs.pour(anyString())).thenReturn(Mono.just(java.util.Map.of(
                    com.abdatytch.commons.notification.ConsentementDeNotification
                            .cle(CanalDeNotification.COURRIEL), "false")));

            var courriel = new CanalEspion(CanalDeNotification.COURRIEL,
                    CanalDeRemise.Resultat.remis());

            service(courriel)
                    .traiter(demande(List.of(CanalDeNotification.COURRIEL)))
                    .block();

            assertThat(etatDe(CanalDeNotification.COURRIEL))
                    .isEqualTo(CompteRenduDeRemise.Etat.REFUSE);
            assertThat(courriel.recu)
                    .withFailMessage("Le canal a ete appele malgre le refus de la "
                            + "personne : le message est parti")
                    .isNull();
        }

        /**
         * REFUSE et non ECHOUE, et la difference se chiffre en messages.
         *
         * <p>{@code aReprendre} exclut REFUSE et reprend ECHOUE. Un refus de la
         * personne marque ECHOUE serait donc rejoue toutes les deux minutes :
         * on renverrait un message a quelqu'un qui vient de dire non, en
         * boucle, jusqu'a epuisement du plafond de tentatives.
         */
        @Test
        @DisplayName("l'état est REFUSÉ, donc la reprise ne le rejouera jamais")
        void refusNonReessayable() {
            when(client.pour(anyString())).thenReturn(Mono.just(
                    new ClientContactsUtilisateur.Contacts(null, "+22370000000")));
            when(prefs.pour(anyString())).thenReturn(Mono.just(java.util.Map.of(
                    com.abdatytch.commons.notification.ConsentementDeNotification
                            .cle(CanalDeNotification.SMS), "false")));

            var sms = new CanalEspion(CanalDeNotification.SMS,
                    CanalDeRemise.Resultat.remis());

            service(sms).traiter(demande(List.of(CanalDeNotification.SMS))).block();

            assertThat(etatDe(CanalDeNotification.SMS))
                    .isNotEqualTo(CompteRenduDeRemise.Etat.ECHOUE);
            assertThat(etatDe(CanalDeNotification.SMS))
                    .isEqualTo(CompteRenduDeRemise.Etat.REFUSE);
        }

        /**
         * Un canal refuse n'empeche pas les autres.
         *
         * <p>Une alerte de securite vise plusieurs canaux. Si la personne a
         * refuse le SMS, elle doit quand meme la voir dans sa boite de
         * reception — refuser un canal n'est pas refuser d'etre prevenu.
         */
        @Test
        @DisplayName("refuser un canal n'empêche pas la boîte de réception")
        void refusNEmpechePasLesAutres() {
            when(client.pour(anyString())).thenReturn(Mono.just(
                    new ClientContactsUtilisateur.Contacts(null, "+22370000000")));
            when(prefs.pour(anyString())).thenReturn(Mono.just(java.util.Map.of(
                    com.abdatytch.commons.notification.ConsentementDeNotification
                            .cle(CanalDeNotification.SMS), "false")));

            var sms = new CanalEspion(CanalDeNotification.SMS,
                    CanalDeRemise.Resultat.remis());
            var inApp = new CanalEspion(CanalDeNotification.IN_APP,
                    CanalDeRemise.Resultat.remis());

            var compteRendu = service(sms, inApp)
                    .traiter(demande(List.of(CanalDeNotification.SMS,
                            CanalDeNotification.IN_APP)))
                    .block();

            assertThat(etatDe(CanalDeNotification.SMS))
                    .isEqualTo(CompteRenduDeRemise.Etat.REFUSE);
            assertThat(etatDe(CanalDeNotification.IN_APP))
                    .isEqualTo(CompteRenduDeRemise.Etat.REMIS);
            assertThat(compteRendu).isNotNull();
            assertThat(compteRendu.rendu()).isTrue();
        }
    }
}
