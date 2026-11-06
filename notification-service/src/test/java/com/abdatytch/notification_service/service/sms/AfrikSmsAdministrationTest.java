package com.abdatytch.notification_service.service.sms;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.abdatytch.notification_service.exception.NotificationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les deux appels d'administration d'AfrikSMS.
 *
 * <p>Le solde est la seule fenêtre sur la panne la plus silencieuse de la
 * chaîne d'inscription : un crédit épuisé ne produit aucune erreur HTTP, les
 * envois répondent 200 avec un code de refus. Si cette lecture est fausse, elle
 * est pire que absente — elle rassure.
 */
@DisplayName("AfrikSmsAdministration")
class AfrikSmsAdministrationTest {

    private static final String URL_RAPPEL = "https://exemple.invalide/outbounds/callback_url";
    private static final String URL_SOLDE = "https://exemple.invalide/outbounds/solde";
    // Valeurs FACTICES : les identifiants reels vivent dans .env, hors de git.
    private static final String CLIENT_ID = "00000000";
    private static final String CLE_API = "cle-api-factice-pour-les-tests-0000";
    private static final String URL_DECLAREE =
            "https://passerelle.exemple.invalide/api/v1/sms/accuses/afriksms?cle=secret";

    private static final String CORPS_SOLDE = """
            {"code":100,"message":"Success operation","information":[
              {"country":"Burkina-Faso","solde":0},
              {"country":"Mali","solde":-2},
              {"country":"Togo","solde":1500}]}""";

    private final AtomicReference<URI> uriVue = new AtomicReference<>();

    private ListAppender<ILoggingEvent> journaux;
    private Logger loggerDuService;

    @BeforeEach
    void capturerLesJournaux() {
        journaux = new ListAppender<>();
        journaux.start();
        loggerDuService = (Logger) LoggerFactory.getLogger(AfrikSmsAdministration.class);
        loggerDuService.addAppender(journaux);
    }

    @AfterEach
    void relacherLesJournaux() {
        loggerDuService.detachAppender(journaux);
        journaux.stop();
    }

    private AfrikSmsAdministration administration(Mono<ClientResponse> reponse) {
        return administration(reponse, URL_DECLAREE, AfrikSmsAdministration.NOTIFICATION_PAR_POST);
    }

    private AfrikSmsAdministration administration(
            Mono<ClientResponse> reponse, String urlDeclaree, int typeNotification) {
        WebClient.Builder constructeur = WebClient.builder()
                .exchangeFunction(requete -> {
                    uriVue.set(requete.url());
                    return reponse;
                });
        return new AfrikSmsAdministration(constructeur, URL_RAPPEL, URL_SOLDE,
                CLIENT_ID, CLE_API, urlDeclaree, typeNotification, 5);
    }

    private static ClientResponse json(HttpStatus statut, String corps) {
        return ClientResponse.create(statut)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(corps)
                .build();
    }

    private List<String> journauxEmis() {
        return journaux.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String parametreDecode(String nom) {
        String brut = UriComponentsBuilder.fromUri(uriVue.get()).build()
                .getQueryParams().getFirst(nom);
        assertThat(brut).as("parametre %s absent", nom).isNotNull();
        return URLDecoder.decode(brut, StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("Consultation du solde")
    class ConsultationDuSolde {

        @Test
        @DisplayName("les soldes sont lus par pays")
        void soldesParPays() {
            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK, CORPS_SOLDE)))
                            .consulterSolde())
                    .assertNext(soldes -> assertThat(soldes).containsExactly(
                            new AfrikSmsAdministration.SoldeParPays("Burkina-Faso", 0),
                            new AfrikSmsAdministration.SoldeParPays("Mali", -2),
                            new AfrikSmsAdministration.SoldeParPays("Togo", 1500)))
                    .verifyComplete();
        }

        /**
         * L'exemple de la documentation AfrikSMS montre un solde à {@code -2}.
         *
         * <p>Un test {@code solde == 0} manquerait donc exactement le cas où le
         * crédit est déjà dépassé — c'est-à-dire celui où les SMS ne partent
         * plus.
         */
        @ParameterizedTest(name = "solde {0} critique = {1}")
        @CsvSource({"-2, true", "0, true", "1, false", "1500, false"})
        void unSoldeNegatifEstCritique(int solde, boolean critique) {
            var parPays = new AfrikSmsAdministration.SoldeParPays("Mali", solde);
            assertThat(parPays.estCritique()).isEqualTo(critique);
        }

        @Test
        @DisplayName("l'URI porte le ClientId et l'ApiKey, et rien d'autre")
        void uriDuSolde() {
            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK, CORPS_SOLDE)))
                            .consulterSolde())
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(UriComponentsBuilder.fromUri(uriVue.get()).build().getQueryParams()
                    .keySet()).containsExactlyInAnyOrder("ClientId", "ApiKey");
            assertThat(parametreDecode("ApiKey")).isEqualTo(CLE_API);
        }

        @Test
        @DisplayName("une entrée illisible est ignorée, pas fatale")
        void entreeIllisibleIgnoree() {
            // Savoir le solde de deux pays sur trois vaut mieux que ne rien
            // savoir parce qu'un pays a ete ajoute avec un champ inattendu.
            String corps = """
                    {"code":100,"information":[
                      {"country":"Mali","solde":"pas un nombre"},
                      {"pays":"sans le champ country","solde":5},
                      {"country":"Togo","solde":42}]}""";

            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK, corps)))
                            .consulterSolde())
                    .assertNext(soldes -> assertThat(soldes).containsExactly(
                            new AfrikSmsAdministration.SoldeParPays("Togo", 42)))
                    .verifyComplete();
        }

        @Test
        @DisplayName("un tableau information absent rend une liste vide, pas une erreur")
        void informationAbsente() {
            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK, "{\"code\":100}")))
                            .consulterSolde())
                    .assertNext(soldes -> assertThat(soldes).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("non configuré : échec, sans appel réseau")
        void nonConfigure() {
            var sansCle = new AfrikSmsAdministration(WebClient.builder(), URL_RAPPEL,
                    URL_SOLDE, CLIENT_ID, "", URL_DECLAREE, 1, 5);

            StepVerifier.create(sansCle.consulterSolde()).verifyError(NotificationException.class);
        }
    }

    @Nested
    @DisplayName("Déclaration de l'URL de rappel")
    class DeclarationDeLUrlDeRappel {

        private static final String CORPS_RAPPEL = """
                {"code":100,"message":"Success operation",
                 "notifyURL":"https://exemple.invalide/dr","typeNotification":"POST"}""";

        @Test
        @DisplayName("l'URL configurée est poussée telle quelle")
        void urlPoussee() {
            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK, CORPS_RAPPEL)))
                            .declarerUrlDeRappel())
                    .expectNext("POST")
                    .verifyComplete();

            assertThat(parametreDecode("notifyURL")).isEqualTo(URL_DECLAREE);
        }

        /**
         * La documentation écrit {@code typeNotification} dans son tableau de
         * paramètres et {@code TypeNotification} dans son exemple cURL.
         *
         * <p>Les deux sont envoyés : un paramètre en trop est ignoré par l'API,
         * alors qu'un paramètre manquant fait échouer l'appel. Sans pouvoir
         * interroger l'API, c'est le seul choix sûr.
         */
        @Test
        @DisplayName("les deux orthographes de typeNotification sont envoyées")
        void deuxOrthographes() {
            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK, CORPS_RAPPEL)))
                            .declarerUrlDeRappel())
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(parametreDecode("typeNotification")).isEqualTo("1");
            assertThat(parametreDecode("TypeNotification")).isEqualTo("1");
        }

        @Test
        @DisplayName("une URL de rappel non configurée échoue, sans appel réseau")
        void urlNonConfiguree() {
            StepVerifier.create(administration(
                            Mono.just(json(HttpStatus.OK, CORPS_RAPPEL)), "", 1)
                            .declarerUrlDeRappel())
                    .verifyError(NotificationException.class);

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("notify-url"));
        }

        @ParameterizedTest(name = "notify-type = {0}")
        @ValueSource(ints = {0, 3, -1})
        @DisplayName("un type de notification hors de {1, 2} est refusé au lieu d'être envoyé")
        void typeInvalide(int type) {
            // Envoyer une valeur hors domaine ferait configurer chez AfrikSMS un
            // mode de rappel indetermine : les accuses cesseraient d'arriver,
            // sans erreur de notre cote.
            StepVerifier.create(administration(
                            Mono.just(json(HttpStatus.OK, CORPS_RAPPEL)), URL_DECLAREE, type)
                            .declarerUrlDeRappel())
                    .verifyError(NotificationException.class);

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("notify-type"));
        }

        @Test
        @DisplayName("le type GET est accepté")
        void typeGet() {
            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK, CORPS_RAPPEL)),
                            URL_DECLAREE, AfrikSmsAdministration.NOTIFICATION_PAR_GET)
                            .declarerUrlDeRappel())
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(parametreDecode("typeNotification")).isEqualTo("2");
        }
    }

    @Nested
    @DisplayName("Rien ne fuit, et un refus reste un refus")
    class RienNeFuit {

        @ParameterizedTest(name = "code {0}")
        @ValueSource(ints = {0, 101, 401})
        @DisplayName("un code autre que 100 est un échec, en HTTP 200")
        void codeDeRefus(int code) {
            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK,
                            "{\"code\":" + code + "}"))).consulterSolde())
                    .verifyError(NotificationException.class);
        }

        @ParameterizedTest(name = "corps illisible: {0}")
        @ValueSource(strings = {"", "pas du json", "{\"message\":\"sans code\"}"})
        void corpsIllisible(String corps) {
            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK, corps)))
                            .consulterSolde())
                    .verifyError(NotificationException.class);
        }

        @Test
        @DisplayName("un corps absent ne passe pas pour un succès")
        void corpsAbsent() {
            StepVerifier.create(administration(Mono.just(
                            ClientResponse.create(HttpStatus.OK).build())).consulterSolde())
                    .verifyError(NotificationException.class);
        }

        @ParameterizedTest(name = "HTTP {0}")
        @ValueSource(ints = {401, 403, 500})
        void statutNonDeuxCent(int statut) {
            StepVerifier.create(administration(Mono.just(
                            json(HttpStatus.valueOf(statut), CORPS_SOLDE))).consulterSolde())
                    .verifyError(NotificationException.class);
        }

        /**
         * Même piège que dans {@code AfrikSmsProvider} : l'URI porte
         * l'{@code ApiKey}, et le message d'une exception {@code WebClient}
         * porte l'URI. Ces appels ne transportent pas de code à usage unique,
         * mais une clé divulguée suffirait à envoyer des SMS à nos frais.
         */
        @Test
        @DisplayName("une panne de transport ne propage ni URI, ni cause")
        void panneDeTransport() {
            RuntimeException panneBavarde = new RuntimeException(
                    "Connection refused: GET " + URL_SOLDE + "?ApiKey=" + CLE_API);

            StepVerifier.create(administration(Mono.error(panneBavarde)).consulterSolde())
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(NotificationException.class);
                        assertThat(erreur.getMessage()).doesNotContain(CLE_API);
                        assertThat(erreur.getCause()).isNull();
                    });

            assertThat(journauxEmis())
                    .isNotEmpty()
                    .allSatisfy(ligne -> assertThat(ligne).doesNotContain(CLE_API));
        }

        @Test
        @DisplayName("aucun journal ne contient la clé d'API")
        void aucunJournalNeContientLaCle() {
            StepVerifier.create(administration(Mono.just(json(HttpStatus.OK,
                            "{\"code\":401}"))).consulterSolde())
                    .verifyError(NotificationException.class);

            assertThat(journauxEmis())
                    .isNotEmpty()
                    .allSatisfy(ligne -> assertThat(ligne)
                            .doesNotContain(CLE_API)
                            .doesNotContain(URL_SOLDE));
        }
    }
}
