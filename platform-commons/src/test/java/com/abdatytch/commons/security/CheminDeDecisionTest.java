package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La normalisation du chemin de décision.
 *
 * <h2>Ce que ce test établit</h2>
 *
 * Que le chemin servant aux décisions de sécurité est bien celui que
 * {@code PathPattern} compare. Les filtres comparaient le chemin <b>brut</b> par
 * {@code startsWith}, alors que Spring Security et le routeur comparent des
 * segments <b>décodés</b> : les deux couches ne voyaient pas la même requête, et
 * un préfixe encodé franchissait la garde avant d'être servi par la couche
 * déclarative.
 *
 * <p>Chaque forme de contournement est ici un cas nommé, parce qu'un
 * contournement corrigé sans test revient.
 */
@DisplayName("CheminDeDecision - le chemin sur lequel on decide")
class CheminDeDecisionTest {

    /**
     * Construit la requête depuis une {@link java.net.URI} déjà encodée.
     *
     * <h3>Pourquoi pas {@code MockServerHttpRequest.get(String)}</h3>
     *
     * Cette surcharge <b>ré-encode</b> ce qu'on lui donne : {@code "/%73wagger-ui"}
     * y devient l'URI {@code /%2573wagger-ui}, soit un {@code %} littéral suivi
     * de {@code 73}. Le décodage d'un niveau rend alors {@code %73wagger-ui}, et
     * le test mesurerait un double encodage au lieu du cas réel.
     *
     * <p>Vérifié en instrumentant le gabarit : c'est ce piège qui a fait échouer
     * la première version de ce test, alors que la classe testée était correcte.
     */
    private static String de(String brutEncode) {
        return CheminDeDecision.de(MockServerHttpRequest
                .method(org.springframework.http.HttpMethod.GET,
                        java.net.URI.create(brutEncode))
                .build());
    }

    // ==================================================================
    @Nested
    @DisplayName("Le contournement par encodage, ferme")
    class Encodage {

        /**
         * Les formes exactes qui franchissaient la garde.
         *
         * <p>{@code /%73wagger-ui/index.html} : {@code startsWith("/swagger-ui")}
         * est faux sur le brut, mais le motif {@code /swagger-ui/**} correspond.
         * La garde ne s'appliquait donc pas, et la page était servie.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "/%73wagger-ui/index.html,   /swagger-ui/index.html",
                "/v3/api%2Ddocs,             /v3/api-docs",
                "/%77ebjars/swagger-ui/x,    /webjars/swagger-ui/x",
                "/api/v1/%64ocs/media,       /api/v1/docs/media",
                "/%61dmin/documents,         /admin/documents",
                "/api/v1/%6dedia/upload,     /api/v1/media/upload"
        })
        @DisplayName("un prefixe encode est ramene a sa forme decodee")
        void prefixeEncodeDecode(String brut, String attendu) {
            assertThat(de(brut))
                    .withFailMessage("'%s' n'est pas ramene a '%s' : un filtre qui compare "
                            + "par startsWith y sera contourne, alors que Spring Security "
                            + "et le routeur, eux, reconnaitront le chemin.", brut, attendu)
                    .isEqualTo(attendu);
        }

        /**
         * Les paramètres de segment sont retirés.
         *
         * <p>{@code /admin;x=1/documents} franchissait un
         * {@code startsWith("/admin/")} tout en étant routé vers
         * {@code /admin/documents}. Les conteneurs retirent ces paramètres avant
         * de résoudre, donc la décision doit les retirer aussi.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "/admin;x=1/documents,   /admin/documents",
                "/media;jsessionid=ab/x, /media/x"
        })
        @DisplayName("les parametres de segment sont retires")
        void parametresDeSegmentRetires(String brut, String attendu) {
            assertThat(de(brut)).isEqualTo(attendu);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les chemins ordinaires ne changent pas")
    class CheminsOrdinaires {

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "/actuator/health,           /actuator/health",
                "/actuator/health/readiness, /actuator/health/readiness",
                "/api/v1/media/upload,       /api/v1/media/upload",
                "/error,                     /error",
                "/v3/api-docs,               /v3/api-docs",
                "/swagger-ui/index.html,     /swagger-ui/index.html"
        })
        @DisplayName("un chemin deja decode est rendu tel quel")
        void inchange(String brut, String attendu) {
            assertThat(de(brut)).isEqualTo(attendu);
        }

        @Test
        @DisplayName("la racine rend '/'")
        void racine() {
            assertThat(de("/")).isEqualTo("/");
        }

        /**
         * Une barre finale est perdue, et c'est sans effet ici.
         *
         * <p>Fixé pour que personne ne s'appuie sur sa présence : les décisions
         * de ce dépôt portent sur des préfixes et des égalités de chemins sans
         * barre finale.
         */
        @Test
        @DisplayName("la barre finale n'est pas conservee")
        void barreFinale() {
            assertThat(de("/actuator/health/")).isEqualTo("/actuator/health");
        }

        /**
         * Le cas {@code //swagger-ui/x} n'est pas testable ainsi, et c'est
         * instructif.
         *
         * <p>Une URI commençant par deux barres fait lire {@code //swagger-ui}
         * comme une <b>autorité</b>, donc le chemin vaut {@code /x}. Mesuré en
         * instrumentant le gabarit. Ce n'est pas un comportement de la classe
         * testée mais de l'analyse d'URI, et une telle requête n'atteindrait pas
         * un service par la passerelle.
         *
         * <p>Noté ici plutôt que supprimé en silence : quelqu'un retentera ce
         * cas en croyant à un oubli.
         */
        @Test
        @DisplayName("une URI a double barre initiale est une autorite, pas un chemin")
        void doubleBarreEstUneAutorite() {
            assertThat(de("//swagger-ui/x")).isEqualTo("/x");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Ce que cette classe ne fait pas")
    class HorsPerimetre {

        /**
         * Les segments de traversée ne sont <b>pas</b> résolus.
         *
         * <p>C'est délibéré : {@code PathTraversalWebFilter} les refuse en
         * amont, et il est le seul détenteur de cette règle. Les résoudre ici en
         * ferait un second, et ce dépôt a déjà payé ce qu'il arrive quand deux
         * mécanismes répondent d'une même question.
         */
        @Test
        @DisplayName("les segments de traversee sont conserves, pas resolus")
        void traverseeNonResolue() {
            assertThat(de("/actuator/health/../env"))
                    .isEqualTo("/actuator/health/../env");
        }
    }
}
