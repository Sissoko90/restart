package com.abdatytch.gateway_service.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La garde de la documentation agrégée.
 *
 * <h2>Ce qu'elle protège, et pourquoi tout l'ensemble</h2>
 *
 * La passerelle est la <b>seule porte</b> de la plateforme : les services
 * n'exemptent leur documentation que du jeton, jamais de la signature HMAC. Elle
 * n'est donc lisible qu'ici — ce qui fait de ce filtre le point unique où se
 * décide qui peut lire la description de toute la surface d'API, chemins
 * d'administration compris.
 *
 * <p>Protéger la page {@code /swagger-ui} sans protéger les documents qu'elle
 * charge ne protégerait rien : {@code /api/v1/docs/media} se lit tout aussi
 * bien à la main. Les quatre surfaces sont donc vérifiées ensemble.
 */
@DisplayName("Documentation agregee - authentification HTTP dediee")
class DocumentationBasicAuthWebFilterTest {

    private static final String UTILISATEUR = "lecteur-docs";
    private static final String MOT_DE_PASSE = "mot-de-passe-de-test";

    /** Le filtre, avec ou sans identifiants configurés. */
    private DocumentationBasicAuthWebFilter filtre(String utilisateur, String motDePasse) {
        var f = new DocumentationBasicAuthWebFilter();
        ReflectionTestUtils.setField(f, "utilisateurAttendu", utilisateur);
        ReflectionTestUtils.setField(f, "motDePasseAttendu", motDePasse);
        return f;
    }

    private static String enTeteBasic(String utilisateur, String motDePasse) {
        String clair = utilisateur + ":" + motDePasse;
        return "Basic " + Base64.getEncoder()
                .encodeToString(clair.getBytes(StandardCharsets.UTF_8));
    }

    private record Resultat(HttpStatus statut, boolean chaineAppelee, String defi) {
    }

    private Resultat appeler(DocumentationBasicAuthWebFilter f, String chemin, String entete) {
        var constructeur = MockServerHttpRequest.get(chemin);
        if (entete != null) {
            constructeur = constructeur.header(HttpHeaders.AUTHORIZATION, entete);
        }
        MockServerWebExchange echange = MockServerWebExchange.from(constructeur.build());

        AtomicBoolean appelee = new AtomicBoolean(false);
        WebFilterChain chaine = e -> {
            appelee.set(true);
            return Mono.empty();
        };

        f.filter(echange, chaine).block();

        return new Resultat(
                HttpStatus.resolve(echange.getResponse().getStatusCode() == null
                        ? 200 : echange.getResponse().getStatusCode().value()),
                appelee.get(),
                echange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
    }

    // ==================================================================
    @Nested
    @DisplayName("Avec des identifiants configures")
    class AvecIdentifiants {

        private final DocumentationBasicAuthWebFilter f = filtre(UTILISATEUR, MOT_DE_PASSE);

        /**
         * Les quatre surfaces, gardées ensemble.
         *
         * <p>{@code /api/v1/docs} est la plus facile à oublier, et la plus
         * importante : c'est elle qui porte les documents <b>agrégés</b> des
         * sept services. Garder la page sans garder les documents ne garderait
         * rien.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/swagger-ui.html",
                "/swagger-ui/index.html",
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/webjars/swagger-ui/favicon.png",
                "/api/v1/docs/auth",
                "/api/v1/docs/media",
                "/api/v1/docs/security"
        })
        @DisplayName("sans en-tete, chaque surface reclame des identifiants")
        void sansEnTete(String chemin) {
            Resultat r = appeler(f, chemin, null);

            assertThat(r.chaineAppelee())
                    .withFailMessage("'%s' a traverse la chaine SANS authentification : "
                            + "la documentation de toute la plateforme est lisible.", chemin)
                    .isFalse();
            assertThat(r.statut()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        /**
         * L'en-tête {@code WWW-Authenticate} est ce qui rend la garde utilisable.
         *
         * <p>Sans lui, le navigateur n'ouvre aucune fenêtre d'identification :
         * la page reste blanche, et personne ne comprend qu'il faut
         * s'identifier. La protection serait correcte et inutilisable.
         */
        @Test
        @DisplayName("le refus porte WWW-Authenticate, sinon le navigateur ne demande rien")
        void defiPresent() {
            Resultat r = appeler(f, "/swagger-ui.html", null);

            assertThat(r.defi())
                    .withFailMessage("aucun WWW-Authenticate : le navigateur n'ouvrira "
                            + "aucune fenetre d'identification et la page restera blanche")
                    .isNotNull()
                    .startsWith("Basic realm=");
        }

        @Test
        @DisplayName("les bons identifiants ouvrent l'acces")
        void identifiantsValides() {
            Resultat r = appeler(f, "/api/v1/docs/media",
                    enTeteBasic(UTILISATEUR, MOT_DE_PASSE));

            assertThat(r.chaineAppelee()).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "Bearer un-jeton-jwt",
                "Basic pas-du-base64-!!",
                "Basic",
                "basic "
        })
        @DisplayName("un en-tete malforme est refuse, jamais accepte par defaut")
        void enTeteMalforme(String entete) {
            Resultat r = appeler(f, "/v3/api-docs", entete);

            assertThat(r.chaineAppelee()).isFalse();
            assertThat(r.statut()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("un base64 sans deux-points est refuse")
        void sansSeparateur() {
            String sansDeuxPoints = "Basic " + Base64.getEncoder()
                    .encodeToString("aucunseparateur".getBytes(StandardCharsets.UTF_8));

            assertThat(appeler(f, "/v3/api-docs", sansDeuxPoints).chaineAppelee()).isFalse();
        }

        @Test
        @DisplayName("un mot de passe faux est refuse, un utilisateur faux aussi")
        void identifiantsFaux() {
            assertThat(appeler(f, "/v3/api-docs",
                    enTeteBasic(UTILISATEUR, "mauvais")).chaineAppelee()).isFalse();
            assertThat(appeler(f, "/v3/api-docs",
                    enTeteBasic("inconnu", MOT_DE_PASSE)).chaineAppelee()).isFalse();
            assertThat(appeler(f, "/v3/api-docs",
                    enTeteBasic("inconnu", "mauvais")).chaineAppelee()).isFalse();
        }

        /**
         * Un mot de passe contenant un deux-points reste valide.
         *
         * <p>Le découpage se fait sur le <b>premier</b> deux-points : un nom
         * d'utilisateur n'en contient pas, un mot de passe peut en contenir.
         * Découper sur le dernier rejetterait des mots de passe légitimes.
         */
        @Test
        @DisplayName("un mot de passe contenant ':' est accepte")
        void motDePasseAvecDeuxPoints() {
            var g = filtre(UTILISATEUR, "a:b:c");

            assertThat(appeler(g, "/v3/api-docs",
                    enTeteBasic(UTILISATEUR, "a:b:c")).chaineAppelee()).isTrue();
        }

        /**
         * Le trafic métier n'est pas touché.
         *
         * <p>Un préfixe trop large ferait réclamer les identifiants de la
         * documentation sur toute l'API : la plateforme deviendrait
         * inutilisable, et pour tout le monde en même temps.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/auth/login",
                "/api/v1/media/upload",
                "/api/v1/users/me",
                "/actuator/health",
                "/.well-known/jwks.json",
                "/api/v1/documents"
        })
        @DisplayName("les chemins metier passent sans identifiants")
        void traficMetierIntact(String chemin) {
            assertThat(appeler(f, chemin, null).chaineAppelee())
                    .withFailMessage("'%s' reclame les identifiants de la documentation : "
                            + "le prefixe garde est trop large et l'API devient "
                            + "inutilisable.", chemin)
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Sans identifiants configures")
    class SansIdentifiants {

        /**
         * Fermé, et le service démarre quand même.
         *
         * <p>C'est le compromis de ce dépôt : jamais de refus de démarrage pour
         * une variable absente, jamais non plus d'ouverture par défaut. Une
         * documentation accessible parce que personne n'a configuré le mot de
         * passe serait le pire des deux.
         *
         * <p>Le refus est un <b>404</b> et non un 401 : il n'y a aucun
         * identifiant à proposer, donc réclamer ceux d'un navigateur ferait
         * chercher un mot de passe qui n'existe pas.
         */
        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("la documentation rend 404, et n'est jamais ouverte")
        void fermeeEtNonOuverte(String vide) {
            var f = filtre(vide, vide);
            Resultat r = appeler(f, "/api/v1/docs/media", null);

            assertThat(r.chaineAppelee())
                    .withFailMessage("la documentation est OUVERTE faute d'identifiants "
                            + "configures : c'est le pire des deux comportements")
                    .isFalse();
            assertThat(r.statut()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.defi())
                    .withFailMessage("un WWW-Authenticate est envoye alors qu'aucun "
                            + "identifiant ne peut fonctionner : cela fait chercher un "
                            + "mot de passe qui n'existe pas")
                    .isNull();
        }

        @Test
        @DisplayName("un mot de passe seul, sans utilisateur, ne suffit pas a ouvrir")
        void configurationIncomplete() {
            assertThat(appeler(filtre("", MOT_DE_PASSE), "/v3/api-docs", null).statut())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(appeler(filtre(UTILISATEUR, ""), "/v3/api-docs", null).statut())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("meme avec des identifiants fournis, l'acces reste ferme")
        void identifiantsFournisNeSuffisentPas() {
            var f = filtre("", "");

            assertThat(appeler(f, "/api/v1/docs/auth",
                    enTeteBasic(UTILISATEUR, MOT_DE_PASSE)).chaineAppelee()).isFalse();
        }

        @Test
        @DisplayName("le trafic metier reste intact, documentation fermee ou non")
        void traficMetierIntact() {
            assertThat(appeler(filtre("", ""), "/api/v1/media/upload", null).chaineAppelee())
                    .isTrue();
        }
    }
}
