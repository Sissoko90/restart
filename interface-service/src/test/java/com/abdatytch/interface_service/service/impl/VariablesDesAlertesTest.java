package com.abdatytch.interface_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.commons.notification.IMessagerieSortante;
import com.abdatytch.interface_service.dto.GeoLocationInfo;
import com.abdatytch.interface_service.service.FaitsDeTentativeAdmin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que l'emetteur fournit aux gabarits d'alerte.
 *
 * <h2>La moitie « emetteur » d'un contrat en deux moities</h2>
 *
 * L'autre est {@code AlertesDExploitationTest}, dans notification-service :
 * elle verifie que les marqueurs des gabarits sont exactement ces clefs-la.
 *
 * <p>Deux derivations independantes comparees a une meme liste. Une clef
 * oubliee ici laisse un marqueur VISIBLE dans le courriel recu — le moteur
 * laisse {@code {pays}} en place plutot que d'ecrire « null », precisement
 * pour qu'un tel oubli se diagnostique au lieu de se deviner.
 *
 * <h2>Pourquoi ce test existe maintenant</h2>
 *
 * Ces trois courriels n'avaient AUCUN test. Leur corps etait compose en HTML
 * dans {@code AdminController} et {@code RecoverySecretScheduler}, et passe a
 * une methode d'envoi generique qui acceptait n'importe quelle chaine. Rien ne
 * pouvait donc verifier ce qui partait — et ce qui partait etait la source HTML
 * du message, lisible telle quelle par le destinataire.
 */
@DisplayName("Les variables des alertes d'exploitation")
class VariablesDesAlertesTest {

    private static final Set<String> CLEFS_SECRET = Set.of("prenom", "secret", "heures");

    private static final Set<String> CLEFS_COMMUNES = Set.of(
            "courriel", "heure", "ip", "agent", "pays", "codePays", "ville", "region",
            "fuseau", "latitude", "longitude", "fournisseur", "organisation", "asn",
            "typeAppareil", "navigateur", "systeme");

    private static final String INCONNU = "non disponible";

    /** Capture la demande au lieu de l'envoyer. */
    private static final class MessagerieEspionne implements IMessagerieSortante {
        private final List<Demande> demandes = new ArrayList<>();

        @Override
        public Mono<Boolean> envoyer(Demande demande) {
            demandes.add(demande);
            return Mono.just(true);
        }

        @Override
        public boolean envoiSmsActif() {
            return false;
        }

        Demande derniere() {
            return demandes.get(demandes.size() - 1);
        }
    }

    private static Set<String> plus(Set<String> base, String... autres) {
        var tout = new java.util.LinkedHashSet<>(base);
        tout.addAll(List.of(autres));
        return tout;
    }

    private static GeoLocationInfo geoComplete() {
        var geo = new GeoLocationInfo();
        geo.setId("tentative-1");
        geo.setCountry("Mali");
        geo.setCountryCode("ML");
        geo.setCity("Bamako");
        geo.setRegion("District");
        geo.setTimezone("Africa/Bamako");
        geo.setLatitude(12.65);
        geo.setLongitude(-8.0);
        geo.setIsp("Fournisseur");
        geo.setOrganization("Organisation");
        geo.setAsn(37000);
        geo.setDeviceType("ORDINATEUR");
        geo.setBrowserFamily("Firefox");
        geo.setBrowserVersion("140");
        geo.setOsFamily("Linux");
        geo.setOsVersion("6.1");
        geo.setEventType("ADMIN_LOGIN_ATTEMPT");
        geo.setStatus("DETECTE");
        geo.setTimestamp("2026-11-11T19:00:00");
        return geo;
    }

    private static FaitsDeTentativeAdmin faits(GeoLocationInfo geo) {
        return new FaitsDeTentativeAdmin("admin@exemple.invalid", "198.51.100.7",
                "Mozilla/5.0", geo, 5, 3);
    }

    // ==================================================================
    @Nested
    @DisplayName("Les clefs fournies")
    class Clefs {

        @Test
        @DisplayName("le secret de recuperation fournit exactement ses trois clefs")
        void clefsDuSecret() {
            var espion = new MessagerieEspionne();
            new EmailServiceImpl(espion)
                    .envoyerLeSecretDeRecuperation("admin@exemple.invalid", "Makan",
                            "Y2hpZmZyZQ==", 24)
                    .block();

            assertThat(espion.derniere().variables().keySet())
                    .withFailMessage("""
                            Clefs fournies : %s
                            Clefs attendues par le gabarit : %s

                            Une clef manquante laisse son marqueur VISIBLE dans le \
                            courriel recu.""",
                            espion.derniere().variables().keySet(), CLEFS_SECRET)
                    .containsExactlyInAnyOrderElementsOf(CLEFS_SECRET);
        }

        @Test
        @DisplayName("l'alerte de connexion fournit exactement ses vingt-quatre clefs")
        void clefsDeLaConnexion() {
            var espion = new MessagerieEspionne();
            new EmailServiceImpl(espion)
                    .alerterSurUneConnexionAdmin("ops@exemple.invalid", faits(geoComplete()),
                            "https://exemple.invalid/c", "https://exemple.invalid/b", 30)
                    .block();

            assertThat(espion.derniere().variables().keySet())
                    .containsExactlyInAnyOrderElementsOf(plus(CLEFS_COMMUNES,
                            "identifiant", "evenement", "statut", "horodatage",
                            "confirmer", "bloquer", "minutes"));
        }

        @Test
        @DisplayName("le rapport d'attaque fournit exactement ses vingt et une clefs")
        void clefsDeLAttaque() {
            var espion = new MessagerieEspionne();
            new EmailServiceImpl(espion)
                    .alerterSurUneAttaqueAdmin("ops@exemple.invalid", faits(geoComplete()),
                            "https://exemple.invalid/m", 24)
                    .block();

            assertThat(espion.derniere().variables().keySet())
                    .containsExactlyInAnyOrderElementsOf(plus(CLEFS_COMMUNES,
                            "tentatives", "tentativesApresConfirmation",
                            "maintenance", "heures"));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Une geolocalisation absente")
    class SansGeolocalisation {

        @Test
        @DisplayName("fournit les MEMES clefs, avec « non disponible »")
        void memesClefsEtValeurExplicite() {
            var espion = new MessagerieEspionne();
            new EmailServiceImpl(espion)
                    .alerterSurUneConnexionAdmin("ops@exemple.invalid", faits(null),
                            "https://exemple.invalid/c", "https://exemple.invalid/b", 30)
                    .block();

            var variables = espion.derniere().variables();

            assertThat(variables.keySet())
                    .withFailMessage("""
                            Une geolocalisation absente change le jeu de clefs.

                            C'est le defaut d'origine : la branche d'erreur portait \
                            un SECOND gabarit, plus court, que rien n'obligeait a \
                            rester d'accord avec le premier.""")
                    .containsExactlyInAnyOrderElementsOf(plus(CLEFS_COMMUNES,
                            "identifiant", "evenement", "statut", "horodatage",
                            "confirmer", "bloquer", "minutes"));

            assertThat(variables.get("pays")).isEqualTo(INCONNU);
            assertThat(variables.get("latitude")).isEqualTo(INCONNU);
            assertThat(variables.get("navigateur")).isEqualTo(INCONNU);

            assertThat(variables.get("ip"))
                    .withFailMessage("""
                            L'adresse IP a disparu avec la geolocalisation.

                            Elle vient de la REQUETE, pas du service tiers : c'est \
                            le seul fait encore certain quand ce service est muet.""")
                    .isEqualTo("198.51.100.7");

            assertThat(variables.get("agent"))
                    .withFailMessage("""
                            Le User-Agent a disparu avec la geolocalisation.

                            L'ancien code l'affichait depuis geoInfo.getDeviceInfo(), \
                            donc il s'effacait exactement quand on en avait le plus \
                            besoin. Il vient desormais de la requete.""")
                    .isEqualTo("Mozilla/5.0");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'urgence decide du compte d'expedition")
    class Urgence {

        @Test
        @DisplayName("le rapport d'attaque part en CRITIQUE, l'alerte de connexion non")
        void urgencesDistinctes() {
            var espion = new MessagerieEspionne();
            var service = new EmailServiceImpl(espion);

            service.alerterSurUneConnexionAdmin("ops@exemple.invalid", faits(null),
                    "https://exemple.invalid/c", "https://exemple.invalid/b", 30).block();
            assertThat(espion.derniere().urgence())
                    .isEqualTo(DemandeDeNotification.Urgence.NORMALE);

            service.alerterSurUneAttaqueAdmin("ops@exemple.invalid", faits(null),
                    "https://exemple.invalid/m", 24).block();
            assertThat(espion.derniere().urgence())
                    .withFailMessage("""
                            Le rapport d'attaque ne part plus en CRITIQUE.

                            C'est l'urgence qui lui fait emprunter le compte \
                            d'expedition dedie, avec repli sur le compte ordinaire \
                            si celui-la echoue. En NORMALE, l'alerte la plus grave \
                            du service partagerait la reputation d'expedition du \
                            trafic courant.""")
                    .isEqualTo(DemandeDeNotification.Urgence.CRITIQUE);
        }

        @Test
        @DisplayName("les trois partent par COURRIEL")
        void canalCourriel() {
            var espion = new MessagerieEspionne();
            new EmailServiceImpl(espion)
                    .envoyerLeSecretDeRecuperation("a@exemple.invalid", "M", "x", 24).block();
            assertThat(espion.derniere().canal()).isEqualTo(CanalDeNotification.COURRIEL);
        }
    }
}
