package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'est une adresse « interne » pour la plateforme.
 *
 * <h2>Deux copies, deux erreurs opposées</h2>
 *
 * Chaque service décidait seul, et les deux versions trouvées se trompaient en
 * sens contraire :
 *
 * <ul>
 *   <li>auth-service acceptait {@code startsWith("172.")}, donc
 *       <b>172.0.0.0/8</b> : 16,7 millions d'adresses dont l'essentiel est de
 *       l'espace public routable. Or ce contrôle est le dernier rempart contre
 *       un appel direct ;</li>
 *   <li>interface-service n'acceptait que {@code 127.0.0.1}, {@code ::1} et
 *       <b>{@code 10.10.10.47}</b> — une adresse de machine écrite en dur. Le
 *       Gateway devenait injoignable dès qu'il changeait d'adresse, et
 *       notamment dès qu'il tournait en conteneur.</li>
 * </ul>
 *
 * <p>Les deux tests ci-dessous figent exactement ces deux erreurs, pour
 * qu'aucune ne revienne par un côté ou par l'autre.
 */
@DisplayName("PrivateNetworks - ce qui est une adresse interne")
class PrivateNetworksTest {

    // ==================================================================
    @Nested
    @DisplayName("Accepté")
    class Accepte {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "127.0.0.1", "127.0.0.53",
                "::1", "0:0:0:0:0:0:0:1"
        })
        @DisplayName("la boucle locale, sous ses différentes formes")
        void boucleLocale(String ip) {
            // La forme etendue 0:0:0:0:0:0:0:1 est celle que rend
            // getHostAddress() sur certaines piles : l'omettre refuserait le
            // Gateway sur une machine en IPv6.
            assertThat(PrivateNetworks.contient(ip)).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "10.0.0.1", "10.255.255.254",
                "192.168.0.1", "192.168.255.254",
                "172.16.0.1", "172.17.0.2", "172.21.0.2", "172.31.255.254"
        })
        @DisplayName("les trois plages privées de la RFC 1918")
        void plagesPrivees(String ip) {
            // 172.17 a 172.31 est ce que Docker attribue par defaut a ses
            // reseaux de ponts : les refuser rendrait tout le systeme
            // injoignable des la mise en conteneur. 172.21.0.2 est l'adresse
            // reellement observee sur le reseau restart_microservices-network.
            assertThat(PrivateNetworks.contient(ip))
                    .withFailMessage("%s est refusée alors qu'elle est privée : "
                            + "le Gateway ne peut plus joindre le service", ip)
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Refusé - l'erreur d'auth-service, trop large")
    class TropLarge {

        @ParameterizedTest(name = "{0} est PUBLIQUE")
        @ValueSource(strings = {
                "172.0.0.1", "172.15.255.254",
                "172.32.0.1", "172.217.16.1", "172.255.255.255"
        })
        @DisplayName("172.x hors de 172.16-172.31 est de l'espace public")
        void plage172Publique(String ip) {
            // `startsWith("172.")` acceptait tout cela. 172.217.16.1 appartient
            // a Google. La plage privee est 172.16.0.0/12, et le second octet
            // est la seule facon de la delimiter.
            assertThat(PrivateNetworks.contient(ip))
                    .withFailMessage("%s est acceptée : le dernier rempart contre "
                            + "l'appel direct laisse passer de l'espace public", ip)
                    .isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"203.0.113.7", "8.8.8.8", "100.64.0.1", "11.0.0.1"})
        @DisplayName("une adresse publique ordinaire est refusée")
        void adressePublique(String ip) {
            // 100.64.0.1 verifie que le point compte dans le prefixe « 10. » :
            // sans lui, toute la plage 100.x passerait.
            assertThat(PrivateNetworks.contient(ip)).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Accepté - l'erreur d'interface-service, trop étroite")
    class TropEtroite {

        @Test
        @DisplayName("aucune adresse de machine n'est codée en dur")
        void aucuneAdresseEnDur() {
            // interface-service acceptait 10.10.10.47 et rien d'autre en dehors
            // de la boucle locale. Cette adresse-la passe toujours — elle est
            // dans 10.0.0.0/8 — mais pour la bonne raison : parce qu'elle est
            // privee, et non parce qu'elle est nommee.
            assertThat(PrivateNetworks.contient("10.10.10.47")).isTrue();

            // La preuve que ce n'est plus un cas particulier : ses voisines
            // passent aussi.
            assertThat(PrivateNetworks.contient("10.10.10.48")).isTrue();
            assertThat(PrivateNetworks.contient("10.99.99.99")).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Entrées dégradées")
    class EntreesDegradees {

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = {
                "", "   ", "pas-une-adresse", "172.x.0.1", "172.", "172",
                "172.16", "172.16.0.1.2", "gateway-service"
        })
        @DisplayName("une entrée mal formée est refusée, sans lever")
        void entreeMalFormee(String ip) {
            // Integer.parseInt sur un octet non numerique leverait une
            // NumberFormatException : une entree inattendue ne doit pas produire
            // une defaillance serveur.
            //
            // Et refuser est le bon repli : ne pas savoir d'ou vient un appel
            // n'est pas une raison de l'accepter.
            assertThat(PrivateNetworks.contient(ip))
                    .withFailMessage("« %s » est acceptée", ip)
                    .isFalse();
        }

        @Test
        @DisplayName("null est refusé")
        void nulRefuse() {
            assertThat(PrivateNetworks.contient(null)).isFalse();
        }

        @Test
        @DisplayName("les espaces autour d'une adresse valide sont tolérés")
        void espacesTolerees() {
            // getHostAddress() n'en produit pas, mais une valeur venue d'une
            // configuration peut en porter.
            assertThat(PrivateNetworks.contient("  172.18.0.5  ")).isTrue();
        }
    }
}
