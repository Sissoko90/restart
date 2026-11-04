package com.abdatytch.security_service;

import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.security_service.security.JwtAuthenticationWebFilter;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vérifie que la validation d'un jeton ne bloque aucun fil réactif.
 *
 * <h2>Pourquoi ce test existe, et ce qu'il aurait attrapé</h2>
 *
 * Ce module n'avait aucun test BlockHound, alors que la politique du dépôt est
 * « zéro blocage sur le chemin d'une requête » — voir
 * {@code docs/NON-BLOCKING.md}.
 *
 * <p>Et le défaut était réel : {@code Jwts.parser()...build()} était appelé à
 * <b>chaque requête</b> portant un jeton. Or {@code build()} de jjwt fait une
 * recherche {@link java.util.ServiceLoader}, qui lit des JAR sur le disque :
 *
 * <pre>
 * BlockingOperationError: Blocking call! java.io.RandomAccessFile#readBytes
 *   at java.util.ServiceLoader$LazyClassPathLookupIterator.parse
 *   at io.jsonwebtoken.impl.lang.Services.loadFirst
 *   at io.jsonwebtoken.impl.DefaultJwtParserBuilder.build
 * </pre>
 *
 * <p>Il a fallu que l'integration continue le signale sur <b>media-service</b>
 * pour qu'on le trouve — et il etait present dans huit composants, dont
 * celui-ci.
 *
 * <h2>Ce que ce test NE garde PAS — mesure, pas suppose</h2>
 *
 * Il ne detecte <b>pas</b> ce defaut-la. Verifie par mutation : en remettant la
 * construction de l'analyseur dans le chemin de la requete, ces quatre tests
 * passent quand meme.
 *
 * <p>La raison tient au cache de jjwt : {@code Services} retient le resultat de
 * sa recherche pour toute la <b>JVM</b>. Le {@code Jwts.builder()} du
 * {@code @BeforeEach} — celui qui fabrique le jeton d'essai, sur le fil
 * principal — rechauffe ce cache avant la premiere souscription reactive. La
 * lecture disque a donc deja eu lieu, ailleurs, et BlockHound n'a plus rien a
 * voir.
 *
 * <p>C'est exactement le mecanisme qui a rendu le defaut invisible en local :
 * la detection dependait d'un cache global reste froid. L'invariant est donc
 * garde sur la <b>forme du code</b>, par
 * {@code platform-commons/AnalyseurJwtConstruitUneFoisTest}, qui echoue bien
 * sur la mutation.
 *
 * <h2>Ce que ce test garde reellement</h2>
 *
 * Tout autre appel bloquant introduit dans ce filtre — lecture de fichier,
 * appel JDBC, {@code Thread.sleep}, journalisation synchrone — sur le chemin du
 * succes comme sur celui du refus. BlockHound surveille les fils de
 * {@code Schedulers.parallel()} au meme titre que les EventLoop de Netty :
 * souscrire la exerce la condition de production.
 *
 * <p>Et le controle negatif garantit que la detection est bien armee, sans quoi
 * les trois autres cas passeraient sans rien mesurer.
 */
@DisplayName("security-service : absence d'appels bloquants (BlockHound)")
class NoBlockingCallsTest {

    private static final String EMETTEUR = "interface-service";
    private static final String AUDIENCE = "security-service";
    private static final Duration DELAI = Duration.ofSeconds(30);

    /** Engendree une fois : une paire RSA coute cher, et son contenu importe peu. */
    private static KeyPair paireDeCles;

    private JwtAuthenticationWebFilter filtre;
    private String jeton;

    /**
     * Remet les ordonnanceurs a neuf, <b>puis</b> installe BlockHound.
     *
     * <p>Reactor met l'ordonnanceur {@code parallel} en cache pour toute la JVM,
     * a sa premiere utilisation. Si cette premiere utilisation tombe avant
     * l'installation de BlockHound, le cache retient des fils qui ne portent pas
     * le marqueur {@code NonBlocking} sur lequel repose toute la detection — et
     * les tests passent alors <b>pour la mauvaise raison</b>, sans rien
     * verifier.
     *
     * <p>{@code shutdownNow()} vide ce cache. La precaution vient de
     * {@code auth-service}, ou le controle negatif echouait deux fois sur trois
     * en suite complete et passait toujours en isolation.
     */
    @BeforeAll
    static void installerBlockHound() {
        paireDeCles = Jwts.SIG.RS256.keyPair().build();
        Schedulers.shutdownNow();
        BlockHound.install();
    }

    @BeforeEach
    void preparer() {
        JwksKeyProvider jwks = Mockito.mock(JwksKeyProvider.class);
        Mockito.when(jwks.getDefaultPublicKey())
                .thenReturn((RSAPublicKey) paireDeCles.getPublic());
        Mockito.when(jwks.keyLocator()).thenReturn(entete -> paireDeCles.getPublic());

        filtre = new JwtAuthenticationWebFilter(jwks);
        // Les @Value ne sont pas injectes hors du conteneur, et l'analyseur est
        // construit par @PostConstruct : sans ces trois lignes, il resterait nul
        // et le test mesurerait un refus au lieu d'une validation.
        ReflectionTestUtils.setField(filtre, "expectedIssuer", EMETTEUR);
        ReflectionTestUtils.setField(filtre, "expectedAudience", AUDIENCE);
        ReflectionTestUtils.invokeMethod(filtre, "construireLAnalyseur");

        jeton = Jwts.builder()
                .subject("11111111-1111-1111-1111-111111111111")
                .issuer(EMETTEUR)
                .audience().add(AUDIENCE).and()
                .claim("roles", List.of("ADMIN"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(paireDeCles.getPrivate())
                .compact();
    }

    @Nested
    @DisplayName("Le detecteur est bien arme")
    class DetecteurArme {

        /**
         * Controle negatif, et il est indispensable.
         *
         * <p>Sans lui, les tests suivants passeraient aussi bien si BlockHound
         * n'etait pas installe — et on croirait avoir verifie l'absence de
         * blocage alors qu'on n'aurait rien mesure. C'est la forme la plus
         * trompeuse d'un test vert.
         */
        @Test
        @DisplayName("Un appel bloquant volontaire EST detecte")
        void appelBloquantDetecte() {
            assertThatThrownBy(() ->
                    Mono.fromCallable(() -> {
                                Thread.sleep(1);
                                return "jamais atteint";
                            })
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .hasCauseInstanceOf(BlockingOperationError.class);
        }
    }

    @Nested
    @DisplayName("La validation d'un jeton ne bloque pas")
    class ValidationNonBloquante {

        @Test
        @DisplayName("Un jeton valide traverse le filtre sans appel bloquant")
        void jetonValide() {
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.method(HttpMethod.GET, "/security-admin/pays")
                            .header("Authorization", "Bearer " + jeton));

            assertThatCode(() ->
                    filtre.filter(echange, exchange -> Mono.empty())
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

        /**
         * Le chemin de REFUS compte autant que celui du succes.
         *
         * <p>Un jeton illisible emprunte la branche d'erreur du filtre. Si cette
         * branche bloquait — journalisation synchrone, lecture de configuration —
         * le defaut ne se verrait que sous attaque, au moment ou la plateforme
         * est deja sollicitee.
         */
        @Test
        @DisplayName("Un jeton invalide est refuse sans appel bloquant")
        void jetonInvalide() {
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.method(HttpMethod.GET, "/security-admin/pays")
                            .header("Authorization", "Bearer pas-un-jeton"));

            assertThatCode(() ->
                    filtre.filter(echange, exchange -> Mono.empty())
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

        /**
         * Le cas qui portait le defaut.
         *
         * <p>Plusieurs validations d'affilee : avec l'ancien code, chacune
         * reconstruisait un analyseur, et la <b>premiere</b> payait la lecture
         * disque du {@code ServiceLoader}. Boucler rend le test insensible a
         * l'ordre dans lequel les classes de test rechauffent ce cache — c'est
         * precisement cette dependance a l'ordre qui a rendu le defaut
         * invisible en local pendant si longtemps.
         */
        @Test
        @DisplayName("Dix validations d'affilee ne bloquent jamais")
        void validationsRepetees() {
            assertThatCode(() -> {
                for (int tour = 0; tour < 10; tour++) {
                    MockServerWebExchange echange = MockServerWebExchange.from(
                            MockServerHttpRequest.method(HttpMethod.GET, "/security-admin/ip")
                                    .header("Authorization", "Bearer " + jeton));

                    filtre.filter(echange, exchange -> Mono.empty())
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI);
                }
            }).doesNotThrowAnyException();
        }
    }
}
