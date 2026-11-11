package com.abdatytch.notification_service;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.notification_service.repositories.SurchargeDeModeleRepository;
import com.abdatytch.notification_service.security.GatewayAccessWebFilter;
import com.abdatytch.notification_service.service.impl.MoteurDeModeles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifie que le chemin d'une requete ne bloque aucun fil reactif.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * Ce module n'avait aucun test BlockHound, alors que la politique du depot est
 * « zero blocage sur le chemin d'une requete » — voir
 * {@code docs/NON-BLOCKING.md}. La dependance etait pourtant deja declaree dans
 * son POM : la capacite etait la, personne ne s'en servait.
 *
 * <h2>Ce qui est exerce, et pourquoi ces deux-la</h2>
 *
 * <ol>
 *   <li>{@link GatewayAccessWebFilter} — il s'execute sur <b>chaque</b>
 *       requete, avant tout le reste, et calcule un HMAC. Un appel bloquant
 *       introduit ici couterait a toutes les requetes du service, y compris
 *       celles qu'il refuse.</li>
 *   <li>{@link MoteurDeModeles#rendre} — appele une fois par notification
 *       AFFICHEE dans la boite de reception, depuis un mapper synchrone, et une
 *       fois par canal vise dans le chemin de remise. Le faire lire la base ou
 *       un fichier y ferait une lecture bloquante par ligne d'une page.</li>
 * </ol>
 *
 * <p>Le moteur est le cas le plus instructif : sa javadoc explique qu'il tient
 * les surcharges en MEMOIRE precisement pour eviter une requete par
 * notification affichee. C'est une affirmation sur le comportement a
 * l'execution, et jusqu'ici rien ne la verifiait.
 *
 * <h2>Ce qui n'est PAS exerce, et c'est delibere</h2>
 *
 * Les canaux de remise. {@code CanalCourriel} parle SMTP, qui est bloquant par
 * nature : il s'execute sur {@code boundedElastic}, dont c'est le role. L'y
 * surveiller ferait echouer un test sur du code correct — l'erreur inverse,
 * aussi couteuse, car elle fait corriger ce qui va bien.
 *
 * <h2>Le controle negatif n'est pas une formalite</h2>
 *
 * Sans lui, un BlockHound mal arme laisserait tous les autres cas passer
 * <b>sans rien mesurer</b>. C'est le seul test de ce fichier dont l'echec
 * signifie « la detection est morte » plutot que « le code a regresse ».
 */
@DisplayName("notification-service : absence d'appels bloquants (BlockHound)")
class NoBlockingCallsTest {

    private static final String SECRET = "secret-de-passerelle-pour-test";
    private static final Duration DELAI = Duration.ofSeconds(30);

    private GatewayAccessWebFilter filtre;
    private MoteurDeModeles moteur;

    /**
     * Remet les ordonnanceurs a neuf, <b>puis</b> installe BlockHound.
     *
     * <p>Reactor met l'ordonnanceur {@code parallel} en cache pour toute la JVM,
     * a sa premiere utilisation. Si cette premiere utilisation tombe avant
     * l'installation de BlockHound, le cache retient des fils qui ne portent pas
     * le marqueur {@code NonBlocking} sur lequel repose toute la detection — et
     * les tests passent alors <b>pour la mauvaise raison</b>.
     *
     * <p>{@code shutdownNow()} vide ce cache. La precaution vient
     * d'auth-service, ou le controle negatif echouait deux fois sur trois en
     * suite complete et passait toujours en isolation.
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
        // ce soit, et le test mesurerait un refus precoce au lieu du chemin
        // complet.
        ReflectionTestUtils.setField(filtre, "secretDePasserelle", SECRET);

        SurchargeDeModeleRepository sansSurcharge =
                Mockito.mock(SurchargeDeModeleRepository.class);
        Mockito.when(sansSurcharge.findAll()).thenReturn(Flux.empty());
        moteur = new MoteurDeModeles(
                new ClassPathResource("modeles-notification.properties"), sansSurcharge);
    }

    /**
     * Une requete signee exactement comme la passerelle la signe.
     *
     * <h3>A CONSTRUIRE HORS DU FIL REACTIF</h3>
     *
     * {@code UUID.randomUUID()} lit {@code /dev/random} par
     * {@code NativePRNG}, donc {@code FileInputStream#readBytes}. Appelee a
     * l'interieur du {@code Mono.defer}, cette methode s'executait sur le fil
     * surveille et BlockHound signalait un blocage — venu du test, pas du code.
     *
     * <p>En production, l'identifiant de requete est engendre par la
     * PASSERELLE et arrive dans un en-tete : ce service ne tire jamais
     * d'aleatoire sur le chemin d'une requete. Mesurer l'inverse aurait fait
     * corriger du code correct, l'erreur la plus couteuse de ce genre de test.
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

        // Le pair TCP, que le mock ne fournit pas. Sans lui le controle
        // d'adresse refuse avant la signature, et le test n'exercerait rien.
        var echange = MockServerWebExchange.from(requete);
        return echange.mutate().request(new ServerHttpRequestDecorator(requete) {
            @Override
            public InetSocketAddress getRemoteAddress() {
                return new InetSocketAddress("127.0.0.1", 54321);
            }
        }).build();
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

                            La detection n'est pas armee, et tous les autres cas de
                            ce fichier passent donc sans rien mesurer. Verifier
                            l'ordre dans @BeforeAll : Schedulers.shutdownNow() DOIT
                            preceder BlockHound.install(), sinon l'ordonnanceur
                            parallel est deja en cache avec des fils non marques.""")
                    .hasRootCauseInstanceOf(BlockingOperationError.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le filtre d'acces, sur chaque requete")
    class FiltreDAcces {

        @Test
        @DisplayName("une requete signee traverse le filtre sans blocage")
        void requeteSigneeNonBloquante() {
            // Construite ICI, sur le fil principal : voir requeteSignee.
            ServerWebExchange echange = requeteSignee("/notifications");

            assertThatCode(() ->
                    Mono.defer(() -> filtre.filter(echange, e -> Mono.empty()))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

        /**
         * Le chemin du REFUS compte autant que celui du succes.
         *
         * <p>Il journalise, ecrit un corps de reponse et sort — trois endroits
         * ou une ecriture synchrone passerait inapercue, puisqu'on ne regarde
         * d'ordinaire que le chemin qui marche.
         */
        @Test
        @DisplayName("un refus pour signature absente ne bloque pas non plus")
        void refusNonBloquant() {
            var requete = MockServerHttpRequest.get("/notifications").build();
            var echange = MockServerWebExchange.from(requete);
            var avecPair = echange.mutate()
                    .request(new ServerHttpRequestDecorator(requete) {
                        @Override
                        public InetSocketAddress getRemoteAddress() {
                            return new InetSocketAddress("127.0.0.1", 54321);
                        }
                    }).build();

            assertThatCode(() ->
                    Mono.defer(() -> filtre.filter(avecPair, e -> Mono.empty()))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

        /**
         * Une sonde sort du filtre immediatement, sans calcul de signature.
         * C'est le chemin qu'emprunte Consul plusieurs fois par minute.
         */
        @Test
        @DisplayName("une sonde traverse sans blocage")
        void sondeNonBloquante() {
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
    @DisplayName("Le moteur de modeles, une fois par notification affichee")
    class MoteurDeModelesNonBloquant {

        /**
         * L'affirmation que ce test verifie enfin.
         *
         * <p>La javadoc du moteur explique que les surcharges sont tenues en
         * memoire precisement pour eviter « une requete par notification
         * affichee » — le defaut N+1, sur l'ecran le plus consulte du service.
         * C'etait une affirmation sur le comportement a l'execution, que rien
         * ne mesurait.
         */
        @Test
        @DisplayName("rendre() ne lit ni base ni fichier")
        void rendreNonBloquant() {
            assertThatCode(() ->
                    Mono.fromCallable(() -> moteur.rendre(
                                    "securite.code-de-verification",
                                    CanalDeNotification.COURRIEL,
                                    Map.of("code", "123456", "minutes", "10",
                                            "prenom", "Essai")))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

        /**
         * La variante HTML passe par l'echappement de chaque variable, donc par
         * plus de travail que la variante texte. Si une lecture devait s'y
         * glisser, ce serait la.
         */
        @Test
        @DisplayName("le rendu d'un corps HTML ne bloque pas davantage")
        void rendreHtmlNonBloquant() {
            assertThatCode(() ->
                    Mono.fromCallable(() -> moteur.rendre(
                                    "exploitation.connexion-admin",
                                    CanalDeNotification.COURRIEL,
                                    Map.of("agent", "<script>Mozilla</script>",
                                            "courriel", "essai@exemple.invalid")))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }

        /**
         * Un modele INTROUVABLE emprunte un autre chemin : il journalise un
         * avertissement et rend un Optional vide.
         */
        @Test
        @DisplayName("un modele introuvable ne bloque pas non plus")
        void modeleIntrouvableNonBloquant() {
            assertThatCode(() ->
                    Mono.fromCallable(() -> moteur.rendre(
                                    "modele.qui.n.existe.pas",
                                    CanalDeNotification.COURRIEL, Map.of()))
                            .subscribeOn(Schedulers.parallel())
                            .block(DELAI))
                    .doesNotThrowAnyException();
        }
    }
}
