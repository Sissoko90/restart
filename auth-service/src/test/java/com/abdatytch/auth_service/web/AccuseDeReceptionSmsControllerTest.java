package com.abdatytch.auth_service.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.abdatytch.auth_service.service.sms.IRegistreDesEnvoisSms;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le seul point d'entrée de ce service appelé par un tiers.
 *
 * <p>Deux choses s'y vérifient, et aucune ne se déduit de la lecture :
 *
 * <ol>
 *   <li>les <b>quatre formes d'appel</b> fonctionnent. AfrikSMS choisit entre
 *       GET et POST par un réglage qui vit chez lui ({@code typeNotification}),
 *       donc n'en accepter qu'une rendrait ce point d'entrée muet le jour où ce
 *       réglage change — et le changement serait invisible de notre côté,
 *       puisque c'est <b>lui</b> qui recevrait les refus ;</li>
 *   <li>un secret absent ou faux rend <b>404</b>, et un secret attendu vide
 *       refuse tout.</li>
 * </ol>
 */
@DisplayName("AccuseDeReceptionSmsController")
class AccuseDeReceptionSmsControllerTest {

    private static final String SECRET = "secret-de-rappel-pour-les-tests";
    private static final String RESOURCE_ID = "P7COiDNjFvZ_gkYo7lGKVIDMemINjrgx";
    private static final String NUMERO = "22890909090";

    /** Registre d'essai, avec panne sur commande. */
    private static final class RegistreDEssai implements IRegistreDesEnvoisSms {
        private final Map<String, String> entrees = new LinkedHashMap<>();
        private RuntimeException panne;

        @Override
        public Mono<Void> enregistrer(String resourceId, String numeroDeTelephone) {
            entrees.put(resourceId, numeroDeTelephone);
            return Mono.empty();
        }

        @Override
        public Mono<String> numeroDe(String resourceId) {
            if (panne != null) {
                return Mono.error(panne);
            }
            return Mono.justOrEmpty(entrees.get(resourceId));
        }
    }

    private RegistreDEssai registre;
    private ListAppender<ILoggingEvent> journaux;

    /**
     * DEUX loggers ecoutes, et c'est le point de ce test.
     *
     * <p>Le refus du secret et le corps illisible restent journalises par le
     * controleur ; l'interpretation de l'accuse — statut, identifiant inconnu,
     * panne du registre — l'est par
     * {@code AccusesDeReceptionSmsService}, ou elle a ete deplacee.
     *
     * <p>N'ecouter que le controleur a fait tomber dix tests d'un coup avec
     * « Expecting any element of: [] » : la preuve que ces assertions portent
     * bien sur des journaux reels, et non sur une liste qu'on remplit soi-meme.
     */
    private final java.util.List<Logger> loggersEcoutes = new java.util.ArrayList<>();

    @BeforeEach
    void preparer() {
        registre = new RegistreDEssai();
        registre.entrees.put(RESOURCE_ID, NUMERO);
        journaux = new ListAppender<>();
        journaux.start();
        loggersEcoutes.clear();
        for (Class<?> source : java.util.List.of(
                AccuseDeReceptionSmsController.class,
                com.abdatytch.auth_service.service.sms.AccusesDeReceptionSmsService.class)) {
            Logger logger = (Logger) LoggerFactory.getLogger(source);
            logger.addAppender(journaux);
            loggersEcoutes.add(logger);
        }
    }

    @AfterEach
    void relacher() {
        loggersEcoutes.forEach(logger -> logger.detachAppender(journaux));
        journaux.stop();
    }

    /**
     * Client lié au seul contrôleur.
     *
     * <p>{@code bindToController} ne renseigne pas les champs {@code @Value} —
     * le dépôt s'est déjà fait prendre — mais ce contrôleur reçoit son secret
     * par le <b>constructeur</b>, donc l'instance fournie ici est bien celle qui
     * répond.
     */
    private WebTestClient client(String secretAttendu) {
        // Le VRAI service d'interpretation, sur le registre d'essai.
        //
        // Cette suite verifie ce que l'endpoint journalise selon le statut et ce
        // qu'il repond sur un identifiant inconnu : avec un
        // IAccusesDeReceptionSms simule, ces assertions ne porteraient plus sur
        // rien. L'interpretation a change de couche, pas de nature.
        var accuses = new com.abdatytch.auth_service.service.sms
                .AccusesDeReceptionSmsService(registre);
        return WebTestClient.bindToController(
                        new AccuseDeReceptionSmsController(accuses, secretAttendu))
                .build();
    }

    private List<String> journauxEmis() {
        return journaux.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Nested
    @DisplayName("Les quatre formes d'appel sont acceptées")
    class QuatreFormes {

        @Test
        @DisplayName("GET, paramètres dans la chaîne de requête")
        void enGet() {
            client(SECRET).get()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET)
                            .queryParam("resourceId", RESOURCE_ID)
                            .queryParam("code", "000")
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.recu").isEqualTo(true);

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne)
                            .contains(NUMERO).contains("LIVRE"));
        }

        @Test
        @DisplayName("POST, corps JSON — la forme que montre la documentation")
        void enPostJson() {
            client(SECRET).post()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "resourceId", RESOURCE_ID,
                            "code", "000",
                            "message", "Successful delivered to Terminal"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.recu").isEqualTo(true);

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains(NUMERO));
        }

        @Test
        @DisplayName("POST, corps de formulaire")
        void enPostFormulaire() {
            MultiValueMap<String, String> champs = new LinkedMultiValueMap<>();
            champs.add("resourceId", RESOURCE_ID);
            champs.add("code", "002");

            client(SECRET).post()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET).build())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(champs)
                    .exchange()
                    .expectStatus().isOk();

            // Le @RequestParam de WebFlux ne lit QUE la chaine de requete : sans
            // la methode dediee au formulaire, les champs arriveraient vides et
            // l'accuse serait traite comme illisible.
            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne)
                            .contains(NUMERO).contains("INJOIGNABLE"));
        }

        @Test
        @DisplayName("POST sans corps, paramètres dans l'URL")
        void enPostSansCorps() {
            client(SECRET).post()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET)
                            .queryParam("resourceId", RESOURCE_ID)
                            .queryParam("code", "001")
                            .build())
                    .exchange()
                    .expectStatus().isOk();

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("REMIS_SANS_ACCUSE"));
        }
    }

    @Nested
    @DisplayName("Authentification par secret partagé")
    class Authentification {

        @ParameterizedTest(name = "cle = {0}")
        @ValueSource(strings = {"", "mauvaise-cle", "secret-de-rappel-pour-les-test"})
        @DisplayName("un secret faux rend 404, jamais 403")
        void secretFaux(String cle) {
            // 403 confirmerait l'existence du point d'entree et inviterait a
            // chercher le secret. C'est la regle du depot pour tout refus.
            client(SECRET).get()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", cle)
                            .queryParam("resourceId", RESOURCE_ID)
                            .queryParam("code", "000")
                            .build())
                    .exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        @DisplayName("aucune clé rend 404")
        void sansCle() {
            client(SECRET).get()
                    .uri("/sms/accuses/afriksms?resourceId=" + RESOURCE_ID + "&code=000")
                    .exchange()
                    .expectStatus().isNotFound();
        }

        /**
         * Le défaut qu'une valeur par défaut aurait créé.
         *
         * <p>Si {@code sms.afriksms.notify-secret} manque, le point d'entrée
         * doit tout refuser. Accepter une clé vide quand le secret attendu est
         * vide laisserait ouvert tout déploiement où personne n'a renseigné la
         * variable.
         */
        @Test
        @DisplayName("un secret attendu vide refuse tout, y compris une clé vide")
        void secretAttenduVide() {
            client("").get()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", "")
                            .queryParam("resourceId", RESOURCE_ID)
                            .build())
                    .exchange()
                    .expectStatus().isNotFound();

            client("").get()
                    .uri("/sms/accuses/afriksms")
                    .exchange()
                    .expectStatus().isNotFound();
        }

        /**
         * Propriété qu'aucun test de comportement ne peut observer.
         *
         * <p>Mesuré par mutation : remplacer {@code MessageDigest.isEqual} par
         * {@code secretAttendu.equals(cle)} ne fait échouer <b>aucun</b> des
         * tests de ce fichier. C'est normal — le résultat est identique, seul le
         * temps de réponse change, et un test unitaire ne le voit pas.
         *
         * <p>Or {@code String.equals} s'arrête au premier caractère différent :
         * le temps révèle alors combien de caractères de tête sont justes, ce
         * qui permet de reconstituer le secret octet par octet. Sur un point
         * d'entrée public et sans limitation de débit propre, c'est praticable.
         *
         * <p>Faute de pouvoir le vérifier par le comportement, la propriété est
         * verrouillée à la source. Un contrôle faible vaut mieux qu'une
         * protection que rien ne retient.
         */
        @Test
        @DisplayName("la comparaison du secret reste à temps constant")
        void comparaisonATempsConstant() throws java.io.IOException {
            java.nio.file.Path source = java.nio.file.Path.of("src/main/java/com/abdatytch/"
                    + "auth_service/web/AccuseDeReceptionSmsController.java");
            assertThat(source)
                    .withFailMessage("source introuvable : ce garde-fou serait vert "
                            + "sans rien vérifier")
                    .exists();
            String contenu = java.nio.file.Files.readString(source);

            assertThat(contenu)
                    .withFailMessage("la comparaison du secret doit passer par "
                            + "MessageDigest.isEqual : une comparaison qui s'arrête au "
                            + "premier caractère différent laisse reconstituer le secret "
                            + "par la mesure du temps de réponse")
                    .contains("MessageDigest.isEqual");
            assertThat(contenu)
                    .withFailMessage("le secret attendu est comparé par equals quelque part")
                    .doesNotContain("secretAttendu.equals(")
                    .doesNotContain(".equals(secretAttendu)");
        }

        @Test
        @DisplayName("un refus ne journalise ni le secret attendu ni celui reçu")
        void aucuneFuiteDeSecret() {
            client(SECRET).get()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", "tentative-xyz").build())
                    .exchange()
                    .expectStatus().isNotFound();

            assertThat(journauxEmis())
                    .isNotEmpty()
                    .allSatisfy(ligne -> assertThat(ligne)
                            .doesNotContain(SECRET)
                            .doesNotContain("tentative-xyz"));
        }
    }

    @Nested
    @DisplayName("Interprétation de l'accusé")
    class Interpretation {

        @ParameterizedTest(name = "code {0} -> {1}")
        @CsvSource({
                "000, LIVRE",
                "001, REMIS_SANS_ACCUSE",
                "002, INJOIGNABLE",
                "999, INCONNU",
        })
        void codes(String code, String statutAttendu) {
            client(SECRET).get()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET)
                            .queryParam("resourceId", RESOURCE_ID)
                            .queryParam("code", code)
                            .build())
                    .exchange()
                    .expectStatus().isOk();

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains(statutAttendu));
        }

        @Test
        @DisplayName("un code absent est INCONNU, pas une erreur")
        void codeAbsent() {
            client(SECRET).get()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET)
                            .queryParam("resourceId", RESOURCE_ID)
                            .build())
                    .exchange()
                    .expectStatus().isOk();

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("INCONNU"));
        }

        /**
         * Pourquoi 200 et non 404 sur un identifiant inconnu.
         *
         * <p>Un fournisseur qui reçoit une erreur réessaie. Répondre 4xx sur un
         * accusé qu'on ne sait pas rattacher — accusé en retard au-delà du TTL,
         * registre purgé, identifiant forgé — déclencherait des reprises
         * indéfinies pour une information déjà perdue.
         */
        @Test
        @DisplayName("un resourceId inconnu est acquitté, pas rejeté")
        void resourceIdInconnu() {
            client(SECRET).get()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET)
                            .queryParam("resourceId", "identifiant-jamais-vu")
                            .queryParam("code", "000")
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.recu").isEqualTo(true);

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("inconnu"));
        }

        @Test
        @DisplayName("un resourceId absent est acquitté")
        void resourceIdAbsent() {
            client(SECRET).get()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET)
                            .queryParam("code", "000")
                            .build())
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("un registre indisponible n'empêche pas d'acquitter")
        void registreIndisponible() {
            registre.panne = new IllegalStateException("Redis indisponible");

            // Sans cela, AfrikSMS reessaierait en boucle pendant toute la panne
            // Redis, pour une information qu'on ne saura de toute facon pas lire.
            client(SECRET).get()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET)
                            .queryParam("resourceId", RESOURCE_ID)
                            .queryParam("code", "000")
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.recu").isEqualTo(true);
        }

        @Test
        @DisplayName("le champ message de l'accusé n'est jamais journalisé")
        void leMessageDuTiersNEstPasJournalise() {
            // Chaine libre venue d'un tiers : rien ne garantit qu'elle ne
            // contienne pas un echo de ce qu'on lui a envoye, donc le code.
            client(SECRET).post()
                    .uri(uri -> uri.path("/sms/accuses/afriksms")
                            .queryParam("cle", SECRET).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "resourceId", RESOURCE_ID,
                            "code", "000",
                            "message", "Votre code Restart est 482913"))
                    .exchange()
                    .expectStatus().isOk();

            assertThat(journauxEmis())
                    .allSatisfy(ligne -> assertThat(ligne).doesNotContain("482913"));
        }
    }
}
