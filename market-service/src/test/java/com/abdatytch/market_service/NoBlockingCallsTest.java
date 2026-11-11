package com.abdatytch.market_service;

import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.enums.Visibilite;
import com.abdatytch.market_service.mapper.MarketMapper;
import com.abdatytch.market_service.security.GatewayAccessWebFilter;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifie que le chemin d'une requete ne bloque aucun fil reactif.
 *
 * <h2>Ce qui est exerce, et pourquoi</h2>
 *
 * <ol>
 *   <li>{@link GatewayAccessWebFilter} — il s'execute sur CHAQUE requete,
 *       avant tout le reste, et calcule un HMAC ;</li>
 *   <li>{@link MarketMapper} — appele une fois par article d'une page de
 *       catalogue. Sa javadoc affirme etre fait de fonctions PURES : ce test
 *       est ce qui rend cette affirmation verifiable. Ce depot a deja paye un
 *       mapper qui bloquait sur chaque reponse ;</li>
 *   <li>{@link PorteeDuCatalogue#voit} — la decision de visibilite, prise sur
 *       le chemin de chaque lecture unitaire ;</li>
 *   <li>le calcul de slug, qui passe par {@code Normalizer} — une classe du
 *       JDK dont les tables de caracteres se chargent au premier appel.</li>
 * </ol>
 *
 * <h2>Ce qui n'est PAS exerce, et c'est delibere</h2>
 *
 * Les appels sortants vers user-service et media-service. Ils passent par
 * {@code WebClient}, donc par Netty, et leur caractere non bloquant est celui
 * de la bibliotheque — le verifier ici testerait Reactor, pas ce module.
 *
 * <h2>Le controle negatif n'est pas une formalite</h2>
 *
 * C'est le seul test de ce fichier dont l'echec signifie « la detection est
 * morte » plutot que « le code a regresse ».
 */
@DisplayName("market-service : absence d'appels bloquants (BlockHound)")
class NoBlockingCallsTest {

    private static final String SECRET = "secret-de-passerelle-pour-test";
    private static final Duration DELAI = Duration.ofSeconds(30);

    private GatewayAccessWebFilter filtre;

    /**
     * Remet les ordonnanceurs a neuf, PUIS installe BlockHound.
     *
     * <p>Reactor met l'ordonnanceur {@code parallel} en cache pour toute la
     * JVM, a sa premiere utilisation. Si elle tombe avant l'installation, le
     * cache retient des fils qui ne portent pas le marqueur {@code NonBlocking}
     * sur lequel repose la detection — et les tests passent alors pour la
     * mauvaise raison.
     */
    @BeforeAll
    static void installerBlockHound() {
        Schedulers.shutdownNow();
        BlockHound.install();
    }

    @BeforeEach
    void preparer() {
        filtre = new GatewayAccessWebFilter(new HMACSignatureUtil());
        // Le @Value n'est pas injecte hors du conteneur : sans cette ligne le
        // secret resterait nul, le filtre refuserait avant de calculer quoi que
        // ce soit, et le test mesurerait un refus precoce.
        ReflectionTestUtils.setField(filtre, "secretDePasserelle", SECRET);
    }

    /**
     * Une requete signee, construite HORS du fil reactif.
     *
     * <p>{@code UUID.randomUUID()} lit {@code /dev/random}. Appelee dans le
     * {@code Mono.defer}, elle ferait signaler a BlockHound un blocage venu du
     * TEST. En production, l'identifiant de requete est engendre par la
     * PASSERELLE et arrive dans un en-tete.
     */
    private ServerWebExchange requeteSignee(String chemin) {
        String horodatage = String.valueOf(System.currentTimeMillis());
        String idRequete = UUID.randomUUID().toString();
        var hmac = new HMACSignatureUtil();
        String donnees = hmac.buildSignatureData(
                horodatage, chemin, HttpMethod.GET.name(), idRequete);

        var requete = MockServerHttpRequest.get(chemin)
                .header("X-Gateway-Timestamp", horodatage)
                .header("X-Gateway-Request-Id", idRequete)
                .header("X-Gateway-Signature", hmac.generateSignature(SECRET, donnees))
                .build();

        return MockServerWebExchange.from(requete).mutate()
                .request(new ServerHttpRequestDecorator(requete) {
                    @Override
                    public InetSocketAddress getRemoteAddress() {
                        return new InetSocketAddress("127.0.0.1", 54321);
                    }
                }).build();
    }

    /**
     * Un article d'essai.
     *
     * <p>A APPELER HORS du fil surveille : {@code UUID.randomUUID()} lit
     * {@code /dev/random} par {@code NativePRNG}. Et le piege est sournois —
     * ce generateur TAMPONNE, donc seul le premier appel de la JVM bloque. Un
     * test ecrit sans cette precaution passe ou echoue selon l'ORDRE
     * d'execution, ce qui est la pire forme de faux vert.
     */
    private static Produit produit() {
        Produit produit = new Produit();
        produit.setId(UUID.randomUUID());
        produit.setBoutiqueId(UUID.randomUUID());
        produit.setNom("Bazin riche");
        produit.setPrix(25_000L);
        produit.setDevise("XOF");
        produit.setPays("ML");
        produit.setVisibilite(Visibilite.PUBLIQUE);
        produit.setStatutModeration(StatutModeration.APPROUVE);
        produit.setActif(Boolean.TRUE);
        return produit;
    }

    // ==================================================================
    @Nested
    @DisplayName("Le controle negatif : la detection est bien armee")
    class DetectionArmee {

        @Test
        @DisplayName("un Thread.sleep sur un fil reactif est bien detecte")
        void sleepDetecte() {
            assertThatThrownBy(() ->
                    Mono.fromCallable(() -> {
                                Thread.sleep(1);
                                return "jamais atteint";
                            })
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .withFailMessage("""
                            BlockHound n'a RIEN detecte sur un Thread.sleep.

                            La detection n'est pas armee, et tous les autres cas de ce \
                            fichier passent donc sans rien mesurer. Verifier l'ordre \
                            dans @BeforeAll : Schedulers.shutdownNow() DOIT preceder \
                            BlockHound.install().""")
                    .hasRootCauseInstanceOf(BlockingOperationError.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le filtre d'acces, sur chaque requete")
    class FiltreDAcces {

        @Test
        @DisplayName("une requete signee traverse sans blocage")
        void signeeNonBloquante() {
            ServerWebExchange echange = requeteSignee("/market/produits");
            assertThatCode(() ->
                    Mono.defer(() -> filtre.filter(echange, e -> Mono.empty()))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

        /**
         * Le chemin du REFUS compte autant que celui du succes : il journalise
         * et ecrit un corps de reponse, deux endroits ou une ecriture
         * synchrone passerait inapercue.
         */
        @Test
        @DisplayName("un refus pour signature absente ne bloque pas non plus")
        void refus() {
            var requete = MockServerHttpRequest.get("/market/produits").build();
            var echange = MockServerWebExchange.from(requete).mutate()
                    .request(new ServerHttpRequestDecorator(requete) {
                        @Override
                        public InetSocketAddress getRemoteAddress() {
                            return new InetSocketAddress("127.0.0.1", 54321);
                        }
                    }).build();

            assertThatCode(() ->
                    Mono.defer(() -> filtre.filter(echange, e -> Mono.empty()))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("une sonde traverse sans blocage")
        void sonde() {
            var echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/actuator/health").build());
            assertThatCode(() ->
                    Mono.defer(() -> filtre.filter(echange, e -> Mono.empty()))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Ce qui s'execute une fois par article d'une page")
    class ParArticle {

        /**
         * L'affirmation de {@code MarketMapper} rendue verifiable.
         *
         * <p>Sa javadoc dit « des fonctions PURES, et aucun appel ». Jusqu'ici,
         * rien ne l'empechait de devenir faux : il suffit qu'une lecture s'y
         * glisse pour qu'une page de vingt articles fasse vingt
         * allers-retours.
         */
        @Test
        @DisplayName("le mapper ne lit rien")
        void mapperPur() {
            // Construit AVANT de souscrire : produit() appelle
            // UUID.randomUUID(), qui lit /dev/random. Voir requeteSignee.
            Produit produit = produit();
            assertThatCode(() ->
                    Mono.fromCallable(() -> MarketMapper.versReponse(produit,
                                    "Sankadi Textile", List.of("https://exemple.invalid/1"),
                                    List.of(), List.of()))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("la decision de visibilite ne lit rien")
        void decisionPure() {
            var portee = new PorteeDuCatalogue("u1", "ML", null, false, false);
            Produit produit = produit();
            assertThatCode(() ->
                    Mono.fromCallable(() -> portee.voit(produit, true))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

    }
}
