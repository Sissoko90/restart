package com.abdatytch.commons.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le detenteur unique de la consultation MaxMind.
 *
 * <h2>Ce que ces tests protegent</h2>
 *
 * Deux implementations lisaient les memes fichiers {@code .mmdb}, avec deux
 * <b>versions differentes</b> de la bibliotheque — {@code 5.0.2} dans la
 * passerelle, {@code 4.1.0} dans media-service. Un correctif devait donc etre
 * applique deux fois, sur deux API qui ne sont pas les memes.
 *
 * <h2>Pourquoi aucun fichier .mmdb n'est utilise ici</h2>
 *
 * Les bases MaxMind ne sont pas versionnees : elles pesent des dizaines de
 * megaoctets et leur licence interdit la redistribution. Un test qui en
 * dependrait echouerait sur toute machine ne les ayant pas telechargees, donc
 * en integration continue.
 *
 * <p>Ces tests portent donc sur ce qui vaut <b>sans</b> base : la degradation,
 * la reconnaissance des adresses privees, et l'absence de {@code Mono} vide. La
 * lecture elle-meme est le travail de MaxMind, pas le notre.
 */
@DisplayName("LocalisationIp — le detenteur unique de la consultation MaxMind")
class LocalisationIpTest {

    // ==================================================================
    @Nested
    @DisplayName("Sans base, la plateforme demarre et fonctionne")
    class SansBase {

        /**
         * Une base absente n'est pas une erreur.
         *
         * <p>Les fichiers MaxMind ne sont pas dans le depot. Refuser de demarrer
         * sans eux rendrait la plateforme indemarrable sur un poste de
         * developpement, pour une fonction qui n'est pas sur le chemin critique.
         */
        @Test
        @DisplayName("un chemin inexistant rend une instance inactive, sans lever")
        void baseAbsenteRendInactive() {
            LocalisationIp localisation = LocalisationIp.charger(
                    "/chemin/qui/nexiste/pas/GeoLite2-Country.mmdb", null, null);

            assertThat(localisation.estActive())
                    .withFailMessage("une instance se declare active sans base : "
                            + "chaque consultation echouerait ensuite")
                    .isFalse();
        }

        @Test
        @DisplayName("inactive, la consultation rend UNKNOWN et jamais un Mono vide")
        void inactiveRendInconnu() {
            // Le piege du Mono vide : une chaine d'autorisation qui recevrait un
            // Mono vide se terminerait sans decision — ni autorisee ni refusee.
            // Ce depot a corrige cette confusion une dizaine de fois.
            StepVerifier.create(LocalisationIp.inactive().codePays("8.8.8.8"))
                    .expectNext(LocalisationIp.INCONNU)
                    .verifyComplete();
        }

        @Test
        @DisplayName("inactive, les details sont partiels mais presents")
        void inactiveRendDesDetailsPartiels() {
            // Un refus ferait croire a une panne a l'administrateur qui consulte
            // une adresse sur une installation sans base MaxMind.
            StepVerifier.create(LocalisationIp.inactive().details("8.8.8.8"))
                    .assertNext(details -> {
                        assertThat(details.adresseIp()).isEqualTo("8.8.8.8");
                        assertThat(details.codePays()).isEqualTo(LocalisationIp.INCONNU);
                    })
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les adresses privees sont reconnues par leur STRUCTURE")
    class AdressesPrivees {

        /**
         * Ce que les deux implementations remplacees rataient.
         *
         * <p>Elles enumeraient les prefixes a la main — celle de la passerelle
         * listait {@code 172.16.} a {@code 172.31.} en seize tests
         * {@code startsWith}. Cette forme rate toute notation equivalente, et
         * une comparaison de chaines ne reconnait aucune adresse IPv6 privee.
         */
        @ParameterizedTest(name = "{0} est privee")
        @ValueSource(strings = {
                "127.0.0.1",
                "10.0.0.1",
                "192.168.1.1",
                "172.16.0.1",
                "172.31.255.254",
                // Au milieu de la plage : les implementations qui listaient les
                // prefixes un par un en oubliaient regulierement.
                "172.20.10.5",
                "169.254.1.1",
                "::1",
                "fe80::1",
        })
        @DisplayName("une adresse de reseau local ou de bouclage est reconnue")
        void adressePriveeReconnue(String adresse) {
            assertThat(LocalisationIp.estUneAdressePrivee(adresse))
                    .withFailMessage("%s n'est pas reconnue comme privee : la "
                            + "consultation irait chercher un pays qui n'existe pas, "
                            + "et le resultat serait compte comme inconnu", adresse)
                    .isTrue();
        }

        @ParameterizedTest(name = "{0} est publique")
        @ValueSource(strings = {
                "8.8.8.8",
                "41.223.10.5",
                // Hors de la plage privee 172.16/12, et donc publique. Une
                // comparaison par prefixe « 172. » les aurait toutes prises
                // pour privees.
                "172.15.0.1",
                "172.32.0.1",
                "2001:4860:4860::8888",
        })
        @DisplayName("une adresse publique n'est pas prise pour privee")
        void adressePubliqueNonConfondue(String adresse) {
            assertThat(LocalisationIp.estUneAdressePrivee(adresse))
                    .withFailMessage("%s est prise pour privee : elle echapperait "
                            + "alors au controle de pays", adresse)
                    .isFalse();
        }

        /**
         * Une adresse illisible n'est <b>pas</b> declaree privee.
         *
         * <p>La traiter comme locale la ferait echapper au controle de pays :
         * il suffirait d'envoyer un en-tete {@code X-Forwarded-For} malforme
         * pour contourner un blocage geographique.
         */
        @ParameterizedTest(name = "« {0} »")
        @NullAndEmptySource
        @ValueSource(strings = {"pas-une-adresse", "999.999.999.999", "  "})
        @DisplayName("une adresse illisible n'est pas declaree locale")
        void adresseIllisibleNonLocale(String adresse) {
            assertThat(LocalisationIp.estUneAdressePrivee(adresse))
                    .withFailMessage("""
                            « %s » est declaree locale.

                            Une adresse illisible traitee comme locale echappe au \
                            controle de pays : un en-tete X-Forwarded-For malforme \
                            suffirait a contourner un blocage geographique.""",
                            adresse)
                    .isFalse();
        }

        @Test
        @DisplayName("une adresse privee rend LOCAL, distinct de UNKNOWN")
        void adressePriveeRendLocal() {
            // Les deux situations sont differentes : « pas de pays a trouver »
            // et « pays introuvable ». Les confondre melangerait dans les
            // metriques le trafic interne et les echecs de consultation.
            StepVerifier.create(LocalisationIp.inactive().codePays("127.0.0.1"))
                    .expectNext(LocalisationIp.LOCAL)
                    .verifyComplete();

            assertThat(LocalisationIp.LOCAL).isNotEqualTo(LocalisationIp.INCONNU);
        }
    }
}
