package com.abdatytch.interface_service.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Secret partagé protégeant la console d'administration.
 *
 * <h2>Le défaut corrigé : le lien de confirmation était inatteignable</h2>
 *
 * Ce filtre exigeait {@code X-Admin-Secret} sur <b>tout</b> {@code /admin/**},
 * y compris {@code /admin/confirm} — l'endpoint « C'est moi / Ce n'est pas
 * moi », que l'administrateur atteint en cliquant un lien reçu par courriel.
 *
 * <p>Un navigateur suivant un lien ne peut pas ajouter d'en-tête. Le filtre
 * exigeait donc quelque chose que l'appelant légitime est <b>incapable</b>
 * d'envoyer : le lien répondait 401, et le compte administrateur restait bloqué
 * sans recours.
 *
 * <p>{@code SecurityConfig} exemptait bien ce chemin, avec le commentaire
 * « jeton à usage unique reçu par courriel » : l'intention était explicite. Mais
 * ce filtre est un bean {@code WebFilter}, donc il s'exécute dans la chaîne HTTP
 * <b>globale</b>, en amont de Spring Security. <b>L'exemption était posée une
 * couche trop bas.</b>
 *
 * <p>C'est la deuxième fois dans cet audit qu'une garde rend l'administration
 * inatteignable — après {@code JwtAuthenticationWebFilter} d'auth-service, qui
 * exigeait {@code type == "developer"} sur des jetons émis en
 * {@code type == "user"}.
 */
@DisplayName("AdminSecretFilter - secret partagé de la console d'administration")
class AdminSecretFilterTest {

    private static final String SECRET = "secret-admin-de-test";

    private AdminSecretFilter filtre;

    @BeforeEach
    void setUp() {
        filtre = new AdminSecretFilter();
        ReflectionTestUtils.setField(filtre, "adminSecretKey", SECRET);
    }

    /** Traverse le filtre et rend {@code true} si la suite a été atteinte. */
    private boolean passe(String chemin, String secretFourni) {
        MockServerHttpRequest.BaseBuilder<?> requete = MockServerHttpRequest.get(chemin);
        if (secretFourni != null) {
            requete.header("X-Admin-Secret", secretFourni);
        }
        MockServerWebExchange echange = MockServerWebExchange.from(
                (MockServerHttpRequest) requete.build());

        AtomicBoolean atteinte = new AtomicBoolean(false);
        filtre.filter(echange, suite -> {
            atteinte.set(true);
            return Mono.empty();
        }).block();

        if (!atteinte.get()) {
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("requête bloquée sans statut de refus")
                    .isNotNull();
        }
        return atteinte.get();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : le lien reçu par courriel")
    class LienParCourriel {

        @Test
        @DisplayName("/admin/confirm passe SANS en-tête - un navigateur n'en pose pas")
        void confirmationSansEnTete() {
            // CE TEST A TROUVE LE DEFAUT.
            //
            // L'administrateur clique un lien dans son courriel. Le navigateur
            // ne peut pas ajouter X-Admin-Secret. Exiger cet en-tete ici rendait
            // le deblocage du compte impossible — et c'est precisement
            // l'endpoint qui sert a debloquer un compte.
            assertThat(passe("/admin/confirm", null))
                    .withFailMessage("le lien de confirmation reçu par courriel est "
                            + "refusé : le compte administrateur reste bloqué sans "
                            + "recours")
                    .isTrue();
        }

        @Test
        @DisplayName("l'exemption est une ÉGALITÉ, pas un préfixe")
        void exemptionParEgalite() {
            // Un startsWith ouvrirait tout le sous-arbre. Une exemption doit
            // designer exactement ce qu'elle exempte — c'est la lecon des
            // `contains` corriges dans les filtres d'acces Gateway.
            assertThat(passe("/admin/confirm/autre-chose", null))
                    .withFailMessage("l'exemption s'étend à un sous-chemin")
                    .isFalse();
            assertThat(passe("/admin/confirmer-tout", null)).isFalse();
        }

        @Test
        @DisplayName("/admin/confirm reste protégé par son propre jeton")
        void confirmationPasOuverte() {
            // Ce test ne verifie rien techniquement ici : il porte la raison
            // pour laquelle l'exemption est acceptable.
            //
            // /admin/confirm n'est pas ouvert : il exige un jeton a usage unique,
            // qui expire et qui est invalide des qu'il sert. Ces deux proprietes
            // sont verifiees par AdminConfirmationTest — jetonExpire et
            // jetonAUsageUnique. Sans elles, cette exemption serait un trou.
            assertThat(passe("/admin/confirm", null)).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les autres chemins d'administration restent gardés")
    class CheminsGardes {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/admin/login", "/admin/info", "/admin/totp/enroll",
                "/admin/users", "/admin/projects/42"
        })
        @DisplayName("un chemin admin sans en-tête est refusé")
        void sansEnTete(String chemin) {
            assertThat(passe(chemin, null))
                    .withFailMessage("%s est accessible sans secret", chemin)
                    .isFalse();
        }

        @Test
        @DisplayName("un secret invalide est refusé")
        void secretInvalide() {
            assertThat(passe("/admin/info", "mauvais-secret")).isFalse();
        }

        @Test
        @DisplayName("un secret valide passe")
        void secretValide() {
            assertThat(passe("/admin/info", SECRET)).isTrue();
        }

        @Test
        @DisplayName("un secret vide est refusé, pas traité comme absent de contrôle")
        void secretVide() {
            assertThat(passe("/admin/info", "")).isFalse();
        }

        @Test
        @DisplayName("/admin exactement est gardé - pas seulement ses descendants")
        void adminSansSlash() {
            // `startsWith("/admin/")` laissait ce chemin non garde. Aucun
            // endpoint n'y repond aujourd'hui, mais un @GetMapping("") ajoute
            // demain a AdminController serait publie sans controle, et rien ne
            // le signalerait.
            assertThat(passe("/admin", null))
                    .withFailMessage("/admin exactement n'est pas gardé : un "
                            + "endpoint ajouté à cette racine serait public")
                    .isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Chemins hors administration")
    class HorsAdministration {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/auth/login", "/actuator/health", "/maintenance/status",
                "/dev/.well-known/jwks.json", "/administration/x"
        })
        @DisplayName("un chemin non admin n'est pas concerné")
        void horsPerimetre(String chemin) {
            // `/administration/x` verifie que la garde ne deborde pas : elle
            // porte sur `/admin` et `/admin/`, pas sur tout ce qui commence par
            // ces lettres.
            assertThat(passe(chemin, null))
                    .withFailMessage("%s est soumis au secret admin", chemin)
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Secret non configuré")
    class SecretNonConfigure {

        @Test
        @DisplayName("sans secret configuré, les endpoints admin sont FERMÉS")
        void secretAbsentFerme() {
            // Choix correct, et qu'il faut figer : la propriete a une valeur par
            // defaut vide (`${admin.secret.key:}`). Ouvrir faute de
            // configuration publierait toute la console d'administration au
            // premier oubli de deploiement.
            ReflectionTestUtils.setField(filtre, "adminSecretKey", "");

            assertThat(passe("/admin/info", "n-importe-quoi"))
                    .withFailMessage("sans secret configuré, l'administration est "
                            + "OUVERTE : un oubli de déploiement la publie")
                    .isFalse();
        }

        @Test
        @DisplayName("sans secret configuré, le lien par courriel reste atteignable")
        void courrielResteAtteignable() {
            // L'exemption precede la verification de configuration : le
            // deblocage d'un compte ne doit pas dependre de la presence du
            // secret admin, puisqu'il ne l'utilise pas.
            ReflectionTestUtils.setField(filtre, "adminSecretKey", "");

            assertThat(passe("/admin/confirm", null)).isTrue();
        }
    }
}
