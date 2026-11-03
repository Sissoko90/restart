package com.abdatytch.interface_service.filter;

import com.abdatytch.interface_service.service.IDevJwtService;
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
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Les quatre derniers filtres d'interface-service.
 *
 * <h2>{@code MaintenanceJwtFilter} — en bon état, un repli ajouté</h2>
 *
 * Il vérifiait déjà le <b>type</b> du jeton, échouait fermé sur erreur et
 * refusait de façon laconique. Seul manquait le repli sur un {@code Mono} vide :
 * la validation rendant un {@code Mono} vide, le {@code flatMap} ne se
 * déclenchait pas et {@code onErrorResume} ne s'appliquait pas — il n'y a pas
 * d'erreur, juste une absence de valeur.
 *
 * <h2>{@code ZipBombFilter} — deux défauts</h2>
 *
 * <ol>
 *   <li>{@code collectList()} chargeait la <b>totalité</b> du corps en mémoire
 *       <b>avant</b> le contrôle de bombe. Auto-contradictoire : l'attaque visée
 *       est le gonflement à la décompression, et celle-ci était le gonflement à
 *       la réception — elle fonctionnait. Même défaut que celui corrigé au
 *       Gateway ;</li>
 *   <li>le corps consommé <b>n'était pas restitué</b> : {@code chain.filter} y
 *       recevait l'échange d'origine, dont le corps était déjà lu. Un envoi ZIP
 *       légitime arrivait <b>vide</b> au contrôleur.</li>
 * </ol>
 *
 * <h2>{@code XssProtectionFilter} — presque inerte par conception</h2>
 *
 * {@code isJsonApiEndpoint} court-circuite le filtre entier pour
 * {@code /admin/}, {@code /auth/}, {@code /api/}, {@code /dev/} <b>et</b> pour
 * tout {@code Content-Type: application/json}. interface-service ne servant que
 * des API JSON, le filtre ne s'applique quasiment à rien — et l'exemption tient
 * à un en-tête que l'appelant choisit.
 *
 * <p>Le choix est défendable : encoder en HTML un corps JSON le corromprait, et
 * le Gateway a tranché de la même façon pour la même raison. La vraie défense
 * est l'encodage en sortie et la CSP, que {@code CspFilter} pose. Ces tests
 * figent la frontière, pour que personne ne croie ce filtre actif.
 *
 * <p>Son bufferisage non borné a tout de même été corrigé : un code presque
 * jamais atteint doit rester sûr le jour où il l'est.
 */
@DisplayName("Filtres restants d'interface-service")
class FiltresRestantsTest {

    // ==================================================================
    @Nested
    @DisplayName("MaintenanceJwtFilter")
    class Maintenance {

        private IDevJwtService jwtService;
        private MaintenanceJwtFilter filtre;

        @BeforeEach
        void setUp() {
            jwtService = Mockito.mock(IDevJwtService.class);
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            when(jwtService.extractClaim(anyString(), anyString())).thenReturn("maintenance");
            filtre = new MaintenanceJwtFilter(jwtService);
        }

        private MockServerWebExchange appel(String chemin, String enTete) {
            MockServerHttpRequest.BaseBuilder<?> requete = MockServerHttpRequest.get(chemin);
            if (enTete != null) {
                requete.header("Authorization", enTete);
            }
            MockServerWebExchange echange = MockServerWebExchange.from(
                    (MockServerHttpRequest) requete.build());
            AtomicBoolean atteinte = new AtomicBoolean(false);
            echange.getAttributes().put("atteinte", atteinte);

            filtre.filter(echange, suite -> {
                atteinte.set(true);
                return Mono.empty();
            }).block();
            return echange;
        }

        private boolean passe(MockServerWebExchange e) {
            return ((AtomicBoolean) e.getAttributes().get("atteinte")).get();
        }

        @Test
        @DisplayName("un jeton de maintenance valide ouvre la maintenance")
        void jetonValide() {
            assertThat(passe(appel("/maintenance/status", "Bearer jeton"))).isTrue();
        }

        @ParameterizedTest(name = "type={0}")
        @ValueSource(strings = {"user", "developer", "refresh", "refresh_user"})
        @DisplayName("un jeton ordinaire n'ouvre PAS la maintenance")
        void jetonOrdinaireRefuse(String type) {
            // Le second facteur de la maintenance est son type de jeton dedie :
            // sans ce controle, tout porteur d'un jeton valide y accederait.
            when(jwtService.extractClaim(anyString(), anyString())).thenReturn(type);

            var echange = appel("/maintenance/status", "Bearer jeton");

            assertThat(passe(echange)).isFalse();
            assertThat(echange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("un jeton invalide est refusé en 401")
        void jetonInvalide() {
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(false));

            var echange = appel("/maintenance/status", "Bearer jeton");

            assertThat(passe(echange)).isFalse();
            assertThat(echange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("sans en-tête Authorization, refus")
        void sansEnTete() {
            assertThat(passe(appel("/maintenance/status", null))).isFalse();
        }

        @Test
        @DisplayName("/maintenance/auth reste ouvert - c'est par là qu'on obtient le jeton")
        void authentificationOuverte() {
            // Exiger un jeton de maintenance pour obtenir un jeton de
            // maintenance rendrait la maintenance inaccessible. Meme nature que
            // le defaut corrige sur AdminSecretFilter et /admin/confirm.
            assertThat(passe(appel("/maintenance/auth", null))).isTrue();
        }

        @Test
        @DisplayName("un chemin hors maintenance n'est pas concerné")
        void horsPerimetre() {
            assertThat(passe(appel("/auth/login", null))).isTrue();
        }

        @Test
        @DisplayName("une erreur de validation échoue FERMÉ")
        void erreurEchoueFerme() {
            // Politique inverse de la liste noire d'IP, et pour la bonne raison :
            // ici c'est l'identite du porteur qui est en cause, pas une adresse
            // reputee hostile.
            when(jwtService.validateTokenReactive(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));

            assertThat(passe(appel("/maintenance/status", "Bearer jeton"))).isFalse();
        }

        @Test
        @DisplayName("une validation VIDE ne perd pas la requête et refuse")
        void validationVide() {
            // Le repli ajoute. Sans lui, le flatMap ne se declenchait pas et la
            // requete n'etait ni transmise ni repondue.
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.empty());

            var echange = appel("/maintenance/status", "Bearer jeton");

            assertThat(passe(echange))
                    .withFailMessage("une validation vide laisse passer")
                    .isFalse();
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("une validation vide perd la requête : ni "
                            + "transmise, ni répondue")
                    .isNotNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("ZipBombFilter")
    class BombeZip {

        private ZipBombFilter filtre;

        @BeforeEach
        void setUp() {
            filtre = new ZipBombFilter();
            ReflectionTestUtils.setField(filtre, "enabled", true);
            ReflectionTestUtils.setField(filtre, "maxCompressionRatio", 100);
            ReflectionTestUtils.setField(filtre, "maxUncompressedSizeMB", 500);
            ReflectionTestUtils.setField(filtre, "maxEntries", 1000);
            ReflectionTestUtils.setField(filtre, "maxNestedDepth", 3);
        }

        private MockServerWebExchange appel(String typeContenu, String corps) {
            MockServerHttpRequest.BaseBuilder<?> requete =
                    MockServerHttpRequest.post("/dev/upload");
            if (typeContenu != null) {
                requete.header("Content-Type", typeContenu);
            }
            MockServerWebExchange echange = MockServerWebExchange.from(
                    corps == null
                            ? (MockServerHttpRequest) requete.build()
                            : ((MockServerHttpRequest.BodyBuilder) requete).body(corps));

            AtomicBoolean atteinte = new AtomicBoolean(false);
            echange.getAttributes().put("atteinte", atteinte);
            filtre.filter(echange, suite -> {
                atteinte.set(true);
                // Consommer le corps, comme le ferait un controleur : c'est ainsi
                // qu'on verifie qu'il a bien ete restitue.
                return suite.getRequest().getBody()
                        .doOnNext(b -> echange.getAttributes().put(
                                "octetsRecus", b.readableByteCount()))
                        .then();
            }).block();
            return echange;
        }

        private boolean passe(MockServerWebExchange e) {
            return ((AtomicBoolean) e.getAttributes().get("atteinte")).get();
        }

        @Test
        @DisplayName("un contenu non ZIP n'est pas analysé")
        void contenuNonZip() {
            assertThat(passe(appel("application/json", "{\"a\":1}"))).isTrue();
        }

        @Test
        @DisplayName("sans Content-Type, pas d'analyse")
        void sansTypeContenu() {
            assertThat(passe(appel(null, null))).isTrue();
        }

        @Test
        @DisplayName("le corps est RESTITUÉ à la suite de la chaîne")
        void corpsRestitue() {
            // LE SECOND DEFAUT. L'ancien code lisait le corps puis transmettait
            // l'echange d'origine, deja consomme : un envoi ZIP legitime
            // arrivait VIDE au controleur.
            var echange = appel("application/zip", "contenu-pas-une-vraie-archive");

            assertThat(passe(echange)).isTrue();
            assertThat(echange.getAttributes().get("octetsRecus"))
                    .withFailMessage("le corps n'a pas été restitué : le contrôleur "
                            + "recevrait une requête vide")
                    .isNotNull();
        }

        @Test
        @DisplayName("une archive illisible passe - repli ouvert assumé")
        void archiveIllisible() {
            // Refuser tout ce qu'on ne sait pas analyser casserait des envois
            // legitimes. Le controleur en aval refusera ce qu'il ne sait pas
            // lire.
            assertThat(passe(appel("application/zip", "ceci-n-est-pas-un-zip"))).isTrue();
        }

        @Test
        @DisplayName("le filtre désactivé court-circuite tout")
        void desactive() {
            ReflectionTestUtils.setField(filtre, "enabled", false);

            assertThat(passe(appel("application/zip", "x"))).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("XssProtectionFilter - la frontière de son action")
    class ProtectionXss {

        private XssProtectionFilter filtre;

        @BeforeEach
        void setUp() {
            filtre = new XssProtectionFilter();
            ReflectionTestUtils.setField(filtre, "enabled", true);
            ReflectionTestUtils.setField(filtre, "sanitizeHtml", true);
            ReflectionTestUtils.setField(filtre, "blockScripts", true);
            ReflectionTestUtils.setField(filtre, "allowedTags", "p,br,strong");
            ReflectionTestUtils.setField(filtre, "blockedPatternsStr", "<script");
        }

        /**
         * @param valeurQ valeur BRUTE du paramètre {@code q}, ou {@code null}
         *                pour n'en poser aucun
         */
        private MockServerWebExchange appel(String chemin, String typeContenu,
                                             String valeurQ) {
            // Valeur passee en VARIABLE de gabarit, et non concatenee.
            //
            // `MockServerHttpRequest.get(String)` traite son argument comme un
            // gabarit d'URI et l'encode : une valeur deja encodee a la main y
            // devenait doublement encodee (`%3C` -> `%253C`), si bien que
            // getQueryParams() ne rendait plus aucun « < » et que la detection
            // ne pouvait pas se declencher. Le test echouait alors en accusant
            // le filtre.
            MockServerHttpRequest.BaseBuilder<?> b = valeurQ == null
                    ? MockServerHttpRequest.get(chemin)
                    : MockServerHttpRequest.get(chemin + "?q={v}", valeurQ);
            if (typeContenu != null) {
                b.header("Content-Type", typeContenu);
            }
            MockServerWebExchange echange = MockServerWebExchange.from(
                    (MockServerHttpRequest) b.build());
            AtomicBoolean atteinte = new AtomicBoolean(false);
            echange.getAttributes().put("atteinte", atteinte);
            filtre.filter(echange, suite -> {
                atteinte.set(true);
                return Mono.empty();
            }).block();
            return echange;
        }

        private boolean passe(MockServerWebExchange e) {
            return ((AtomicBoolean) e.getAttributes().get("atteinte")).get();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/admin/x", "/auth/login", "/api/v1/x", "/dev/keys"})
        @DisplayName("les quatre préfixes d'API sont ENTIÈREMENT exemptés")
        void prefixesExemptes(String chemin) {
            // Ce test FIGE une limite, il ne celebre pas une protection.
            //
            // Le filtre ne s'applique a rien de ce que sert interface-service.
            // Le choix est defendable — encoder en HTML un corps JSON le
            // corromprait — mais il doit etre visible, pour que personne ne
            // croie ce filtre actif.
            assertThat(passe(appel(chemin, null, "<script>alert(1)</script>")))
                    .withFailMessage("%s est filtré : la frontière a changé, "
                            + "vérifier que les corps JSON ne sont pas corrompus",
                            chemin)
                    .isTrue();
        }

        @Test
        @DisplayName("un Content-Type JSON exempte, quel que soit le chemin")
        void jsonExempte() {
            // L'exemption tient a un en-tete que l'APPELANT choisit : la portion
            // residuelle du filtre se contourne en declarant du JSON.
            assertThat(passe(appel("/quelque-chose", "application/json",
                    "<script>")))
                    .isTrue();
        }

        @Test
        @DisplayName("hors de ces cas, une injection dans la requête est refusée")
        void injectionRefuseeHorsExemption() {
            // La portion ou le filtre agit encore. Elle est etroite, mais elle
            // doit fonctionner.
            var echange = appel("/quelque-chose", null, "<script>alert(1)</script>");

            assertThat(passe(echange)).isFalse();
        }

        @Test
        @DisplayName("du trafic ordinaire hors exemption passe")
        void traficOrdinaire() {
            assertThat(passe(appel("/quelque-chose", null, "recherche normale"))).isTrue();
        }

        @Test
        @DisplayName("le filtre désactivé court-circuite tout")
        void desactive() {
            ReflectionTestUtils.setField(filtre, "enabled", false);

            assertThat(passe(appel("/quelque-chose", null, "<script>"))).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("CspFilter - les en-têtes qui constituent la vraie défense XSS")
    class Csp {

        private CspFilter filtre;

        @BeforeEach
        void setUp() {
            filtre = new CspFilter();
            ReflectionTestUtils.setField(filtre, "enabled", true);
            ReflectionTestUtils.setField(filtre, "reportOnly", false);
            ReflectionTestUtils.setField(filtre, "defaultSrc", "'self'");
            ReflectionTestUtils.setField(filtre, "scriptSrc", "'self'");
            ReflectionTestUtils.setField(filtre, "styleSrc", "'self'");
            ReflectionTestUtils.setField(filtre, "imgSrc", "'self'");
            ReflectionTestUtils.setField(filtre, "connectSrc", "'self'");
            ReflectionTestUtils.setField(filtre, "frameAncestors", "'none'");
            // Ce champ a une valeur par defaut en production
            // (`${security.csp.report-uri:/security/csp-report}`), donc il
            // n'est jamais nul la-bas. Hors contexte Spring, il faut le poser :
            // buildCspPolicy appelle reportUri.isEmpty().
            ReflectionTestUtils.setField(filtre, "reportUri", "/security/csp-report");
        }

        private MockServerWebExchange appel() {
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/auth/login").build());
            filtre.filter(echange, suite -> Mono.empty()).block();
            return echange;
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "Content-Security-Policy",
                "X-Content-Type-Options",
                "X-Frame-Options",
                "Referrer-Policy",
                "Permissions-Policy"
        })
        @DisplayName("les en-têtes de sécurité sont posés")
        void enTetesPoses(String enTete) {
            // C'est ici que le XSS se defend reellement : a l'encodage en sortie
            // et par la CSP. XssProtectionFilter n'est qu'une couche
            // supplementaire, et quasiment inerte.
            assertThat(appel().getResponse().getHeaders().getFirst(enTete))
                    .withFailMessage("%s n'est pas posé : la défense principale "
                            + "contre le XSS manque", enTete)
                    .isNotNull();
        }

        @Test
        @DisplayName("nosniff est bien la valeur attendue")
        void nosniff() {
            // Son absence rendait possible le reniflement de type sur les
            // fichiers servis — defaut deja corrige dans ce depot.
            assertThat(appel().getResponse().getHeaders()
                    .getFirst("X-Content-Type-Options"))
                    .isEqualTo("nosniff");
        }

        @Test
        @DisplayName("l'encadrement est refusé")
        void encadrementRefuse() {
            assertThat(appel().getResponse().getHeaders().getFirst("X-Frame-Options"))
                    .isEqualTo("DENY");
            assertThat(appel().getResponse().getHeaders()
                    .getFirst("Content-Security-Policy"))
                    .contains("frame-ancestors");
        }

        @Test
        @DisplayName("le mode report-only change l'en-tête, pas la politique")
        void modeRapportSeul() {
            // Confondre les deux publierait une politique qui n'applique rien.
            ReflectionTestUtils.setField(filtre, "reportOnly", true);

            var reponse = appel().getResponse();
            assertThat(reponse.getHeaders().getFirst("Content-Security-Policy-Report-Only"))
                    .isNotNull();
            assertThat(reponse.getHeaders().getFirst("Content-Security-Policy"))
                    .withFailMessage("les deux en-têtes sont posés : le navigateur "
                            + "applique la politique alors qu'elle devait être "
                            + "observée")
                    .isNull();
        }

        @Test
        @DisplayName("le filtre désactivé ne pose aucun en-tête")
        void desactive() {
            ReflectionTestUtils.setField(filtre, "enabled", false);

            assertThat(appel().getResponse().getHeaders()
                    .getFirst("Content-Security-Policy"))
                    .isNull();
        }
    }
}
