package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.OAuth2UserInfo;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.OAuthProvider;
import com.abdatytch.auth_service.exception.AuthException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Les invariants que tout fournisseur OAuth2 doit tenir.
 *
 * <h2>Pourquoi des tests portant sur TOUS les fournisseurs</h2>
 *
 * Le defaut que cette migration a corrige n'etait pas une ligne fausse, c'etait
 * une regle appliquee a certains endroits et pas aux autres. Un test ecrit
 * fournisseur par fournisseur reproduirait ce probleme : le quatrieme
 * fournisseur ajoute passerait a cote.
 *
 * <p>Ces tests parcourent donc l'enumeration ou la liste des implementations.
 * Ajouter un fournisseur sans respecter la regle fait tomber la suite, sans
 * qu'on ait pense a ecrire un test pour lui.
 */
@DisplayName("Invariants communs aux fournisseurs OAuth2")
class InvariantsDesFournisseursOAuth2Test {

    // ==================================================================
    @Nested
    @DisplayName("L'evenement journalise est propre a chaque fournisseur")
    class EvenementParFournisseur {

        /**
         * Le defaut precis que la migration a ferme.
         *
         * <p>Le code journalisait
         * {@code provider == GOOGLE ? GOOGLE_LOGIN : FACEBOOK_LOGIN}, a trois
         * endroits. Tout fournisseur qui n'etait pas Google devenait donc
         * Facebook : une connexion Apple aurait ete journalisee, comptee dans
         * {@code oauth2_login{provider}} et alertee comme une connexion
         * Facebook. Rien n'aurait signale l'erreur, et la piste d'audit aurait
         * confirme la fausse lecture.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(OAuthProvider.class)
        @DisplayName("chaque fournisseur a un evenement, et aucun n'est nul")
        void chaqueFournisseurAUnEvenement(OAuthProvider fournisseur) {
            assertThat(fournisseur.getEvenementDeConnexion())
                    .withFailMessage("le fournisseur %s n'a pas d'evenement de "
                            + "connexion : sa connexion serait journalisee sous "
                            + "l'evenement d'un autre", fournisseur)
                    .isNotNull();
        }

        @Test
        @DisplayName("deux fournisseurs ne partagent pas le meme evenement")
        void aucunEvenementPartage() {
            List<EventType> evenements = Arrays.stream(OAuthProvider.values())
                    .filter(f -> f != OAuthProvider.LOCAL)
                    .map(OAuthProvider::getEvenementDeConnexion)
                    .toList();
            Set<EventType> distincts = new HashSet<>(evenements);

            assertThat(distincts)
                    .withFailMessage("""
                            Deux fournisseurs partagent le meme evenement de \
                            connexion.

                            C'etait le defaut d'origine : le ternaire \
                            « GOOGLE ? GOOGLE_LOGIN : FACEBOOK_LOGIN » attribuait \
                            a Facebook tout fournisseur qui n'etait pas Google. \
                            La piste d'audit designait alors le mauvais \
                            fournisseur, sans aucune erreur visible.""")
                    .hasSameSizeAs(evenements);
        }

        @Test
        @DisplayName("Apple a bien son propre evenement")
        void appleADistinctEvenement() {
            assertThat(OAuthProvider.APPLE.getEvenementDeConnexion())
                    .isEqualTo(EventType.APPLE_LOGIN);
            assertThat(OAuthProvider.APPLE.getEvenementDeConnexion())
                    .withFailMessage("une connexion Apple serait journalisee "
                            + "comme une connexion Facebook")
                    .isNotEqualTo(EventType.FACEBOOK_LOGIN);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'URL d'autorisation porte toujours le state")
    class StateDansLUrl {

        /**
         * Le {@code state} manquait dans les deux URL d'origine.
         *
         * <p>{@code AppConstants.OAUTH2_STATE_PREFIX} etait defini et
         * {@code OAuth2CallbackRequest.state} portait le commentaire « Pour la
         * validation CSRF » : la protection etait ecrite, et aucune des deux URL
         * ne contenait le parametre. Sans lui, un tiers peut faire aboutir
         * <b>son</b> parcours dans le navigateur de la victime — soit elle se
         * retrouve connectee au compte de l'attaquant, soit le compte social de
         * l'attaquant se lie au sien, ce qui lui en donne l'acces durable.
         *
         * <p>Le test parcourt les implementations : un fournisseur ajoute sans
         * {@code state} fait tomber la suite.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.abdatytch.auth_service.service.oauth2."
                + "InvariantsDesFournisseursOAuth2Test#fournisseursConfigures")
        @DisplayName("le state figure dans l'URL, et telle quelle")
        void statePresentDansLUrl(String nom, IFournisseurOAuth2 fournisseur) {
            String etat = "etat-aleatoire-de-test-123";

            String url = fournisseur.urlDAutorisation(etat);

            assertThat(url)
                    .withFailMessage("""
                            L'URL d'autorisation de %s ne contient pas le \
                            parametre state.

                            Sans lui, rien ne distingue un rappel que nous avons \
                            initie d'un rappel provoque par un tiers : c'est le \
                            login CSRF, et c'est la raison d'etre du parametre.

                            URL produite : %s""", nom, url)
                    .contains("state=" + etat);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.abdatytch.auth_service.service.oauth2."
                + "InvariantsDesFournisseursOAuth2Test#fournisseursConfigures")
        @DisplayName("les valeurs sont encodees pour URL")
        void valeursEncodees(String nom, IFournisseurOAuth2 fournisseur) {
            // Un state en base64url ne contient pas de caractere a encoder,
            // mais un identifiant ou une URI de rappel peut en contenir. Sans
            // encodage, un « & » dans une valeur ajoute un parametre a la
            // requete, ce qui est une injection de parametre.
            String url = fournisseur.urlDAutorisation("a+b/c=d&e");

            assertThat(url)
                    .withFailMessage("le state n'est pas encode : un caractere "
                            + "reserve y ajouterait un parametre dans l'URL "
                            + "envoyee a %s", nom)
                    .doesNotContain("state=a+b/c=d&e");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.abdatytch.auth_service.service.oauth2."
                + "InvariantsDesFournisseursOAuth2Test#fournisseursConfigures")
        @DisplayName("l'URL part sur le point d'entree du fournisseur, en HTTPS")
        void urlEnHttps(String nom, IFournisseurOAuth2 fournisseur) {
            assertThat(fournisseur.urlDAutorisation("x"))
                    .withFailMessage("l'URL d'autorisation de %s n'est pas en "
                            + "HTTPS : le code d'autorisation circulerait en "
                            + "clair", nom)
                    .startsWith("https://");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un fournisseur non configure ne fait pas tomber le demarrage")
    class NonConfigureNEstPasUnePanne {

        /**
         * La consigne tenue ici, mot pour mot : « fais pas un refus de
         * demarrage sans ces valeurs dans env ».
         *
         * <p>Un fournisseur social absent est un etat normal — on n'active pas
         * Apple le premier jour. Faire echouer le demarrage pour une variable
         * Apple vide rendrait indisponibles les connexions par telephone, par
         * courriel et par mot de passe, pour une cause sans aucun rapport.
         */
        @Test
        @DisplayName("Apple sans configuration se construit et se declare inactif")
        void appleSansConfiguration() {
            FournisseurApple apple = AppleDEssai.nonConfigure();

            assertThat(apple.estConfigure())
                    .withFailMessage("Apple se declare configure sans secret : "
                            + "un parcours serait engage puis echouerait chez Apple")
                    .isFalse();
            assertThat(apple.fournisseur()).isEqualTo(OAuthProvider.APPLE);
        }

        @Test
        @DisplayName("un parcours Apple non configure est refuse, avec une erreur nommee")
        void parcoursAppleRefuse() {
            FournisseurApple apple = AppleDEssai.nonConfigure();

            assertThatThrownBy(() -> apple.urlDAutorisation("etat"))
                    .isInstanceOf(AuthException.class)
                    .extracting(e -> ((AuthException) e).getErrorCode())
                    .isEqualTo(ErrorCode.OAUTH2_PROVIDER_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("l'echange de code rend une ERREUR et non un Mono vide")
        void echangeRendUneErreur() {
            // Le piege du Mono vide, corrige une dizaine de fois dans ce depot :
            // une chaine qui se termine vide laisse la connexion ni aboutie ni
            // refusee, et le client attend son propre delai d'expiration.
            Mono<OAuth2UserInfo> echange = AppleDEssai.nonConfigure().echangerLeCode("code");

            assertThatThrownBy(echange::block)
                    .isInstanceOf(AuthException.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le registre")
    class Registre {

        @Test
        @DisplayName("un fournisseur non configure est refuse comme un fournisseur absent")
        void nonConfigureEtAbsentSontIndiscernables() {
            // Les distinguer renseignerait un appelant anonyme sur l'etat de
            // notre configuration : « Apple existe mais n'est pas configure »
            // est une information que nous n'avons aucune raison de donner.
            var registre = new RegistreDesFournisseursOAuth2(
                    List.of(AppleDEssai.nonConfigure()));

            assertThatThrownBy(() -> registre.pour(OAuthProvider.APPLE))
                    .isInstanceOf(AuthException.class)
                    .extracting(e -> ((AuthException) e).getErrorCode())
                    .isEqualTo(ErrorCode.OAUTH2_PROVIDER_NOT_CONFIGURED);

            assertThatThrownBy(() -> registre.pour(OAuthProvider.GOOGLE))
                    .isInstanceOf(AuthException.class)
                    .extracting(e -> ((AuthException) e).getErrorCode())
                    .withFailMessage("un fournisseur absent et un fournisseur non "
                            + "configure rendent des codes differents, ce qui "
                            + "renseigne sur notre configuration")
                    .isEqualTo(ErrorCode.OAUTH2_PROVIDER_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("deux implementations pour un meme fournisseur font echouer le demarrage")
        void deuxImplementationsRefusees() {
            // Sans ce controle, l'une masquerait l'autre selon l'ordre de scan
            // des beans : le comportement changerait sans qu'aucun code change,
            // ce qui est la pire forme de dependance invisible.
            assertThatThrownBy(() -> new RegistreDesFournisseursOAuth2(
                    List.of(AppleDEssai.nonConfigure(), AppleDEssai.nonConfigure())))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("seuls les fournisseurs utilisables sont annonces")
        void fournisseursUtilisables() {
            var registre = new RegistreDesFournisseursOAuth2(
                    List.of(AppleDEssai.nonConfigure()));

            assertThat(registre.fournisseursUtilisables()).isEmpty();
        }
    }

    /** Les implementations reelles, avec une configuration d'essai complete. */
    static Stream<org.junit.jupiter.params.provider.Arguments> fournisseursConfigures() {
        List<org.junit.jupiter.params.provider.Arguments> cas = new ArrayList<>();
        cas.add(org.junit.jupiter.params.provider.Arguments.of("google",
                new FournisseurGoogle(null, "id-google", "secret", "https://exemple/rappel")));
        cas.add(org.junit.jupiter.params.provider.Arguments.of("facebook",
                new FournisseurFacebook(null, "id-fb", "secret", "https://exemple/rappel")));
        cas.add(org.junit.jupiter.params.provider.Arguments.of("apple",
                AppleDEssai.configure()));
        return cas.stream();
    }
}
