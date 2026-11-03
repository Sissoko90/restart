package com.abdatytch.gateway_service.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La pièce dont dépend chaque signature HMAC de la plateforme.
 *
 * <p>Le Gateway signe {@code timestamp|chemin|méthode|requestId}, et le service
 * en aval recalcule la signature sur le chemin qu'il a reçu. Une erreur ici
 * refuse <b>toutes</b> les requêtes du préfixe concerné, en 403, avec un
 * message qui parle de signature invalide et non de chemin : le défaut serait
 * cherché du côté des clés.
 */
@DisplayName("CheminCible — le chemin que le service recevra")
class CheminCibleTest {

    @Nested
    @DisplayName("Retrait du préfixe public")
    class RetraitDuPrefixe {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "/api/v1/users/42, /users/42",
                "/api/v1/media/upload, /media/upload",
                "/api/v1/auth/email/auth-request, /auth/email/auth-request",
                "/api/v1/admin/document-types, /admin/document-types",
                "/api/v1/sms/accuses/afriksms, /sms/accuses/afriksms",
        })
        void prefixeRetire(String entrant, String attendu) {
            assertThat(CheminCible.pour(entrant)).isEqualTo(attendu);
        }

        @Test
        @DisplayName("un chemin sans le préfixe est rendu tel quel")
        void sansPrefixe() {
            assertThat(CheminCible.pour("/actuator/health")).isEqualTo("/actuator/health");
        }

        /**
         * Le JWKS standard est ramené sur le chemin réellement servi.
         *
         * <h3>Le défaut que cette réécriture corrige</h3>
         *
         * {@code /.well-known/jwks.json} est le chemin de découverte standard,
         * celui qu'un client interroge pour vérifier la signature d'un jeton.
         * La route de la passerelle le proxifiait vers <b>auth-service</b>, qui
         * ne sert aucun chemin {@code /.well-known} : l'autorité JWT de cette
         * plateforme est interface-service, seul détenteur des clés privées.
         *
         * <p>Le chemin réel y est {@code /dev/.well-known/jwks.json}, parce que
         * {@code DevKeyManagementController} porte
         * {@code @RequestMapping("/dev")}.
         *
         * <p>Rien ne signalait le défaut : une route qui existe et ne mène
         * nulle part est indistinguable d'un chemin inexistant. Et le chemin
         * explicite {@code /dev/.well-known/jwks.json} fonctionnait par une
         * route YAML, donc les services ne s'en plaignaient pas.
         *
         * <p>Cette assertion garde la correction. Le routeur et le filtre de
         * signature lisent tous deux {@code CheminCible}, donc ils ne peuvent
         * pas diverger — mais rien n'empêcherait de retirer la réécriture et de
         * rendre la route muette à nouveau.
         */
        @Test
        @DisplayName("le JWKS standard mene au chemin reellement servi par l'autorite JWT")
        void jwksRameneSurLeCheminReel() {
            assertThat(CheminCible.pour("/.well-known/jwks.json"))
                    .withFailMessage("le chemin standard du JWKS n'est plus reecrit : la "
                            + "route mene a un chemin que personne ne sert, et l'erreur "
                            + "est indistinguable d'un chemin inexistant")
                    .isEqualTo("/dev/.well-known/jwks.json");

            // Ce chemin n'est volontairement PAS dans SERVICES_PAR_PREFIXE :
            // couper la decouverte des cles en mode maintenance rendrait
            // invérifiables les jetons deja emis. D'ou l'assertion tolerante.
            assertThat(CheminCible.serviceCible("/.well-known/jwks.json"))
                    .withFailMessage("le JWKS est attribue a un service qui n'est pas "
                            + "l'autorite JWT")
                    .isIn("interface-service", null);
        }

        @Test
        @DisplayName("le préfixe n'est retiré qu'en tête, et qu'une fois")
        void retireUneSeuleFois() {
            // `replaceFirst` sur une expression reguliere aurait le meme effet
            // ici, mais un chemin qui contient deux fois le prefixe ne doit pas
            // perdre le second : il appartient au chemin du service.
            assertThat(CheminCible.pour("/api/v1/users/api/v1/x"))
                    .isEqualTo("/users/api/v1/x");
        }

        @Test
        @DisplayName("null et vide ne lèvent pas")
        void degenere() {
            assertThat(CheminCible.pour(null)).isEmpty();
            assertThat(CheminCible.pour("")).isEmpty();
            assertThat(CheminCible.pour("/api/v1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Réécritures vers auth-service")
    class ReecrituresAuthService {

        /**
         * La collision qui rend ces réécritures nécessaires.
         *
         * <p>auth-service et interface-service exposent tous les deux
         * {@code /admin/**} et {@code /auth/**}. Sans préfixes publics
         * distincts, les endpoints d'auth-service sont injoignables : c'était
         * le cas, et les routes YAML censées les servir arrivaient sans
         * réécriture, donc sur des chemins qu'aucun contrôleur ne déclare.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "/api/v1/auth-admin/accounts/42/unlock, /admin/accounts/42/unlock",
                "/api/v1/auth-admin/audit/logs, /admin/audit/logs",
                "/api/v1/auth-admin/sms/solde, /admin/sms/solde",
                "/api/v1/auth-admin/sms/url-de-rappel, /admin/sms/url-de-rappel",
                "/api/v1/auth-sdk/admin/login, /auth/admin/login",
                "/api/v1/auth-sdk/biometric/register, /auth/biometric/register",
                "/api/v1/auth-sdk/erasure/me, /auth/erasure/me",
        })
        void reecrites(String entrant, String attendu) {
            assertThat(CheminCible.pour(entrant)).isEqualTo(attendu);
        }

        @ParameterizedTest(name = "{0} inchange")
        @ValueSource(strings = {
                "/api/v1/auth-users/42/roles",
                "/api/v1/sms/accuses/afriksms",
        })
        @DisplayName("les chemins qu'auth-service expose déjà ne sont pas réécrits")
        void nonReecrits(String entrant) {
            assertThat(CheminCible.pour(entrant))
                    .isEqualTo(entrant.substring("/api/v1".length()));
        }

        @Test
        @DisplayName("le préfixe sans suite est réécrit aussi")
        void prefixeNu() {
            assertThat(CheminCible.pour("/api/v1/auth-admin")).isEqualTo("/admin");
            assertThat(CheminCible.pour("/api/v1/auth-sdk")).isEqualTo("/auth");
        }

        /**
         * Le défaut que {@code estLeSegment} empêche.
         *
         * <p>Un {@code startsWith} nu ferait correspondre {@code /auth-admin} à
         * {@code /auth-administration}, qui partirait chez auth-service sous
         * {@code /administration} — un chemin que personne n'a déclaré, et
         * qu'aucun test n'aurait couvert. Ce dépôt a déjà corrigé trois fois
         * cette famille de défaut dans {@code GatewayAccessWebFilter} : un
         * contrôle de chemin qui désigne plus large que son intention.
         */
        @ParameterizedTest(name = "{0} n''est PAS reecrit")
        @ValueSource(strings = {
                "/api/v1/auth-administration/x",
                "/api/v1/auth-adminx",
                "/api/v1/auth-sdkv2/x",
                "/api/v1/auth-sdk-legacy/x",
        })
        void laReecritureNeDepasseePasLeSegment(String entrant) {
            assertThat(CheminCible.pour(entrant))
                    .isEqualTo(entrant.substring("/api/v1".length()));
        }

        @Test
        @DisplayName("interface-service garde ses chemins historiques")
        void interfaceServiceInchange() {
            // Son contrat d'URL ne doit pas bouger : des clients l'utilisent.
            assertThat(CheminCible.pour("/api/v1/auth/login")).isEqualTo("/auth/login");
            assertThat(CheminCible.pour("/api/v1/admin/login")).isEqualTo("/admin/login");
        }

        @Test
        @DisplayName("une réécriture ne s'applique qu'une fois")
        void pasDeReecritureEnCascade() {
            // /auth-sdk -> /auth ne doit pas ensuite voir /auth et rien faire de
            // plus, ni une autre regle s'appliquer au resultat.
            assertThat(CheminCible.pour("/api/v1/auth-sdk/auth-admin/x"))
                    .isEqualTo("/auth/auth-admin/x");
        }
    }
}
