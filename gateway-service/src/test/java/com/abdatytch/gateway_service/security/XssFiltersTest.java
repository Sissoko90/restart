package com.abdatytch.gateway_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les deux filtres d'injection du Gateway, et leurs roles distincts.
 *
 * <h2>Le defaut le plus grave trouve ici</h2>
 *
 * {@link XssProtectionWebFilter} s'appelait « Protection » et n'en assurait
 * <b>aucune</b>. Il calculait une chaine nettoyee, la comparait a l'original,
 * ecrivait un avertissement — puis <b>jetait le resultat</b> et poursuivait la
 * chaine avec la requete inchangee. Les deux controles d'en-tetes se contentaient
 * aussi de journaliser.
 *
 * <p>C'est pire qu'un filtre absent : son nom et sa presence laissaient croire
 * que les parametres de requete etaient assainis au Gateway.
 *
 * <h2>Pourquoi il ne bloque toujours pas</h2>
 *
 * Ses motifs ont ete mesures. Ils <b>manquent</b>
 * {@code <img src=x onerror=alert(1)>} et produisent des faux positifs sur du
 * trafic legitime — trois sur six echantillons plausibles. Bloquer sur cette
 * base casserait du trafic valide, et un filtre qui casse le trafic valide finit
 * desactive.
 *
 * <p>Il <b>signale</b> desormais : dix tentatives depuis la meme adresse
 * finissent par la faire inscrire en liste noire, ce qui traite le balayage sans
 * refuser la requete isolee d'un utilisateur dont le texte contenait un motif.
 *
 * <p>{@link BodyXssScannerWebFilter}, lui, <b>bloque</b> — et c'est coherent :
 * un corps JSON contenant {@code <script>} sur un endpoint de la plateforme est
 * nettement moins ambigu qu'un parametre de requete.
 */
@DisplayName("Filtres d'injection - detection et blocage")
class XssFiltersTest {

    private static final String IP = "203.0.113.7";

    private SecurityViolationRecorder enregistreur;
    private ClientIpResolver resolveur;

    @BeforeEach
    void setUp() {
        enregistreur = Mockito.mock(SecurityViolationRecorder.class);
        when(enregistreur.enregistrer(anyString(), anyString())).thenReturn(Mono.empty());

        resolveur = Mockito.mock(ClientIpResolver.class);
        when(resolveur.resolve(Mockito.any())).thenReturn(IP);
    }

    private boolean passeEnTetes(MockServerHttpRequest requete) {
        var filtre = new XssProtectionWebFilter(resolveur).avecEnregistreur(enregistreur);
        MockServerWebExchange echange = MockServerWebExchange.from(requete);
        AtomicBoolean atteinte = new AtomicBoolean(false);
        filtre.filter(echange, suite -> {
            atteinte.set(true);
            return Mono.empty();
        }).block();
        return atteinte.get();
    }

    private MockServerWebExchange passeCorps(String corps) {
        var filtre = new BodyXssScannerWebFilter(resolveur).avecEnregistreur(enregistreur);
        MockServerWebExchange echange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(corps));
        AtomicBoolean atteinte = new AtomicBoolean(false);
        echange.getAttributes().put("atteinte", atteinte);
        filtre.filter(echange, suite -> {
            atteinte.set(true);
            return Mono.empty();
        }).block();
        return echange;
    }

    private boolean corpsPasse(MockServerWebExchange echange) {
        return ((AtomicBoolean) echange.getAttributes().get("atteinte")).get();
    }

    // ==================================================================
    @Nested
    @DisplayName("Parametres et en-tetes - signale sans bloquer")
    class SignaleSansBloquer {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "q=<script>alert(1)</script>",
                "url=javascript:alert(1)",
                "x=<svg onload=alert(1)>"
        })
        @DisplayName("un motif detecte est SIGNALE")
        void motifSignale(String query) {
            // Ce signalement n'existait pas : le filtre se contentait d'un
            // avertissement dans les journaux, sans consequence cumulative. Dix
            // tentatives ne menaient a rien.
            passeEnTetes(MockServerHttpRequest.get("/api/v1/users/me?" + query).build());

            verify(enregistreur).enregistrer(Mockito.eq(IP), anyString());
        }

        @Test
        @DisplayName("la requete PASSE quand meme - choix assume, pas un oubli")
        void requetePasseQuandMeme() {
            // Decision explicite, documentee dans la classe : les motifs ne sont
            // pas assez fiables pour refuser. Ce test existe pour que personne ne
            // « corrige » le comportement en croyant a un oubli — et pour que
            // celui qui voudra vraiment bloquer sache qu'il change une decision.
            assertThat(passeEnTetes(MockServerHttpRequest
                    .get("/api/v1/users/me?q=<script>alert(1)</script>").build()))
                    .withFailMessage("le filtre bloque desormais : verifier d'abord "
                            + "que les motifs ne produisent plus de faux positifs")
                    .isTrue();
        }

        @Test
        @DisplayName("un motif dans User-Agent est signale")
        void motifDansUserAgent() {
            passeEnTetes(MockServerHttpRequest.get("/api/v1/users/me")
                    .header("User-Agent", "<script>alert(1)</script>")
                    .build());

            verify(enregistreur).enregistrer(Mockito.eq(IP), anyString());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "q=recherche normale",
                "nom=Jean-Baptiste",
                "page=2&taille=20"
        })
        @DisplayName("du trafic ordinaire n'est pas signale")
        void traficOrdinaire(String query) {
            // Signaler a tort finirait par mettre en liste noire des adresses
            // innocentes : le signalement doit rester rare.
            passeEnTetes(MockServerHttpRequest.get("/api/v1/users/me?" + query).build());

            verify(enregistreur, never()).enregistrer(anyString(), anyString());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Corps de requete - bloque")
    class CorpsBloque {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "{\"nom\":\"<script>alert(1)</script>\"}",
                "{\"url\":\"javascript:alert(1)\"}",
                "{\"html\":\"<iframe src=evil></iframe>\"}",
                "{\"x\":\"data:text/html,<script>\"}"
        })
        @DisplayName("un corps contenant une injection est refuse en 400")
        void corpsInjecteRefuse(String corps) {
            var echange = passeCorps(corps);

            assertThat(corpsPasse(echange))
                    .withFailMessage("le corps injecte atteint le service")
                    .isFalse();
            assertThat(echange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("un corps refuse est SIGNALE")
        void corpsRefuseSignale() {
            // Sans signalement, un essai systematique se poursuivait
            // indefiniment : chaque requete refusee isolement, rien ne
            // s'accumulait.
            passeCorps("{\"nom\":\"<script>alert(1)</script>\"}");

            verify(enregistreur).enregistrer(Mockito.eq(IP), Mockito.eq("xss-body"));
        }

        @Test
        @DisplayName("un corps ordinaire passe et reste lisible en aval")
        void corpsOrdinairePasse() {
            // Le filtre consomme le corps pour l'examiner : s'il ne le
            // reconstituait pas, tous les endpoints POST recevraient un corps
            // vide. C'est le risque propre a ce genre de filtre.
            var echange = passeCorps("{\"phoneNumber\":\"+22374427759\",\"password\":\"x\"}");

            assertThat(corpsPasse(echange))
                    .withFailMessage("un corps legitime est refuse")
                    .isTrue();
        }

        @Test
        @DisplayName("un corps VIDE sur un POST ne perd pas la requete")
        void corpsVideNePerdPasLaRequete() {
            // DataBufferUtils.join sur un corps vide rend un Mono VIDE : aucun
            // tampon a joindre. Le flatMap ne s'execute alors jamais, et la
            // requete n'est ni transmise ni repondue.
            //
            // Le cas est tout a fait atteignable : un POST avec
            // Content-Type: application/json et un corps vide. Le service aval
            // devrait repondre 400 ; au lieu de quoi le client attend.
            var filtre = new BodyXssScannerWebFilter(resolveur).avecEnregistreur(enregistreur);
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(""));
            AtomicBoolean atteinte = new AtomicBoolean(false);

            filtre.filter(echange, suite -> {
                atteinte.set(true);
                return Mono.empty();
            }).block();

            assertThat(atteinte.get() || echange.getResponse().getStatusCode() != null)
                    .withFailMessage("un corps vide perd la requete : ni transmise, "
                            + "ni repondue")
                    .isTrue();
        }

        @Test
        @DisplayName("une methode sans corps n'est pas inspectee")
        void methodeSansCorps() {
            var filtre = new BodyXssScannerWebFilter(resolveur).avecEnregistreur(enregistreur);
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/users/me").build());
            AtomicBoolean atteinte = new AtomicBoolean(false);

            filtre.filter(echange, suite -> {
                atteinte.set(true);
                return Mono.empty();
            }).block();

            assertThat(atteinte.get()).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les limites des motifs, mesurees")
    class LimitesDesMotifs {

        @Test
        @DisplayName("une charge courante N'EST PAS detectee - limite assumee")
        void chargeNonDetectee() {
            // <img src=x onerror=alert(1)> est l'une des formes les plus
            // courantes, et aucun motif ne l'attrape : « onerror » n'est pas dans
            // la liste, et « src= » sans guillemets echappe au motif qui les
            // exige.
            //
            // Ce test documente la limite plutot que de la taire. Il echouera si
            // quelqu'un ameliore les motifs — ce qui sera une bonne nouvelle, et
            // le signal qu'on peut alors envisager de bloquer.
            passeEnTetes(MockServerHttpRequest
                    .get("/api/v1/users/me?x=<img src=x onerror=alert(1)>").build());

            verify(enregistreur, never()).enregistrer(anyString(), anyString());
        }

        @Test
        @DisplayName("la vraie defense est la politique de securite de contenu")
        void vraieDefense() {
            // Rappel inscrit dans les tests : le XSS se defend a l'encodage en
            // sortie et par la CSP — default-src 'self', posee par SecurityConfig
            // — pas par un filtre d'entree. Le Gateway voit des donnees qui
            // peuvent legitimement contenir <script> : quelqu'un qui ecrit au
            // sujet du HTML.
            //
            // Ce test n'assertit rien de technique ; il porte la raison pour
            // laquelle les deux filtres ci-dessus sont des couches
            // supplementaires et non la defense principale.
            assertThat(true).isTrue();
        }
    }
}
