package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PUSH n'a plus besoin de user-service, mais garde son consentement.
 *
 * <h2>Le defaut que ces tests empechent</h2>
 *
 * {@code contactsPour} decidait de ses appels sortants sur UNE question : « un
 * canal demande-t-il une coordonnee ? ». Cela suffisait tant que les canaux a
 * consentement etaient exactement les canaux a coordonnee.
 *
 * <p>PUSH les separe. Un jeton d'appareil est une donnee LOCALE a ce service —
 * {@code CanalPush} le lit dans sa propre table — donc PUSH n'a besoin
 * d'aucune coordonnee de profil. Mais il demande bien le consentement de la
 * personne : c'est un canal qui fait sonner un telephone, et
 * {@code ConsentementDeNotification.CANAUX_A_CONSENTEMENT} l'inclut.
 *
 * <p>Avec la condition unique, une notification purement PUSH aurait pris le
 * court-circuit « rien a lire » et recu une carte de preferences VIDE : le
 * consentement serait retombe sur la politique par defaut au lieu du choix
 * exprime. Un refus de la personne aurait ete ignore, en silence — et le defaut
 * du push EST le refus, donc le symptome aurait ete inverse : des push a qui
 * n'en voulait pas, et aucun test rouge.
 */
@DisplayName("PUSH : sans coordonnee, mais avec consentement")
class ResolveurPushSansCoordonneeTest {

    private static final String PORTEUR = "11111111-1111-1111-1111-111111111111";

    private final ClientContactsUtilisateur contacts = mock(ClientContactsUtilisateur.class);
    private final ClientPreferencesUtilisateur prefs = mock(ClientPreferencesUtilisateur.class);

    /** Les defauts de PRODUCTION : courriel accorde, SMS et push refuses. */
    private final ResolveurDeCoordonnees resolveur =
            new ResolveurDeCoordonnees(contacts, prefs, true, false, false);

    private static DemandeDeNotification demande(CanalDeNotification... canaux) {
        return new DemandeDeNotification(java.util.UUID.randomUUID(), PORTEUR,
                List.of(canaux), "securite.code-de-verification", Map.of(),
                DemandeDeNotification.Urgence.CRITIQUE, "test", null, Instant.now());
    }

    @Nested
    @DisplayName("Les appels sortants")
    class AppelsSortants {

        /**
         * LE test de ce changement : PUSH lit les preferences, pas le profil.
         */
        @Test
        @DisplayName("PUSH seul : setting-service est appelé, user-service NON")
        void pushSeulNAppellePasUserService() {
            when(prefs.pour(anyString())).thenReturn(Mono.just(
                    Map.of("notifications.canal.push", "true")));

            var coordonnees = resolveur.contactsPour(demande(CanalDeNotification.PUSH))
                    .block();

            verify(prefs).pour(PORTEUR);
            verify(contacts, never()).pour(anyString());

            assertThat(coordonnees).isNotNull();
            assertThat(resolveur.consent(CanalDeNotification.PUSH, coordonnees))
                    .withFailMessage("""
                            Le consentement exprime pour PUSH n'a pas ete lu.

                            C'est le defaut que la separation evite : avec une \
                            condition unique sur « faut-il une coordonnee », une \
                            notification purement PUSH recevait une carte de \
                            preferences vide, et le defaut — qui est le REFUS — \
                            s'appliquait a la place du choix de la personne.""")
                    .isTrue();
        }

        /**
         * Et un refus exprime est bien respecte.
         *
         * <p>Sans cette seconde assertion, une implementation qui rendrait
         * toujours « accorde » passerait le test precedent.
         */
        @Test
        @DisplayName("PUSH seul : un refus exprimé est respecté")
        void pushSeulRespecteLeRefus() {
            when(prefs.pour(anyString())).thenReturn(Mono.just(
                    Map.of("notifications.canal.push", "false")));

            var coordonnees = resolveur.contactsPour(demande(CanalDeNotification.PUSH))
                    .block();

            assertThat(resolveur.consent(CanalDeNotification.PUSH, coordonnees))
                    .withFailMessage("un refus exprime pour PUSH est ignore")
                    .isFalse();
        }

        /**
         * La boite de reception seule n'appelle toujours RIEN.
         *
         * <p>Le court-circuit d'origine doit survivre a la separation : une
         * notification purement IN_APP ne doit dependre d'aucun autre service.
         */
        @Test
        @DisplayName("boîte de réception seule : aucun appel sortant")
        void boiteSeuleNAppelleRien() {
            resolveur.contactsPour(demande(CanalDeNotification.IN_APP,
                    CanalDeNotification.WEBSOCKET)).block();

            verify(contacts, never()).pour(anyString());
            verify(prefs, never()).pour(anyString());
        }

        /**
         * Un canal a coordonnee appelle bien les DEUX.
         *
         * <p>Sans cette assertion, une implementation qui n'appellerait jamais
         * user-service passerait les tests ci-dessus.
         */
        @Test
        @DisplayName("SMS : les deux services sont appelés")
        void smsAppelleLesDeux() {
            when(contacts.pour(anyString()))
                    .thenReturn(Mono.just(ClientContactsUtilisateur.AUCUN));
            when(prefs.pour(anyString())).thenReturn(Mono.just(Map.of()));

            resolveur.contactsPour(demande(CanalDeNotification.SMS)).block();

            verify(contacts).pour(PORTEUR);
            verify(prefs).pour(PORTEUR);
        }
    }

    @Nested
    @DisplayName("La coordonnée rendue au canal")
    class CoordonneeRendue {

        /**
         * PUSH ne recoit PLUS de coordonnee, et c'est le point.
         *
         * <p>Il recevait le NUMERO DE TELEPHONE faute de jeton d'appareil — ce
         * que le commentaire du resolveur admettait — donc le canal refusait
         * toujours, apres avoir paye un appel a user-service.
         */
        @Test
        @DisplayName("PUSH reçoit null, et non un numéro de téléphone")
        void pushNeRecoitPlusDeNumero() {
            when(prefs.pour(anyString())).thenReturn(Mono.just(Map.of()));

            var demande = demande(CanalDeNotification.PUSH);
            var coordonnees = resolveur.contactsPour(demande).block();

            assertThat(resolveur.pour(demande, CanalDeNotification.PUSH, coordonnees))
                    .withFailMessage("""
                            PUSH recoit encore une coordonnee.

                            Un jeton d'appareil n'est pas une coordonnee de \
                            profil : il appartient a un telephone, le systeme le \
                            renouvelle sans que le compte change, et une personne \
                            en a autant qu'elle possede d'appareils. CanalPush \
                            les lit dans sa propre table.""")
                    .isNull();
        }

        /**
         * Et une panne de user-service ne bloque plus un push.
         *
         * <p>C'est la consequence heureuse du changement : la garde
         * « coordonnees indisponibles » de l'orchestrateur ne s'applique qu'aux
         * canaux a coordonnee.
         */
        @Test
        @DisplayName("PUSH n'a pas besoin de coordonnée, donc une panne de profil ne le bloque pas")
        void pushNeDependPlusDuProfil() {
            assertThat(resolveur.aBesoinDUneCoordonnee(CanalDeNotification.PUSH))
                    .withFailMessage("""
                            PUSH est encore declare « a coordonnee ».

                            L'orchestrateur marquerait donc ECHOUE tout push \
                            emis pendant une panne de user-service, alors que \
                            les jetons d'appareil sont dans la base de CE \
                            service et n'ont besoin de personne.""")
                    .isFalse();
            // Et les canaux qui en ont vraiment besoin le declarent toujours.
            assertThat(resolveur.aBesoinDUneCoordonnee(CanalDeNotification.SMS)).isTrue();
            assertThat(resolveur.aBesoinDUneCoordonnee(CanalDeNotification.COURRIEL)).isTrue();
        }
    }
}
