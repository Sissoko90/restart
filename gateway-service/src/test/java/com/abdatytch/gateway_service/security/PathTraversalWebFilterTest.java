package com.abdatytch.gateway_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
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

    /**
     * Une requete dont l'encodage est PRESERVE.
     *
     * <h3>Pourquoi {@code URI.create} et non {@code MockServerHttpRequest.get(String)}</h3>
     *
     * La seconde RE-ENCODE la chaine : {@code ?q=50%25} y devient
     * {@code q=50%2525} en brut. Un test ecrit avec elle mesure donc un double
     * encodage la ou il croit mesurer un encodage simple — et le filtre lit
     * desormais la requete en BRUT, ce qui rend la difference visible.
     *
     * <p>Mesure faite le 2026-11-13 : {@code get("?chemin=%2e%2e")} produit
     * {@code chemin=%252e%252e}.
     */
    private MockServerHttpRequest requete(String cible) {
        return MockServerHttpRequest.method(HttpMethod.GET, URI.create(cible)).build();
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

        /**
         * LE DOUBLE DECODAGE DE LA REQUETE, PINCE.
         *
         * <p>Le filtre lisait {@code getURI().getQuery()}, qui rend la chaine
         * DEJA DECODEE, puis la decodait une seconde fois. Il controlait donc
         * a une profondeur de DEUX, alors que le service en aval n'en decode
         * qu'une.
         *
         * <p>Ce test mesure la profondeur : une traversee encodee UNE fois est
         * refusee, parce que c'est ce que le service verrait.
         */
        @Test
        @DisplayName("une traversee encodee une fois dans la requete est refusee")
        void traverseeEncodeeDansLaQuery() {
            assertThat(passe(requete(
                    "/api/v1/media/fichier?chemin=%2e%2e%2f%2e%2e%2fetc")))
                    .withFailMessage("""
                            Une traversee encodee dans la chaine de requete passe.

                            Le service en aval decodera une fois et verra « ../../etc ».""")
                    .isFalse();
        }

        /**
         * UN DOUBLE ENCODAGE DANS LA REQUETE PASSE, ET C'EST LA BONNE REPONSE.
         *
         * <p>Ce test documente une LIMITE choisie, pas un oubli.
         *
         * <p>{@code chemin=%252e%252e%252fetc} donne, apres l'unique decodage
         * du service, la valeur {@code %2e%2e%2fetc} — une chaine de texte, pas
         * un chemin. Pour qu'elle devienne {@code ../etc}, il faudrait que le
         * service la decode UNE SECONDE FOIS ; ce serait son defaut a lui, et
         * la passerelle ne peut pas enumerer les bogues de ses voisins.
         *
         * <p>Refuser ici couterait cher et pour rien : un parametre portant une
         * URL double-encodee — cas courant d'une redirection qui traverse deux
         * couches, {@code redirect=https%253A%252F%252Fx} — serait bloque.
         * Bloquer du trafic valide a un cout : on finit par desactiver le
         * filtre.
         */
        @Test
        @DisplayName("un double encodage dans la requete passe : le controle est a UN decodage")
        void doubleEncodageDansLaQuery() {
            assertThat(passe(requete(
                    "/api/v1/media/fichier?chemin=%252e%252e%252fetc")))
                    .withFailMessage("""
                            Un double encodage dans la requete est refuse.

                            C'est une limite CHOISIE : le controle porte a la \
                            profondeur que le service voit. Refuser ici bloquerait \
                            une URL double-encodee dans un parametre de redirection.""")
                    .isTrue();
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
            assertThat(passe(requete("/api/v1/users/me?filter=a%2Fb")))
                    .withFailMessage("une barre encodee dans une valeur de parametre "
                            + "est refusee : du trafic valide est bloque, et un filtre "
                            + "qui bloque le trafic valide finit desactive")
                    .isTrue();
        }

        /**
         * LE FAUX POSITIF QUI COUTAIT LE PLUS CHER.
         *
         * <p>Un pour-cent litteral dans une valeur de parametre — « Solde
         * -50% », « TVA 18% », un motif de recherche — est envoye en
         * {@code %25}. Le filtre le refusait en 400 ET le comptait comme une
         * tentative de traversee : quelques recherches de ce genre faisaient
         * bloquer l'adresse de l'acheteur.
         *
         * <p>Dans un CHEMIN, {@code %25} reste refuse : c'est la seule
         * difference entre les deux origines.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/market/produits?q=50%25",
                "/api/v1/market/produits?q=%25",
                "/api/v1/market/produits?q=TVA%2018%25",
                "/api/v1/market/produits?q=T%5Fshirt"
        })
        @DisplayName("un pour-cent litteral dans un parametre passe")
        void pourcentLitteralDansParametre(String cible) {
            assertThat(passe(requete(cible)))
                    .withFailMessage("""
                            %s est refuse alors qu'il est legitime.

                            Un acheteur cherchant « 50%% » recevrait un 400 et verrait \
                            son adresse comptee en violation de securite.""", cible)
                    .isTrue();
        }

        @Test
        @DisplayName("mais %25 dans le CHEMIN reste refuse")
        void pourcentDansLeChemin() {
            assertThat(passe(requete("/api/v1/users/%252e%252e/me")))
                    .withFailMessage("""
                            Le double encodage dans un chemin n'est plus refuse.

                            La correction ne devait lever la regle que pour la chaine \
                            de requete.""")
                    .isFalse();
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
