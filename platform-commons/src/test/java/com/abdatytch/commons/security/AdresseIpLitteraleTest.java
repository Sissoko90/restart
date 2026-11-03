package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La lecture d'une adresse littérale.
 *
 * <h2>Ce que ce test établit</h2>
 *
 * Deux choses, et la seconde est la raison d'être de la classe.
 *
 * <p>D'abord que les deux côtés du contrat s'accordent : la forme écrite comme
 * clé par security-service est celle que le gateway produit depuis l'adresse de
 * la socket. Une divergence ici serait silencieuse — lire une clé absente rend
 * « pas bloqué » — et une règle posée par un administrateur n'arrêterait
 * personne.
 *
 * <p>Ensuite qu'<b>aucune résolution de nom</b> n'est déclenchée. C'est le
 * défaut que cette classe corrige : la validation précédente, dans
 * {@code ClientIpResolver}, acceptait la <i>forme</i> {@code 999.1.1.1} puis
 * confiait la lecture au JDK, qui échouait et partait interroger le résolveur —
 * un appel bloquant de plus de cent millisecondes sur une EventLoop, déclenché
 * par une valeur d'en-tête que l'appelant choisit.
 */
@DisplayName("AdresseIpLitterale - lire une adresse sans jamais resoudre de nom")
class AdresseIpLitteraleTest {

    @Nested
    @DisplayName("Formes acceptees")
    class FormesAcceptees {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "41.223.10.5,          41.223.10.5",
                "127.0.0.1,            127.0.0.1",
                "0.0.0.0,              0.0.0.0",
                "255.255.255.255,      255.255.255.255",
                // Les zéros de tête sont absorbés, et lus en DÉCIMAL. Les lire en
                // octal — ce que fait inet_aton en C — ferait de 010 la valeur 8,
                // et la règle porterait sur une autre machine que celle visée.
                "041.223.010.005,      41.223.10.5",
                "010.010.010.010,      10.10.10.10",
        })
        @DisplayName("IPv4 : la forme canonique absorbe les zeros de tete")
        void ipv4(String saisie, String attendue) {
            assertThat(AdresseIpLitterale.canonique(saisie)).isEqualTo(attendue);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                // L'IPv6 n'est PAS comprimée : c'est la forme que rend
                // getHostAddress des deux côtés du contrat. Comprimer ici
                // donnerait une clé que le gateway ne formerait jamais.
                "2001:db8::1,                              2001:db8:0:0:0:0:0:1",
                "2001:0db8:0000:0000:0000:0000:0000:0001,  2001:db8:0:0:0:0:0:1",
                "::1,                                      0:0:0:0:0:0:0:1",
        })
        @DisplayName("IPv6 : forme non comprimee, identique a celle du JDK")
        void ipv6(String saisie, String attendue) {
            assertThat(AdresseIpLitterale.canonique(saisie)).isEqualTo(attendue);
        }

        @Test
        @DisplayName("Les espaces autour de l'adresse sont tolerees")
        void espaces() {
            assertThat(AdresseIpLitterale.canonique("  41.223.10.5  "))
                    .isEqualTo("41.223.10.5");
        }

        @Test
        @DisplayName("Deux ecritures d'une meme machine rendent la MEME cle")
        void memeMachineMemeCle() {
            // L'invariant qui tient tout l'edifice : sans lui, une regle posee
            // sur l'une des deux ecritures ne serait jamais consultee.
            assertThat(AdresseIpLitterale.canonique("041.223.010.005"))
                    .isEqualTo(AdresseIpLitterale.canonique("41.223.10.5"));
        }
    }

    @Nested
    @DisplayName("Formes refusees")
    class FormesRefusees {

        @ParameterizedTest
        @ValueSource(strings = {
                // Bien formée au sens de l'expression régulière, mais hors
                // bornes. C'est LE cas qui déclenchait une résolution DNS.
                "999.1.1.1",
                "256.1.1.1",
                "1.1.1.256",
                // Trop ou pas assez de groupes.
                "1.2.3.4.5",
                "1.2.3",
                "10.1",
                // Un nom d'hôte, même s'il n'est composé que de chiffres
                // hexadécimaux : sans deux-points, le JDK y verrait un nom.
                "abcdef",
                "localhost",
                "exemple.test",
                // Un préfixe CIDR n'est pas une adresse. Le refuser ici est ce
                // qui permet à security-service de le refuser avec un message
                // nommé plutôt que de l'enregistrer sans effet.
                "41.223.0.0/16",
                "::/0",
                // IPv6 malformée.
                "ab:cd:ef:1",
                ":::",
                // Vide et blanc.
                "",
                "   ",
        })
        @DisplayName("Rendent null, donc refus")
        void refusees(String saisie) {
            assertThat(AdresseIpLitterale.canonique(saisie)).isNull();
            assertThat(AdresseIpLitterale.octets(saisie)).isNull();
            assertThat(AdresseIpLitterale.estLitterale(saisie)).isFalse();
        }

        @Test
        @DisplayName("null rend null, sans lever")
        void nulle() {
            // Le filtre du gateway appelle cette méthode sur une valeur
            // d'en-tête absente : lever ici casserait la requête au lieu de la
            // refuser.
            assertThat(AdresseIpLitterale.canonique(null)).isNull();
            assertThat(AdresseIpLitterale.octets(null)).isNull();
            assertThat(AdresseIpLitterale.estLitterale(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Aucune resolution de nom")
    class AucuneResolution {

        /**
         * Le défaut corrigé, mesuré.
         *
         * <h3>Pourquoi un test de durée, qui est d'ordinaire une mauvaise idée</h3>
         *
         * Parce que c'est la seule façon d'observer la différence : la version
         * précédente et celle-ci rendent le <b>même résultat</b> — {@code null}
         * — pour {@code 999.1.1.1}. Seul le chemin parcouru diffère, et il ne
         * se voit qu'au temps passé.
         *
         * <h3>Pourquoi le seuil ne rend pas le test instable</h3>
         *
         * L'écart mesuré n'est pas marginal : ~113 ms par appel contre ~0,1 ms,
         * soit trois ordres de grandeur. Le seuil est posé à deux secondes pour
         * cent appels, quand la version fautive en demandait onze. Une machine
         * lente ou chargée reste très loin du seuil ; seul un retour du DNS le
         * franchit.
         *
         * <p>Les adresses sont toutes <b>distinctes</b> : une valeur répétée
         * serait servie par le cache de résolution négatif et le test passerait
         * même avec le défaut présent.
         */
        @Test
        @DisplayName("Cent formes IPv4 invalides ne declenchent aucun DNS")
        void pasDeDnsSurIpv4Invalide() {
            String[] invalides = new String[100];
            for (int rang = 0; rang < invalides.length; rang++) {
                invalides[rang] = "999.1.1." + rang;
            }

            long debut = System.nanoTime();
            for (String invalide : invalides) {
                assertThat(AdresseIpLitterale.octets(invalide)).isNull();
            }
            Duration ecoule = Duration.ofNanos(System.nanoTime() - debut);

            assertThat(ecoule)
                    .as("une resolution DNS par appel couterait plus de 10 s au total")
                    .isLessThan(Duration.ofSeconds(2));
        }

        @Test
        @DisplayName("Cent noms d'hote ne declenchent aucun DNS")
        void pasDeDnsSurNomDHote() {
            // Sans deux-points, une chaîne hexadécimale est un nom pour le JDK :
            // mesuré à 2192 ms pour vingt appels. C'est pourquoi le deux-points
            // est exigé avant de confier quoi que ce soit à getByName.
            String[] noms = new String[100];
            for (int rang = 0; rang < noms.length; rang++) {
                noms[rang] = "abcdef" + rang;
            }

            long debut = System.nanoTime();
            for (String nom : noms) {
                assertThat(AdresseIpLitterale.octets(nom)).isNull();
            }
            Duration ecoule = Duration.ofNanos(System.nanoTime() - debut);

            assertThat(ecoule).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Nested
    @DisplayName("Accord avec la forme produite par le gateway")
    class AccordAvecLeGateway {

        /**
         * Ce que le gateway écrit comme clé vient de
         * {@code remote.getAddress().getHostAddress()}. Cette méthode doit rendre
         * la même chaîne, sinon la règle et la requête ne se rencontrent pas.
         */
        @ParameterizedTest
        @ValueSource(strings = {"41.223.10.5", "127.0.0.1", "2001:db8::1", "::1"})
        @DisplayName("canonique() est idempotente : la forme du JDK est un point fixe")
        void idempotente(String adresse) {
            String canonique = AdresseIpLitterale.canonique(adresse);
            assertThat(AdresseIpLitterale.canonique(canonique))
                    .as("repasser une forme canonique doit la laisser inchangee")
                    .isEqualTo(canonique);
        }
    }
}
