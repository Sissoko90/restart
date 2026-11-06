package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.ConsentementDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.notification_service.client.ClientContactsUtilisateur;
import com.abdatytch.notification_service.client.ClientPreferencesUtilisateur;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Où joindre une personne")
class ResolveurDeCoordonneesTest {

    private static final String PORTEUR = "11111111-1111-1111-1111-111111111111";
    private static final String COURRIEL = "personne@exemple.test";
    private static final String TELEPHONE = "+22370000000";

    private final ClientContactsUtilisateur client = mock(ClientContactsUtilisateur.class);
    private final ClientPreferencesUtilisateur prefs = mock(ClientPreferencesUtilisateur.class);

    /**
     * Les defauts de PRODUCTION : courriel accorde, SMS et push refuses.
     *
     * <p>Et non des valeurs choisies pour arranger les tests. Un jeu de defauts
     * different ici rendrait les tests verts sur une politique que personne
     * n'execute — l'erreur que ce depot a payee avec un @Value de disjoncteur
     * surcharge par la configuration.
     */
    private final ResolveurDeCoordonnees resolveur =
            new ResolveurDeCoordonnees(client, prefs, true, false, false);

    @org.junit.jupiter.api.BeforeEach
    void aucunePreferenceParDefaut() {
        // Le cas de la quasi-totalite des comptes : la personne n'a rien regle.
        when(prefs.pour(anyString())).thenReturn(Mono.just(Map.of()));
    }

    private static DemandeDeNotification demande(List<CanalDeNotification> canaux,
                                                 String secours) {
        return new DemandeDeNotification(UUID.randomUUID(), PORTEUR, canaux,
                "modele-quelconque", Map.of(),
                DemandeDeNotification.Urgence.NORMALE, "test", secours, Instant.now());
    }

    @Nested
    @DisplayName("Trois réponses, et non deux")
    class TroisReponses {

        /**
         * La distinction qui decide du sort de la notification.
         *
         * <p>« user-service n'a pas repondu » et « cette personne n'a pas
         * d'adresse » menent au meme resultat immediat — aucune coordonnee —
         * mais pas au meme sort : le premier doit etre reessaye, le second est
         * definitif. Les confondre perdrait des notifications a chaque incident
         * de user-service, en les marquant non reessayables.
         */
        @Test
        @DisplayName("user-service injoignable rend un état INDISPONIBLE, distinct de « aucun contact »")
        void panneDistincteDeAbsence() {
            when(client.pour(anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("injoignable")));

            var coordonnees = resolveur
                    .contactsPour(demande(List.of(CanalDeNotification.COURRIEL), null))
                    .block();

            assertThat(coordonnees).isNotNull();
            assertThat(coordonnees.indisponible()).isTrue();
        }

        @Test
        @DisplayName("une personne sans contact rend un état disponible, mais sans coordonnée")
        void absenceDeContact() {
            when(client.pour(anyString()))
                    .thenReturn(Mono.just(ClientContactsUtilisateur.AUCUN));

            var demande = demande(List.of(CanalDeNotification.COURRIEL), null);
            var coordonnees = resolveur.contactsPour(demande).block();

            assertThat(coordonnees).isNotNull();
            assertThat(coordonnees.indisponible()).isFalse();
            assertThat(resolveur.pour(demande, CanalDeNotification.COURRIEL, coordonnees))
                    .isNull();
        }

        /**
         * Une erreur ne doit JAMAIS remonter de cette methode.
         *
         * <p>Propagee, elle ferait echouer la notification entiere — y compris
         * ses canaux IN_APP et WEBSOCKET, qui n'avaient besoin d'aucune
         * coordonnee.
         */
        @Test
        @DisplayName("aucune erreur ne remonte : elle est traduite en état")
        void aucuneErreurNeRemonte() {
            when(client.pour(anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("injoignable")));

            var demande = demande(
                    List.of(CanalDeNotification.COURRIEL, CanalDeNotification.IN_APP), null);

            // `block()` releverait l'erreur si elle etait propagee.
            assertThat(resolveur.contactsPour(demande).block()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Quand user-service n'est pas appelé du tout")
    class AucunAppel {

        /**
         * Une notification en boite de reception ne doit dependre d'aucun autre
         * service, ni lui couter une requete. C'est aussi ce qui garde la chaine
         * utile pendant une panne de user-service.
         */
        @Test
        @DisplayName("des canaux sans coordonnée n'appellent pas user-service")
        void canauxSansCoordonnee() {
            var demande = demande(List.of(CanalDeNotification.IN_APP,
                    CanalDeNotification.WEBSOCKET), null);

            assertThat(resolveur.contactsPour(demande).block()).isNotNull();

            verify(client, never()).pour(anyString());
        }

        /**
         * La coordonnee de secours designe une personne qui n'a PAS de profil —
         * confirmation d'inscription, invitation. L'appel echouerait donc, et
         * serait de toute facon inutile.
         */
        @Test
        @DisplayName("une coordonnée de secours court-circuite l'appel, et gagne sur tout")
        void secoursCourtCircuite() {
            var demande = demande(List.of(CanalDeNotification.COURRIEL), COURRIEL);

            var coordonnees = resolveur.contactsPour(demande).block();

            verify(client, never()).pour(anyString());
            assertThat(resolveur.pour(demande, CanalDeNotification.COURRIEL, coordonnees))
                    .isEqualTo(COURRIEL);
        }
    }

    @Nested
    @DisplayName("Le champ choisi par canal")
    class ChampParCanal {

        private ResolveurDeCoordonnees.Coordonnees coordonneesCompletes() {
            var demande = demande(List.of(CanalDeNotification.COURRIEL), null);
            when(client.pour(anyString())).thenReturn(Mono.just(
                    new ClientContactsUtilisateur.Contacts(COURRIEL, TELEPHONE)));
            return resolveur.contactsPour(demande).block();
        }

        @Test
        @DisplayName("COURRIEL prend l'adresse, SMS et PUSH prennent le numéro")
        void champParCanal() {
            var coordonnees = coordonneesCompletes();
            var demande = demande(List.of(CanalDeNotification.COURRIEL), null);

            assertThat(resolveur.pour(demande, CanalDeNotification.COURRIEL, coordonnees))
                    .isEqualTo(COURRIEL);
            assertThat(resolveur.pour(demande, CanalDeNotification.SMS, coordonnees))
                    .isEqualTo(TELEPHONE);
            assertThat(resolveur.pour(demande, CanalDeNotification.PUSH, coordonnees))
                    .isEqualTo(TELEPHONE);
        }

        @Test
        @DisplayName("IN_APP et WEBSOCKET n'ont pas de coordonnée")
        void canauxSansCoordonneeRendentNull() {
            var coordonnees = coordonneesCompletes();
            var demande = demande(List.of(CanalDeNotification.IN_APP), null);

            assertThat(resolveur.pour(demande, CanalDeNotification.IN_APP, coordonnees))
                    .isNull();
            assertThat(resolveur.pour(demande, CanalDeNotification.WEBSOCKET, coordonnees))
                    .isNull();
        }

        /**
         * Un champ vide vaut « inconnu ».
         *
         * <p>Rendre la chaine vide telle quelle enverrait le canal l'essayer :
         * un courriel vers une adresse vide, donc un refus du serveur SMTP a la
         * place d'un refus clair ici.
         */
        @Test
        @DisplayName("un champ vide ou blanc vaut inconnu")
        void champVideVautInconnu() {
            var demande = demande(List.of(CanalDeNotification.COURRIEL), null);
            when(client.pour(anyString())).thenReturn(Mono.just(
                    new ClientContactsUtilisateur.Contacts("   ", null)));

            var coordonnees = resolveur.contactsPour(demande).block();

            assertThat(resolveur.pour(demande, CanalDeNotification.COURRIEL, coordonnees))
                    .isNull();
            assertThat(resolveur.pour(demande, CanalDeNotification.SMS, coordonnees))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("Quels canaux ont besoin d'une coordonnée")
    class BesoinDeCoordonnee {

        /**
         * Cette methode existe pour que l'orchestrateur ne marque ECHOUE que les
         * canaux concernes quand user-service est injoignable.
         *
         * <p>Sans elle, une panne de user-service faisait echouer jusqu'a la
         * boite de reception — un canal qui ne depend de personne. Le defaut a
         * ete attrape a l'ecriture, et ce test l'empeche de revenir.
         */
        @Test
        @DisplayName("seuls COURRIEL, SMS et PUSH en ont besoin")
        void seulsLesCanauxACoordonnee() {
            assertThat(resolveur.aBesoinDUneCoordonnee(CanalDeNotification.COURRIEL)).isTrue();
            assertThat(resolveur.aBesoinDUneCoordonnee(CanalDeNotification.SMS)).isTrue();
            assertThat(resolveur.aBesoinDUneCoordonnee(CanalDeNotification.PUSH)).isTrue();

            assertThat(resolveur.aBesoinDUneCoordonnee(CanalDeNotification.IN_APP)).isFalse();
            assertThat(resolveur.aBesoinDUneCoordonnee(CanalDeNotification.WEBSOCKET)).isFalse();
        }
    }

    @Nested
    @DisplayName("Le consentement de la personne")
    class Consentement {

        private ResolveurDeCoordonnees.Coordonnees avecPreferences(
                java.util.Map<String, String> reglages) {
            when(client.pour(anyString())).thenReturn(Mono.just(
                    new ClientContactsUtilisateur.Contacts(COURRIEL, TELEPHONE)));
            when(prefs.pour(anyString())).thenReturn(Mono.just(reglages));
            return resolveur.contactsPour(
                    demande(List.of(CanalDeNotification.SMS), null)).block();
        }

        /**
         * Le silence n'est pas un oui.
         *
         * <p>C'est le cas de la quasi-totalite des comptes, et la politique de
         * defaut tranche : SMS et push refuses, parce qu'un SMS se paie et
         * arrive sur le telephone de la personne. Dans le doute, ne pas envoyer
         * est reparable ; envoyer ne l'est pas.
         */
        @Test
        @DisplayName("sans préférence exprimée, les défauts par canal s'appliquent")
        void defautsParCanal() {
            var coordonnees = avecPreferences(java.util.Map.of());

            assertThat(resolveur.consent(CanalDeNotification.COURRIEL, coordonnees)).isTrue();
            assertThat(resolveur.consent(CanalDeNotification.SMS, coordonnees)).isFalse();
            assertThat(resolveur.consent(CanalDeNotification.PUSH, coordonnees)).isFalse();
        }

        @Test
        @DisplayName("un refus explicite est respecté, même quand le défaut accorde")
        void refusExpliciteRespecte() {
            var coordonnees = avecPreferences(java.util.Map.of(
                    ConsentementDeNotification.cle(CanalDeNotification.COURRIEL), "false"));

            assertThat(resolveur.consent(CanalDeNotification.COURRIEL, coordonnees)).isFalse();
        }

        @Test
        @DisplayName("un accord explicite ouvre un canal refusé par défaut")
        void accordExpliciteOuvre() {
            var coordonnees = avecPreferences(java.util.Map.of(
                    ConsentementDeNotification.cle(CanalDeNotification.SMS), "true"));

            assertThat(resolveur.consent(CanalDeNotification.SMS, coordonnees)).isTrue();
        }

        /**
         * IN_APP et WEBSOCKET ne demandent aucun consentement.
         *
         * <p>Ils remettent dans la propre boite de reception de la personne, sur
         * sa propre session. Les soumettre au consentement aurait permis de lui
         * cacher ses propres messages — et, avec un defaut a faux, l'aurait fait
         * pour tout le monde d'un coup.
         */
        @Test
        @DisplayName("la boîte de réception et le flux temps réel ne demandent rien")
        void canauxInternesToujoursAccordes() {
            var coordonnees = avecPreferences(java.util.Map.of(
                    ConsentementDeNotification.cle(CanalDeNotification.COURRIEL), "false"));

            assertThat(resolveur.consent(CanalDeNotification.IN_APP, coordonnees)).isTrue();
            assertThat(resolveur.consent(CanalDeNotification.WEBSOCKET, coordonnees)).isTrue();
        }

        /**
         * Une panne de setting-service ne doit pas tout couper.
         *
         * <p>Le client rend une carte vide, donc les defauts s'appliquent — et
         * non un refus general. Traiter la panne comme un refus couperait les
         * codes de connexion : on empecherait les gens de se connecter pour
         * proteger une preference qu'ils n'ont probablement pas exprimee.
         *
         * <p>Et ce n'est pas une ouverture : le defaut du SMS et du push EST le
         * refus. Seul le courriel passe, qui est le canal des confirmations.
         */
        @Test
        @DisplayName("une panne de setting-service applique les défauts, pas un refus général")
        void panneAppliqueLesDefauts() {
            var coordonnees = avecPreferences(java.util.Map.of());

            assertThat(resolveur.consent(CanalDeNotification.COURRIEL, coordonnees)).isTrue();
            assertThat(resolveur.consent(CanalDeNotification.SMS, coordonnees)).isFalse();
        }

        /**
         * Toute valeur autre que « true » vaut refus.
         *
         * <p>Le magasin accepte des chaines libres. Une valeur abimee ne doit
         * pas rouvrir un canal que la personne pensait avoir ferme.
         */
        @Test
        @DisplayName("une valeur illisible vaut refus, pas « on prend le défaut »")
        void valeurIllisibleVautRefus() {
            var coordonnees = avecPreferences(java.util.Map.of(
                    ConsentementDeNotification.cle(CanalDeNotification.COURRIEL), "peut-etre"));

            assertThat(resolveur.consent(CanalDeNotification.COURRIEL, coordonnees)).isFalse();
        }
    }
}
