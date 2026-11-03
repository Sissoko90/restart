package com.abdatytch.auth_service.security;

import com.abdatytch.auth_service.service.IJwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pose de l'identité dans auth-service.
 *
 * <h2>Le défaut que ces tests ont trouvé</h2>
 *
 * Le filtre exigeait {@code type == "developer"}. Or {@code generateToken}
 * délègue la signature à interface-service, dont {@code generateTokenForUser}
 * pose {@code type: "user"} : <b>aucun jeton délivré par auth-service
 * n'authentifiait dans auth-service</b>.
 *
 * <p>Conséquence mesurable : un administrateur obtenait un jeton valide par
 * {@code POST /auth/admin/login}, puis un <b>401</b> sur chaque appel à
 * {@code /admin/**}, {@code /auth-users/**} et {@code /auth/erasure/**} — soit
 * la totalité de la surface d'administration.
 *
 * <p>Rien ne pouvait le signaler : ce filtre n'avait aucun test, et il
 * n'échoue pas bruyamment. Il laisse simplement passer sans identité, et c'est
 * {@code SecurityConfig} qui refuse ensuite — un 401 attribuable au jeton, à la
 * configuration, à l'horloge, à tout sauf à sa cause réelle.
 *
 * <h2>Ce que les tests verrouillent en plus</h2>
 *
 * <ul>
 *   <li>les deux autorités d'un SUPER_ADMIN survivent ({@code get(0)} n'en
 *       gardait qu'une) ;</li>
 *   <li>un jeton de rafraîchissement n'authentifie pas ;</li>
 *   <li>un administrateur du <i>portail développeur</i> n'obtient pas les droits
 *       d'administrateur de la <i>plateforme</i> par homonymie de rôle.</li>
 * </ul>
 */
@DisplayName("JwtAuthenticationWebFilter - pose de l'identité")
class JwtAuthenticationWebFilterTest {

    private static final String JETON = "jeton-opaque";
    private static final String UTILISATEUR = "11111111-1111-1111-1111-111111111111";

    private IJwtService jwtService;
    private JwtAuthenticationWebFilter filtre;

    @BeforeEach
    void setUp() {
        jwtService = Mockito.mock(IJwtService.class);
        when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
        when(jwtService.getUserIdFromToken(anyString())).thenReturn(UTILISATEUR);
        filtre = new JwtAuthenticationWebFilter(jwtService);
    }

    /** Pose les claims que le filtre lira. */
    private void claims(Object type, Object roles) {
        Map<String, Object> claims = new HashMap<>();
        if (type != null) {
            claims.put("type", type);
        }
        if (roles != null) {
            claims.put("roles", roles);
        }
        when(jwtService.extractAllClaims(anyString())).thenReturn(claims);
    }

    private void claimsBruts(Map<String, Object> claims) {
        when(jwtService.extractAllClaims(anyString())).thenReturn(claims);
    }

    /**
     * Fait traverser le filtre et rend l'identité réellement posée dans le
     * contexte réactif, ou {@code null} si la requête a passé sans identité.
     *
     * <p>L'identité est lue depuis le contexte, pas depuis un mouchard sur le
     * filtre : c'est le seul endroit où {@code SecurityConfig} la cherchera.
     */
    private Authentication identitePosee() {
        MockServerWebExchange echange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/accounts")
                        .header("Authorization", "Bearer " + JETON)
                        .build());

        AtomicReference<Authentication> vue = new AtomicReference<>();
        AtomicBoolean atteinte = new AtomicBoolean(false);

        filtre.filter(echange, suite -> {
            atteinte.set(true);
            return ReactiveSecurityContextHolder.getContext()
                    .map(contexte -> {
                        vue.set(contexte.getAuthentication());
                        return contexte;
                    })
                    .then();
        }).block();

        assertThat(atteinte.get())
                .withFailMessage("la requête est perdue : ce filtre ne refuse jamais, "
                        + "il doit toujours transmettre la suite de la chaîne")
                .isTrue();

        return vue.get();
    }

    private List<String> autorites() {
        Authentication identite = identitePosee();
        if (identite == null) {
            return List.of();
        }
        return identite.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé - les jetons de la plateforme")
    class DefautCorrige {

        @Test
        @DisplayName("un jeton type=user AUTHENTIFIE - c'est celui qu'émet auth-service")
        void jetonUtilisateurAuthentifie() {
            // CE TEST A TROUVE LE DEFAUT.
            //
            // Le filtre exigeait type == "developer". Comme generateToken passe
            // par interface-service, qui pose type: "user", cette assertion
            // echouait : identitePosee() rendait null, et toute
            // l'administration repondait 401.
            claims("user", List.of("ROLE_ADMIN"));

            assertThat(identitePosee())
                    .withFailMessage("un jeton émis par auth-service lui-même "
                            + "n'authentifie pas : toute l'administration répond 401")
                    .isNotNull();
        }

        @Test
        @DisplayName("le sujet posé est l'identifiant de l'utilisateur")
        void sujetPose() {
            // Le sujet sert aux contrôles de propriété (IDOR) en aval : poser le
            // mauvais les ferait tous porter sur la mauvaise personne.
            claims("user", List.of("ROLE_ADMIN"));

            assertThat(identitePosee().getName()).isEqualTo(UTILISATEUR);
        }

        @Test
        @DisplayName("les DEUX autorités d'un SUPER_ADMIN survivent")
        void hierarchieConservee() {
            // UserRole.expand() resout la hierarchie a l'emission : le jeton
            // d'un SUPER_ADMIN porte [ROLE_SUPER_ADMIN, ROLE_ADMIN].
            //
            // Avec rolesList.get(0), seule la premiere etait retenue. Un
            // SUPER_ADMIN se voyait donc refuser /admin/** — que son subordonne
            // obtenait. L'inversion est le genre d'incoherence qu'on attribue a
            // la configuration des regles plutot qu'a la lecture du jeton.
            claims("user", List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));

            assertThat(autorites())
                    .withFailMessage("une autorité est perdue : un SUPER_ADMIN "
                            + "n'atteint pas /admin/**")
                    .containsExactly("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        }

        @Test
        @DisplayName("un rôle sans préfixe reçoit ROLE_, sinon hasRole ne matche jamais")
        void prefixePose() {
            // hasRole("ADMIN") exige l'autorite ROLE_ADMIN : il prefixe
            // lui-meme. Transmettre "ADMIN" brut ne satisfait aucune regle.
            claims("user", List.of("ADMIN"));

            assertThat(autorites()).containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("le préfixe n'est jamais doublé")
        void prefixeNonDouble() {
            // ROLE_ROLE_ADMIN ne satisferait rien non plus : la correction du
            // defaut precedent ne doit pas en creer un symetrique.
            claims("user", List.of("ROLE_ADMIN"));

            assertThat(autorites()).containsExactly("ROLE_ADMIN");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Types de jetons non recevables comme jeton d'accès")
    class TypesRefuses {

        @ParameterizedTest(name = "type={0}")
        @ValueSource(strings = {"refresh", "refresh_user", "temp_2fa", "maintenance"})
        @DisplayName("un jeton qui n'est pas un jeton d'accès n'authentifie pas")
        void typeNonRecevable(String type) {
            // Un jeton de rafraichissement porte la MEME signature et la meme
            // audience qu'un jeton d'acces : seul son claim type les distingue.
            // L'accepter accorderait une session de la duree du
            // rafraichissement — des jours au lieu de minutes.
            //
            // temp_2fa est plus grave encore : il n'atteste que du premier
            // facteur. L'accepter rendrait le second facteur contournable.
            claims(type, List.of("ROLE_ADMIN"));

            assertThat(identitePosee())
                    .withFailMessage("un jeton de type %s authentifie : la durée de "
                            + "session ou le second facteur sont contournables", type)
                    .isNull();
        }

        @Test
        @DisplayName("un jeton SANS claim type n'authentifie pas")
        void typeAbsent() {
            // Un jeton forge a partir d'une cle compromise, ou emis par un
            // chemin oublie, ne doit pas beneficier du doute.
            claims(null, List.of("ROLE_ADMIN"));

            assertThat(identitePosee()).isNull();
        }

        @Test
        @DisplayName("un claim type d'un autre type Java n'authentifie pas et ne lève pas")
        void typeMalForme() {
            Map<String, Object> claims = new HashMap<>();
            claims.put("type", 42);
            claims.put("roles", List.of("ROLE_ADMIN"));
            claimsBruts(claims);

            assertThat(identitePosee()).isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les deux espaces de rôles ne se mélangent pas")
    class EspacesDeRoles {

        @Test
        @DisplayName("un ADMIN du PORTAIL n'obtient pas ROLE_ADMIN sur la plateforme")
        void pasDElevationParHomonymie() {
            // LE PIEGE QUE LA CORRECTION AURAIT PU INTRODUIRE.
            //
            // Deux espaces de roles independants partagent des noms :
            // l'AdminSeeder d'interface-service cree un compte de portail de
            // role "ADMIN", sans aucun rapport avec UserRole.ADMIN.
            //
            // Normaliser le claim de role en ROLE_ADMIN aurait fait d'un
            // administrateur du portail developpeur un administrateur de la
            // plateforme d'authentification — une elevation de privilege
            // obtenue sans rien forger, par simple homonymie. Le separateur
            // correct est le claim type, pas le nom du role.
            Map<String, Object> claims = new HashMap<>();
            claims.put("type", "developer");
            claims.put("role", "ADMIN");
            claimsBruts(claims);

            assertThat(autorites())
                    .withFailMessage("un administrateur du portail développeur obtient "
                            + "les droits d'administrateur de la plateforme par "
                            + "homonymie de rôle")
                    .containsExactly("ROLE_DEVELOPER")
                    .doesNotContain("ROLE_ADMIN");
        }

        @Test
        @DisplayName("un jeton de développeur authentifie bien, comme ROLE_DEVELOPER")
        void developpeurAuthentifie() {
            // Il doit authentifier : le modele Auth-as-a-Service veut que
            // l'application du developpeur appelle /auth/** en son nom. Ne pas
            // l'authentifier fermerait la plateforme a ses clients.
            Map<String, Object> claims = new HashMap<>();
            claims.put("type", "developer");
            claims.put("role", "DEVELOPER");
            claimsBruts(claims);

            assertThat(autorites()).containsExactly("ROLE_DEVELOPER");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Absence de jeton et dégradation")
    class Degradation {

        /** Traverse le filtre sans en-tête, ou avec l'en-tête donné. */
        private boolean transmise(String enTete) {
            MockServerHttpRequest.BaseBuilder<?> requete =
                    MockServerHttpRequest.get("/auth/login");
            if (enTete != null) {
                requete.header("Authorization", enTete);
            }
            MockServerWebExchange echange = MockServerWebExchange.from(
                    (MockServerHttpRequest) requete.build());
            AtomicBoolean atteinte = new AtomicBoolean(false);

            filtre.filter(echange, suite -> {
                atteinte.set(true);
                return Mono.empty();
            }).block();

            return atteinte.get();
        }

        @Test
        @DisplayName("sans en-tête, la requête passe sans identité")
        void sansEnTete() {
            // Indispensable : /auth/login est public et n'a pas de jeton a
            // presenter. Refuser ici rendrait la connexion impossible.
            assertThat(transmise(null)).isTrue();
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = {"Bearer", "Basic xyz", "bearer jeton", "jeton"})
        @DisplayName("un en-tête mal formé ne perd pas la requête")
        void enTeteMalForme(String enTete) {
            assertThat(transmise(enTete)).isTrue();
        }

        @Test
        @DisplayName("un jeton invalide passe sans identité, sans lever")
        void jetonInvalide() {
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(false));
            claims("user", List.of("ROLE_ADMIN"));

            assertThat(identitePosee()).isNull();
        }

        @Test
        @DisplayName("une erreur de validation ne perd pas la requête")
        void erreurDeValidation() {
            when(jwtService.validateTokenReactive(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));

            assertThat(identitePosee()).isNull();
        }

        @Test
        @DisplayName("une validation VIDE ne perd pas la requête")
        void validationVide() {
            // Le piege repertorie cinq fois dans ce depot : un Mono vide
            // traverse le flatMap sans le declencher, et onErrorResume ne
            // s'applique pas puisqu'il n'y a pas d'erreur. La requete ne serait
            // ni transmise ni repondue, et le client attendrait jusqu'a son
            // delai d'expiration.
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.empty());

            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/admin/accounts")
                            .header("Authorization", "Bearer " + JETON)
                            .build());
            AtomicBoolean atteinte = new AtomicBoolean(false);

            filtre.filter(echange, suite -> {
                atteinte.set(true);
                return Mono.empty();
            }).block();

            assertThat(atteinte.get() || echange.getResponse().getStatusCode() != null)
                    .withFailMessage("une validation vide perd la requête : ni "
                            + "transmise, ni répondue")
                    .isTrue();
        }

        @Test
        @DisplayName("des claims illisibles passent sans identité")
        void claimsIllisibles() {
            // extractAllClaims rattrape ses exceptions et rend une map vide :
            // le filtre doit en faire une absence d'identite, pas une erreur.
            claimsBruts(new HashMap<>());

            assertThat(identitePosee()).isNull();
        }

        @Test
        @DisplayName("un jeton d'accès SANS aucun rôle authentifie sans autorité")
        void accesSansRole() {
            // Un utilisateur final ordinaire n'a pas de role : il doit pouvoir
            // etre identifie — les controles de propriete en dependent — sans
            // obtenir d'autorite pour autant.
            claims("user", List.of());

            Authentication identite = identitePosee();
            assertThat(identite)
                    .withFailMessage("un utilisateur sans rôle n'est pas identifié : "
                            + "les contrôles de propriété en aval n'ont plus de sujet")
                    .isNotNull();
            assertThat(identite.getAuthorities()).isEmpty();
        }
    }
}
