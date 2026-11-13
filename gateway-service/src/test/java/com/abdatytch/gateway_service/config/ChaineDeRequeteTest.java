package com.abdatytch.gateway_service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La chaine de requete retransmise au service aval.
 *
 * <h2>Les deux defauts pinces</h2>
 *
 * La passerelle recollait la chaine DECODEE dans l'URL cible. Mesures du
 * 2026-11-13, a travers la passerelle reelle :
 *
 * <pre>
 * envoye   : ?q=Bazin%26injecte=1        UN parametre
 * transmis : ?q=Bazin&amp;injecte=1          DEUX parametres
 *
 * envoye   : ?q=50%25                    « 50% »
 * transmis : ?q=50%                      URI.create leve, 503
 * </pre>
 *
 * <p>Le premier est une injection : tout parametre que le service lit et que
 * l'appelant ne devrait pas gouverner devenait atteignable. Le second rendait
 * impossible toute recherche portant un pour-cent.
 */
@DisplayName("La chaine de requete retransmise")
class ChaineDeRequeteTest {

    // ==================================================================
    @Nested
    @DisplayName("L'encodage est PRESERVE")
    class EncodagePreserve {

        /**
         * LE DEFAUT PRINCIPAL : la scission de parametre.
         *
         * <p>Un ET encode appartient a la VALEUR. Le decoder avant de
         * retransmettre en fait un separateur, et le service aval lit un
         * parametre que personne ne lui a envoye.
         */
        @Test
        @DisplayName("un ET encode reste dans la valeur, il ne devient pas un separateur")
        void etEncodeNeScindePas() {
            assertThat(GatewayRoutesConfig.chaineDeRequete(
                    URI.create("http://h/x?q=Bazin%26injecte=1")))
                    .withFailMessage("""
                            Un ET encode est retransmis decode.

                            Le service aval lira DEUX parametres la ou l'appelant en \
                            a envoye un : tout reglage que le service lit devient \
                            atteignable depuis l'exterieur.""")
                    .isEqualTo("?q=Bazin%26injecte=1")
                    .doesNotContain("&");
        }

        @Test
        @DisplayName("un EGAL encode non plus")
        void egalEncode() {
            assertThat(GatewayRoutesConfig.chaineDeRequete(
                    URI.create("http://h/x?q=a%3Db")))
                    .isEqualTo("?q=a%3Db");
        }

        /**
         * Le second defaut : l'URL malformee.
         *
         * <p>Decoder {@code %25} donne un {@code %} seul, et {@code URI.create}
         * leve « Malformed escape pair ». La requete sortait en 503.
         */
        @Test
        @DisplayName("un pour-cent encode reste encode, et l'URL reste construisible")
        void pourcentEncode() {
            String chaine = GatewayRoutesConfig.chaineDeRequete(
                    URI.create("http://h/x?q=50%25"));
            assertThat(chaine).isEqualTo("?q=50%25");

            // Ce que la passerelle fait ensuite : recoller et construire.
            assertThat(URI.create("http://aval:8092/market/produits" + chaine))
                    .withFailMessage("""
                            L'URL cible n'est pas construisible.

                            C'est exactement ce qui sortait en 503 : URI.create levait \
                            « Malformed escape pair » sur un pour-cent decode.""")
                    .hasToString("http://aval:8092/market/produits?q=50%25");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "http://h/x?q=a%2Fb,        ?q=a%2Fb",
                "http://h/x?q=T%5Fshirt,    ?q=T%5Fshirt",
                "http://h/x?q=TVA%2018%25,  ?q=TVA%2018%25",
                "http://h/x?a=1&b=2,        ?a=1&b=2"})
        @DisplayName("les autres encodages traversent inchanges")
        void encodagesPreserves(String uri, String attendu) {
            assertThat(GatewayRoutesConfig.chaineDeRequete(URI.create(uri)))
                    .isEqualTo(attendu);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les cas limites")
    class CasLimites {

        @Test
        @DisplayName("sans chaine de requete : rien, et surtout pas un point d'interrogation")
        void sansRequete() {
            assertThat(GatewayRoutesConfig.chaineDeRequete(URI.create("http://h/x")))
                    .withFailMessage("""
                            Un « ? » est ajoute a une URL qui n'a pas de parametre.

                            Le service aval verrait un chemin different de celui que \
                            la passerelle a signe, et refuserait tout en 403 avec un \
                            message parlant de signature.""")
                    .isEmpty();
        }

        @Test
        @DisplayName("une chaine VIDE ne produit pas non plus de point d'interrogation")
        void requeteVide() {
            assertThat(GatewayRoutesConfig.chaineDeRequete(URI.create("http://h/x?")))
                    .isEmpty();
        }

        @Test
        @DisplayName("une URI nulle ne fait pas lever")
        void uriNulle() {
            assertThat(GatewayRoutesConfig.chaineDeRequete(null)).isEmpty();
        }
    }
}
