package com.abdatytch.auth_service.client;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.service.ServiceTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les appels a l'autorite JWT sont bornes dans le temps.
 *
 * <h2>Le defaut que ces tests ferment</h2>
 *
 * Les trois appels de {@link InterfaceServiceClient} n'avaient <b>aucun</b>
 * delai maximal, alors que les trois autres clients sortants du service
 * ({@code AfrikSmsProvider}, {@code AfrikSmsAdministration},
 * {@code GatewayGeoLocationClient}) portaient tous le leur. Celui du chemin
 * critique etait le seul sans.
 *
 * <p>interface-service signe les jetons de toute la plateforme : il est sur le
 * chemin de <b>chaque</b> connexion. Une instance <b>figee</b> — pas tombee,
 * figee, qui est le cas le plus courant et le plus traitre — retenait
 * indefiniment la chaine reactive appelante. Les connexions s'accumulaient sans
 * jamais aboutir ni echouer, et rien ne distinguait cet etat d'une lenteur
 * passagere.
 *
 * <h2>Pourquoi un {@code Mono.never()} et non un vrai serveur</h2>
 *
 * Une dependance figee se simule exactement par une reponse qui n'arrive
 * jamais. Un serveur d'essai lent introduirait une attente reelle et rendrait le
 * test fragile ; {@code Mono.never()} est deterministe, et le seul temps ecoule
 * est celui du delai qu'on verifie.
 */
@DisplayName("InterfaceServiceClient — les appels a l'autorite JWT sont bornes")
class InterfaceServiceClientTest {

    /** Court, pour que la suite reste rapide tout en exerçant le vrai chemin. */
    private static final long DELAI_SECONDES = 1;

    /** Fenetre et seuil du disjoncteur, petits pour un test lisible. */
    private static final int FENETRE = 4;
    private static final int APPELS_MINIMUM = 2;

    /**
     * Disjoncteur REEL, sur un registre neuf a chaque appel.
     *
     * <p>Pas un bouchon : c'est l'operateur qui compte les echecs et refuse
     * quand il est ouvert, donc le simuler reviendrait a ne rien verifier. Un
     * registre neuf par client evite qu'un test laisse un disjoncteur ouvert
     * pour le suivant — ce serait un etat partage entre tests, c'est-a-dire
     * l'ordre d'execution comme condition de reussite.
     */
    private static com.abdatytch.auth_service.service.IDisjoncteurSortant disjoncteurReel() {
        return new com.abdatytch.auth_service.service.impl.DisjoncteurSortantServiceImpl(
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(),
                50f, FENETRE, APPELS_MINIMUM, 30L);
    }

    /** Une dependance FIGEE : la reponse n'arrive jamais. */
    private static InterfaceServiceClient clientFige(ServiceTokenProvider fournisseur) {
        return clientFige(fournisseur, disjoncteurReel());
    }

    private static InterfaceServiceClient clientFige(
            ServiceTokenProvider fournisseur,
            com.abdatytch.auth_service.service.IDisjoncteurSortant disjoncteur) {
        WebClient.Builder constructeur = WebClient.builder()
                .exchangeFunction(requete -> Mono.never());
        InterfaceServiceClient client = new InterfaceServiceClient(
                constructeur, fournisseur, disjoncteur, DELAI_SECONDES);
        // url et secret viennent de @Value, qu'aucune construction manuelle ne
        // renseigne — le depot s'est deja fait prendre par ce piege.
        ReflectionTestUtils.setField(client, "interfaceServiceUrl", "http://interface.invalid");
        ReflectionTestUtils.setField(client, "internalSecret", "secret-de-test");
        return client;
    }

    private static ServiceTokenProvider fournisseurImmediat() {
        ServiceTokenProvider fournisseur = Mockito.mock(ServiceTokenProvider.class);
        Mockito.when(fournisseur.generateServiceToken()).thenReturn("jeton-de-service");
        return fournisseur;
    }

    // ==================================================================
    @Nested
    @DisplayName("Une dependance figee ne retient plus l'appelant")
    class DependanceFigee {

        @Test
        @DisplayName("la signature de jeton echoue au delai, et non jamais")
        void signatureBornee() {
            verifierBorne(client -> client.signUserToken("u-1", "p-1", "a@b.test", "u",
                    List.of("USER"), Map.of()));
        }

        @Test
        @DisplayName("le rafraichissement est borne aussi")
        void rafraichissementBorne() {
            verifierBorne(client -> client.refreshToken("jeton-de-rafraichissement"));
        }

        @Test
        @DisplayName("la verification est bornee aussi")
        void verificationBornee() {
            // Les trois appels, et pas seulement le premier : un delai pose sur
            // un seul des trois laisserait les deux autres retenir l'appelant.
            verifierBorne(client -> client.verifyToken("jeton"));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le delai couvre la chaine ENTIERE")
    class ChaineEntiere {

        /**
         * Le delai doit enfermer la generation du jeton de service, pas seulement
         * l'appel HTTP.
         *
         * <h4>Pourquoi un garde-fou de SOURCE ici</h4>
         *
         * Simuler une generation de jeton figee demande un
         * {@code Thread.sleep} dans le fournisseur, puisque
         * {@code generateServiceToken} enveloppe un appel <b>synchrone</b>. Or
         * {@code NoBlockingCallsTest} installe BlockHound pour toute la JVM, et
         * BlockHound transforme ce sommeil en {@code Error} fatale : Reactor la
         * releve sur le thread du minuteur au lieu de la propager, le
         * souscripteur ne reçoit jamais rien, et le test expire.
         *
         * <p>Mesure : la version comportementale de ce test passait seule et
         * echouait dans la suite complete — 792 tests verts sans
         * {@code NoBlockingCallsTest}, quatre expirations avec. Un test qui ne
         * vaut que seul ne vaut rien.
         *
         * <p>La propriete se verifie donc sur la source : {@code borner} doit
         * envelopper {@code generateServiceToken()}, et non l'inverse.
         */
        @Test
        @DisplayName("borner enveloppe la generation du jeton, pas seulement l'appel HTTP")
        void bornerEnveloppeToutAppel() throws java.io.IOException {
            java.nio.file.Path source = java.nio.file.Path.of(
                    "src/main/java/com/abdatytch/auth_service/client/InterfaceServiceClient.java");
            assertThat(source)
                    .withFailMessage("source introuvable : ce garde-fou serait vert sans "
                            + "rien verifier")
                    .exists();

            String contenu = java.nio.file.Files.readString(source);

            // Trois appels, trois enveloppes qui commencent par la generation.
            assertThat(contenu.split("borner\\(generateServiceToken\\(\\)", -1).length - 1)
                    .withFailMessage("""
                            Les trois appels doivent etre de la forme \
                            borner(generateServiceToken()...).

                            La forme inverse — generateServiceToken().flatMap(t -> \
                            borner(appel)) — laisse une signature lente hors du delai : \
                            la chaine reste non bornee par un autre chemin que celui \
                            qu'on vient de corriger.""")
                    .isEqualTo(3);
        }
    }


    // ==================================================================
    @Nested
    @DisplayName("Le disjoncteur arrete d'essayer")
    class Disjoncteur {

        /**
         * Ce que le disjoncteur apporte en plus du delai maximal.
         *
         * <p>Avec le delai seul, chaque connexion attend une seconde avant
         * d'echouer — pour un resultat connu d'avance. Le disjoncteur retient
         * l'information : apres {@code APPELS_MINIMUM} echecs, il refuse
         * <b>sans tenter</b>.
         *
         * <p>La verification porte sur le fait que l'appel ne consomme plus de
         * temps : en temps virtuel, un appel refuse par le disjoncteur echoue
         * <b>sans</b> qu'on ait besoin d'avancer l'horloge.
         */
        @Test
        @DisplayName("apres assez d'echecs, l'appel est refuse SANS attendre le delai")
        void refusImmediatApresOuverture() {
            var disjoncteur = disjoncteurReel();

            // On provoque les echecs necessaires a l'ouverture.
            for (int i = 0; i < APPELS_MINIMUM; i++) {
                StepVerifier.withVirtualTime(() ->
                                clientFige(fournisseurImmediat(), disjoncteur).verifyToken("j"))
                        .expectSubscription()
                        .thenAwait(Duration.ofSeconds(DELAI_SECONDES + 1))
                        .expectErrorSatisfies(InterfaceServiceClientTest::estIndisponible)
                        .verify(Duration.ofSeconds(10));
            }

            // Celui-ci doit echouer SANS avancee d'horloge : pas d'appel, donc
            // pas de delai a attendre. Si le disjoncteur n'etait pas la, ce
            // verify expirerait.
            StepVerifier.withVirtualTime(() ->
                            clientFige(fournisseurImmediat(), disjoncteur).verifyToken("j"))
                    .expectSubscription()
                    .expectErrorSatisfies(InterfaceServiceClientTest::estIndisponible)
                    .verify(Duration.ofSeconds(10));
        }

        /**
         * Un disjoncteur ouvert ne doit pas rendre 500.
         *
         * <p>{@code CallNotPermittedException} remontee brute serait classee en
         * defaillance serveur par {@code GlobalExceptionHandler} — accusant ce
         * service alors qu'il vient justement de se proteger correctement.
         */
        @Test
        @DisplayName("un disjoncteur ouvert rend une indisponibilite, pas une erreur interne")
        void ouvertRendIndisponibilite() {
            var disjoncteur = disjoncteurReel();
            for (int i = 0; i < APPELS_MINIMUM + 1; i++) {
                StepVerifier.withVirtualTime(() ->
                                clientFige(fournisseurImmediat(), disjoncteur).verifyToken("j"))
                        .expectSubscription()
                        .thenAwait(Duration.ofSeconds(DELAI_SECONDES + 1))
                        .expectError()
                        .verify(Duration.ofSeconds(10));
            }

            StepVerifier.withVirtualTime(() ->
                            clientFige(fournisseurImmediat(), disjoncteur).verifyToken("j"))
                    .expectSubscription()
                    .expectErrorSatisfies(InterfaceServiceClientTest::estIndisponible)
                    .verify(Duration.ofSeconds(10));
        }

    }

    /**
     * Verifie qu'un appel echoue bien au delai, en TEMPS VIRTUEL.
     *
     * <h4>Pourquoi le temps virtuel</h4>
     *
     * {@code Mono.timeout} s'appuie sur {@code Schedulers.parallel()}. En temps
     * reel, ce test dormait une seconde par appel — et surtout il echouait dans
     * la suite complete : {@code NoBlockingCallsTest} installe BlockHound pour
     * toute la JVM, et le minuteur n'aboutissait plus.
     *
     * <p>{@code withVirtualTime} remplace l'ordonnanceur : le delai est franchi
     * sans attente et sans dependre de l'etat global de la JVM. C'est la
     * convention deja etablie dans {@code AfrikSmsProviderTest}, pour cette
     * raison exactement.
     *
     * <p>Le client est construit <b>dans</b> le fournisseur : l'ordonnanceur
     * virtuel doit etre en place a l'assemblage de la chaine, pas apres.
     */
    private static void verifierBorne(
            java.util.function.Function<InterfaceServiceClient, Mono<?>> appel) {
        StepVerifier.withVirtualTime(() -> appel.apply(clientFige(fournisseurImmediat())))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(DELAI_SECONDES + 1))
                .expectErrorSatisfies(InterfaceServiceClientTest::estIndisponible)
                .verify(Duration.ofSeconds(10));
    }

    /**
     * L'echec doit etre NOMME, pas un {@code TimeoutException} nu.
     *
     * <p>Un {@code TimeoutException} remonterait en 500, ce qui designe un
     * defaut de ce service. {@code SERVICE_UNAVAILABLE} dit la verite : c'est
     * la dependance qui ne repond pas, et l'appelant peut reessayer plus tard.
     */
    private static void estIndisponible(Throwable erreur) {
        assertThat(erreur)
                .withFailMessage("l'echec remonte brut : un TimeoutException nu devient "
                        + "un 500, qui accuse ce service au lieu de sa dependance")
                .isInstanceOf(AuthException.class);
        assertThat(((AuthException) erreur).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }
}
