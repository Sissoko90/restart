package com.abdatytch.gateway_service;

import com.abdatytch.gateway_service.security.ClientIpResolver;
import com.abdatytch.gateway_service.security.IpBlacklistWebFilter;
import com.abdatytch.gateway_service.security.RedisRateLimitWebFilter;
import com.abdatytch.gateway_service.security.SecurityViolationRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Aucun appel bloquant sur le chemin chaud du Gateway.
 *
 * <h2>La lacune que ce test comble</h2>
 *
 * auth-service avait sa suite BlockHound depuis longtemps ; <b>gateway-service
 * n'en avait aucune</b> — alors que la dependance {@code blockhound} etait deja
 * declaree dans son {@code pom.xml}, en portee test, et simplement jamais
 * utilisee.
 *
 * <p>C'est le module qui porte <b>tout</b> le trafic de la plateforme : chaque
 * requete traverse ses filtres avant d'atteindre un service. Un seul appel
 * bloquant y immobiliserait un thread d'EventLoop pour l'ensemble du trafic, et
 * non pour un service.
 *
 * <p>La relecture disait que tout allait bien — aucun {@code .block()}, aucun
 * {@code Thread.sleep} dans le module. Mais « verifie par relecture » et
 * « verifie » ne sont pas la meme chose : c'est exactement ce que ce depot a
 * appris en trouvant, par mesure, douze protections ecrites et jamais
 * atteintes.
 *
 * <h2>Ce que BlockHound ne verra pas</h2>
 *
 * Il instrumente les appels <b>bloquants</b> : entrees-sorties,
 * {@code Thread.sleep}, attentes de verrou. Il ne voit <b>pas</b> le calcul.
 * Une verification de signature RSA par requete, par exemple, reste invisible
 * ici — c'est la meme limite qui laissait passer les 241 ms de BCrypt
 * d'auth-service, et pour laquelle existe le garde-fou de source
 * {@code HachageHorsEventLoopTest}.
 */
@DisplayName("Absence d'appels bloquants sur le chemin chaud du Gateway")
class NoBlockingCallsTest {

    /**
     * Remet les ordonnanceurs a neuf, <b>puis</b> installe BlockHound.
     *
     * <p>Reactor met l'ordonnanceur {@code parallel} en cache pour toute la
     * JVM, a sa premiere utilisation. Si cette premiere utilisation tombe dans
     * une fenetre ou la fabrique globale d'ordonnanceurs a ete remplacee —
     * {@code StepVerifier.withVirtualTime} le fait, le temps d'une
     * verification — le cache retient un ordonnanceur dont les fils sont de
     * simples {@code Thread}, sans le marqueur {@code NonBlocking} sur lequel
     * repose toute la detection. Le cache survit ensuite au retablissement de
     * la fabrique.
     *
     * <p>Consequence observee dans auth-service : le controle negatif echouait
     * deux fois sur trois en suite complete, et passait toujours en isolation.
     * Les deux vrais tests de non-blocage passaient alors <b>pour la mauvaise
     * raison</b>, sans rien verifier.
     *
     * <p>{@code shutdownNow()} vide ce cache : les ordonnanceurs suivants sont
     * crees par la vraie fabrique, quelle que soit l'histoire de la JVM avant
     * cette classe.
     */
    @BeforeAll
    static void installerBlockHound() {
        Schedulers.shutdownNow();
        // Le chargement de classes est autorise. BlockHound arme la detection
        // pour TOUTE la JVM, donc pour les classes de test qui suivent dans le
        // meme fork Surefire. Sans cette autorisation, un chargement de classe
        // paresseux depuis un JAR — RandomAccessFile#readBytes via
        // JarFile.getEntry — fait echouer un test qui n'a rien a voir avec le
        // non-blocage.
        //
        // Observe en integration continue sur media-service : quinze requetes
        // en 500. Et le defaut depend de l'ORDRE des classes de test, donc de
        // l'ordre de balayage du systeme de fichiers : vert sur macOS, rouge
        // sur Linux, a code identique. Voir le javadoc de
        // media-service/NoBlockingCallsTest pour le detail.
        BlockHound.install(builder ->
                builder.allowBlockingCallsInside("java.lang.ClassLoader", "loadClass"));
    }

    private ReactiveRedisTemplate<String, String> redis;
    private ClientIpResolver resolveurDIp;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void preparer() {
        redis = Mockito.mock(ReactiveRedisTemplate.class);
        ReactiveValueOperations<String, String> valeurs =
                Mockito.mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);
        when(valeurs.get(anyString())).thenReturn(Mono.empty());
        when(valeurs.increment(anyString())).thenReturn(Mono.just(1L));
        when(valeurs.set(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(redis.expire(anyString(), any())).thenReturn(Mono.just(true));
        when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofMinutes(1)));
        when(redis.hasKey(anyString())).thenReturn(Mono.just(false));

        resolveurDIp = Mockito.mock(ClientIpResolver.class);
        when(resolveurDIp.resolve(any())).thenReturn("41.223.10.5");
    }

    // ==================================================================
    @Nested
    @DisplayName("Controle negatif")
    class ControleNegatif {

        /**
         * Sans ce controle, un test « aucun blocage detecte » ne prouverait
         * rien : il pourrait simplement signifier que BlockHound n'est pas
         * installe.
         *
         * <p>Ce depot a paye deux fois un harnais vert qui ne mesurait plus
         * rien.
         */
        @Test
        @DisplayName("BlockHound est bien actif sur ce module")
        void blockHoundEstActif() {
            Mono<Long> bloquant = Mono.fromCallable(() -> {
                Thread.sleep(10);
                return 1L;
            }).subscribeOn(Schedulers.parallel());

            assertThatThrownBy(() -> bloquant.block(Duration.ofSeconds(5)))
                    .hasRootCauseInstanceOf(BlockingOperationError.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le chemin chaud, filtre par filtre")
    class CheminChaud {

        /**
         * Les deux filtres qui consultent Redis a chaque requete.
         *
         * <p>Ce sont eux ou un {@code RedisTemplate} bloquant se glisserait le
         * plus naturellement : l'API bloquante et l'API reactive portent des
         * noms voisins, et la bloquante est celle que la plupart des exemples
         * montrent.
         */
        @Test
        @DisplayName("liste noire d'IP et limitation de debit ne bloquent pas")
        void filtresRedisNeBloquentPas() throws Exception {
            var enregistreur = new SecurityViolationRecorder(redis, new SimpleMeterRegistry());
            List<WebFilter> chaine = List.of(
                    new IpBlacklistWebFilter(redis, resolveurDIp),
                    new RedisRateLimitWebFilter(redis, resolveurDIp)
                            .avecEnregistreur(enregistreur));

            traverser(chaine, MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/auth-sdk/profile")
                            .header("X-Forwarded-For", "41.223.10.5")));
        }

        /**
         * Le scanner de corps lit la requete entiere en memoire.
         *
         * <p>C'est le filtre le plus expose a une lecture bloquante : il
         * assemble le corps. {@code DataBufferUtils.join} est reactif, mais une
         * reecriture « plus simple » avec un {@code InputStream} ne le serait
         * pas — et BlockHound est le seul a pouvoir le dire.
         */
        @Test
        @DisplayName("le scan du corps ne bloque pas")
        void scanDuCorpsNeBloquePas() throws Exception {
            List<WebFilter> chaine = List.of(
                    new com.abdatytch.gateway_service.security.BodyXssScannerWebFilter(
                            resolveurDIp));

            traverser(chaine, MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/auth-sdk/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"phoneNumber\":\"+22370000000\"}")));
        }

        @Test
        @DisplayName("un corps hostile, donc le chemin de REFUS, ne bloque pas non plus")
        void cheminDeRefusNeBloquePas() throws Exception {
            // Le chemin d'erreur est celui qu'on oublie de mesurer, et c'est
            // celui qui s'execute en masse pendant une attaque — donc le pire
            // moment pour immobiliser un EventLoop.
            var enregistreur = new SecurityViolationRecorder(redis, new SimpleMeterRegistry());
            List<WebFilter> chaine = List.of(
                    new com.abdatytch.gateway_service.security.BodyXssScannerWebFilter(
                            resolveurDIp).avecEnregistreur(enregistreur));

            traverser(chaine, MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/auth-sdk/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"x\":\"<script>alert(1)</script>\"}")));
        }

        @Test
        @DisplayName("la traversee d'un chemin public ne bloque pas")
        void cheminPublicNeBloquePas() throws Exception {
            List<WebFilter> chaine = List.of(
                    new com.abdatytch.gateway_service.security.PathTraversalWebFilter(
                            resolveurDIp),
                    new IpBlacklistWebFilter(redis, resolveurDIp));

            traverser(chaine, MockServerWebExchange.from(
                    MockServerHttpRequest.method(HttpMethod.GET, "/actuator/health")));
        }
    }

    /**
     * Traverse la chaine sur {@code Schedulers.parallel()}.
     *
     * <p>C'est le scheduler que BlockHound surveille, au meme titre que
     * l'EventLoop Netty en production. Souscrire depuis le thread de test ne
     * prouverait rien : un blocage y est permis.
     */
    private void traverser(List<WebFilter> filtres, ServerWebExchange echange)
            throws Exception {
        WebFilterChain suite = exchange -> Mono.empty();
        for (int i = filtres.size() - 1; i >= 0; i--) {
            WebFilter filtre = filtres.get(i);
            WebFilterChain suivante = suite;
            suite = exchange -> filtre.filter(exchange, suivante);
        }

        CompletableFuture<Object> resultat = new CompletableFuture<>();
        WebFilterChain chaine = suite;
        // Mono.defer EST la correction, pas une precaution.
        //
        // Sans lui, `chaine.filter(echange)` s'execute sur le thread de TEST :
        // le corps des methodes `filter(...)` tourne a l'ASSEMBLAGE, sur
        // `main`, ou bloquer est permis. Seule la souscription allait sur
        // `parallel`.
        //
        // Mesure par mutation : un `Thread.sleep(5)` injecte en tete de
        // IpBlacklistWebFilter.filter SURVIVAIT a la premiere version de ce
        // test. Il ne verifiait donc presque rien — la forme exacte du harnais
        // vert que ce depot a deja paye deux fois.
        //
        // Avec defer, l'assemblage ET la souscription ont lieu sur le
        // scheduler surveille.
        Mono.defer(() -> chaine.filter(echange))
                .subscribeOn(Schedulers.parallel())
                .subscribe(resultat::complete, resultat::completeExceptionally,
                        () -> resultat.complete(null));

        // Une BlockingOperationError remonterait ici comme cause de l'echec.
        resultat.get(10, TimeUnit.SECONDS);
        assertThat(echange).isNotNull();
    }
}
