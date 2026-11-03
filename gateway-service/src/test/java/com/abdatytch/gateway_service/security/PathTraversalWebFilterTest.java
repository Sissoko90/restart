package com.abdatytch.gateway_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detection de traversee de repertoire au Gateway.
 *
 * <h2>Pourquoi l'ancien motif ne suffisait pas</h2>
 *
 * Le motif etait {@code .*[/\\]\.\.[\\/].*} : il exigeait que {@code ..} soit
 * <b>entoure</b> de separateurs. Quatre variantes passaient, chacune verifiee
 * avant correction :
 *
 * <ul>
 *   <li>{@code /api/v1/auth/..} — {@code ..} en fin de chemin, sans barre
 *       finale. C'est la forme la plus courante, et nombre de serveurs
 *       normalisent {@code /a/b/..} en {@code /a} ;</li>
 *   <li>{@code /..} — a la racine ;</li>
 *   <li>{@code /api/v1/auth/....//x} — variante {@code ....//} ;</li>
 *   <li>{@code /api/v1/auth/..;/x} — parametre de chemin, retire par le
 *       conteneur avant resolution.</li>
 * </ul>
 *
 * <h2>Un faux positif compte aussi</h2>
 *
 * L'ancien controle bloquait {@code %2e}, {@code %2f} et {@code %5c} n'importe
 * ou, donc {@code /api/v1/users/me?filter=a%2Fb} — une barre encodee dans une
 * valeur de parametre, parfaitement normale. Un filtre qui refuse du trafic
 * valide finit par etre desactive, et c'est alors l'ensemble de sa protection
 * qui disparait. Le dernier bloc de tests garde ce cas.
 */
@DisplayName("PathTraversalWebFilter - traversee de repertoire")
class PathTraversalWebFilterTest {

    private PathTraversalWebFilter filtre;

    @BeforeEach
    void setUp() {
        ClientIpResolver resolveur = org.mockito.Mockito.mock(ClientIpResolver.class);
        org.mockito.Mockito.when(resolveur.resolve(org.mockito.Mockito.any()))
                .thenReturn("203.0.113.7");
        filtre = new PathTraversalWebFilter(resolveur);
    }

    /** Rend {@code true} si la requete a atteint la suite de la chaine. */
    private boolean passe(MockServerHttpRequest requete) {
        MockServerWebExchange echange = MockServerWebExchange.from(requete);
        AtomicBoolean atteinte = new AtomicBoolean(false);

        filtre.filter(echange, suite -> {
            atteinte.set(true);
            return Mono.empty();
        }).block();

        if (!atteinte.get()) {
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("la requete a ete bloquee sans statut de refus")
                    .isIn(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
        return atteinte.get();
    }

    // ==================================================================
    @Nested
    @DisplayName("Variantes qui passaient avant correction")
    class VariantesManquees {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/auth/..",
                "/..",
                "/api/v1/auth/....//internal/auth/users/1",
                "/api/v1/auth/.../internal",
                "/api/v1/auth/..;/internal"
        })
        @DisplayName("ces quatre formes sont desormais bloquees")
        void formesBloquees(String chemin) {
            assertThat(passe(MockServerHttpRequest.get(chemin).build()))
                    .withFailMessage("%s traverse le filtre : c'est l'une des variantes "
                            + "que le motif positionnel laissait passer", chemin)
                    .isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Formes deja couvertes")
    class FormesCouvertes {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/auth/../internal/auth/users/1",
                "/api/v1/auth/../",
                "/api/v1/auth/%2e%2e/internal",
                "/api/v1/auth/%2E%2E%2Finternal"
        })
        @DisplayName("la traversee classique et sa version encodee restent bloquees")
        void formesClassiques(String chemin) {
            assertThat(passe(MockServerHttpRequest.get(chemin).build())).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Autres charges refusees")
    class AutresCharges {

        @Test
        @DisplayName("un octet nul est refuse")
        void octetNul() {
            // Sert a tronquer un nom de fichier au niveau systeme : « x.jpg%00.php »
            // peut etre vu comme « x.jpg » par un controle et « x.jpg\\0.php » par
            // l'appel systeme.
            assertThat(passe(MockServerHttpRequest.get("/api/v1/media/x%00.jpg").build()))
                    .isFalse();
        }

        @Test
        @DisplayName("le double encodage est refuse AVANT tout decodage")
        void doubleEncodage() {
            // %252e se decode en %2e, qui se decode en point. Decoder deux fois
            // serait precisement ce qui contourne un controle a decodage unique :
            // on refuse donc la tentative plutot que de l'eplucher.
            assertThat(passe(MockServerHttpRequest.get("/api/v1/auth/%252e%252e/x").build()))
                    .isFalse();
        }

        @Test
        @DisplayName("une traversee dans la chaine de requete est refusee")
        void traverseeDansLaQuery() {
            assertThat(passe(MockServerHttpRequest
                    .get("/api/v1/media/fichier?chemin=../../etc/passwd").build()))
                    .isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Trafic legitime - le faux positif supprime")
    class TraficLegitime {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/auth/login",
                "/api/v1/users/me",
                "/api/v1/users/11111111-1111-1111-1111-111111111111",
                "/api/v1/auth/v3/api-docs",
                "/api/v1/media/fichier.photo.jpg",
                "/actuator/health"
        })
        @DisplayName("un chemin ordinaire passe")
        void cheminOrdinaire(String chemin) {
            assertThat(passe(MockServerHttpRequest.get(chemin).build()))
                    .withFailMessage("%s est refuse alors qu'il est legitime", chemin)
                    .isTrue();
        }

        @Test
        @DisplayName("une barre obliques encodee dans un parametre passe")
        void barreEncodeeDansParametre() {
            // LE faux positif corrige. L'ancien controle refusait tout %2f, donc
            // toute valeur de parametre contenant une barre encodee — un
            // identifiant base64, un chemin relatif, une date au format ISO avec
            // separateurs encodes.
            assertThat(passe(MockServerHttpRequest
                    .get("/api/v1/users/me?filter=a%2Fb").build()))
                    .withFailMessage("une barre encodee dans une valeur de parametre "
                            + "est refusee : du trafic valide est bloque, et un filtre "
                            + "qui bloque le trafic valide finit desactive")
                    .isTrue();
        }

        @Test
        @DisplayName("un point unique dans un segment passe")
        void pointUniquePasse() {
            // Un seul point n'est pas une traversee, et les noms de fichiers en
            // contiennent.
            assertThat(passe(MockServerHttpRequest.get("/api/v1/media/a.b.c.jpg").build()))
                    .isTrue();
        }
    }
}
