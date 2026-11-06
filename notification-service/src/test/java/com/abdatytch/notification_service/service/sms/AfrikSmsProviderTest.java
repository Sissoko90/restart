package com.abdatytch.notification_service.service.sms;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.abdatytch.notification_service.constants.ErrorCode;
import com.abdatytch.notification_service.exception.NotificationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Le fournisseur retenu, et la classe la plus exposée du lot : l'URI qu'elle
 * construit contient à la fois la clé d'API et le code à usage unique.
 *
 * <p>Trois choses sont vérifiées ici, et aucune ne se déduit de la lecture :
 *
 * <ol>
 *   <li>l'URI porte bien les cinq paramètres, correctement encodés — un message
 *       contenant {@code &} ne doit pas se scinder en deux paramètres ;</li>
 *   <li>un corps de réponse 200 dont le {@code code} n'est pas 100 est un
 *       <b>échec</b> : c'est la forme que prend le refus chez AfrikSMS, et ne
 *       regarder que le statut HTTP la prendrait pour un succès ;</li>
 *   <li>aucun journal émis, sur aucun chemin d'échec, ne contient la clé d'API
 *       ni le contenu du SMS.</li>
 * </ol>
 */
@DisplayName("AfrikSmsProvider")
class AfrikSmsProviderTest {

    private static final String URL = "https://exemple.invalide/api/outbounds/send";
    // Valeurs FACTICES, de la meme forme que les vraies. Les identifiants
    // reels vivent dans .env, qui n'est pas suivi par git ; les recopier ici
    // les publierait dans le depot, ce qui annulerait la precaution.
    private static final String CLIENT_ID = "00000000";
    private static final String CLE_API = "cle-api-factice-pour-les-tests-0000";
    private static final String EXPEDITEUR = "RESTART";
    private static final String NUMERO = "+228 90 90 90 90";
    private static final String CONTENU = "Votre code Restart est 482913";

    private static final String CORPS_SUCCES =
            "{\"code\":100,\"message\":\"Success operation\",\"resourceId\":\"P7COiD\"}";

    /** URI réellement présentée au transport par le dernier appel. */
    private final AtomicReference<URI> uriVue = new AtomicReference<>();

    private ListAppender<ILoggingEvent> journaux;
    private Logger loggerDuFournisseur;
    private RegistreDEssai registre;

    /**
     * Registre d'essai : note ce qu'on lui demande, sans Redis.
     *
     * <p>Il peut aussi tomber en panne sur commande, ce qui sert à vérifier le
     * point le plus facile à rater — un registre indisponible ne doit pas faire
     * échouer un SMS déjà accepté par le fournisseur.
     */
    private static final class RegistreDEssai implements IRegistreDesEnvoisSms {
        private final Map<String, String> entrees = new LinkedHashMap<>();
        private RuntimeException panne;

        @Override
        public Mono<Void> enregistrer(String resourceId, String numeroDeTelephone) {
            if (panne != null) {
                return Mono.error(panne);
            }
            entrees.put(resourceId, numeroDeTelephone);
            return Mono.empty();
        }

        @Override
        public Mono<String> numeroDe(String resourceId) {
            return Mono.justOrEmpty(entrees.get(resourceId));
        }
    }

    @BeforeEach
    void preparerLeRegistre() {
        registre = new RegistreDEssai();
    }

    @BeforeEach
    void capturerLesJournaux() {
        journaux = new ListAppender<>();
        journaux.start();
        loggerDuFournisseur = (Logger) LoggerFactory.getLogger(AfrikSmsProvider.class);
        loggerDuFournisseur.addAppender(journaux);
    }

    @AfterEach
    void relacherLesJournaux() {
        loggerDuFournisseur.detachAppender(journaux);
        journaux.stop();
    }

    /**
     * Construit le fournisseur sur un transport simulé.
     *
     * <p>{@code exchangeFunction} remplace la couche réseau sans toucher à la
     * construction de l'URI : c'est l'URI finale, telle que WebClient
     * l'émettrait, qui est capturée.
     */
    private AfrikSmsProvider fournisseur(ClientResponse reponse) {
        return fournisseur(Mono.just(reponse));
    }

    private AfrikSmsProvider fournisseur(Mono<ClientResponse> reponse) {
        WebClient.Builder constructeur = WebClient.builder()
                .exchangeFunction(requete -> {
                    uriVue.set(requete.url());
                    return reponse;
                });
        return new AfrikSmsProvider(constructeur, registre, disjoncteurReel(), URL, CLIENT_ID, CLE_API, EXPEDITEUR, 5);
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

    @Nested
    @DisplayName("Construction de l'URI")
    class ConstructionDeLUri {

        @Test
        @DisplayName("les cinq paramètres obligatoires sont présents")
        void cinqParametres() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, CORPS_SUCCES))
                            .envoyer(NUMERO, CONTENU))
                    .verifyComplete();

            var parametres = UriComponentsBuilder.fromUri(uriVue.get()).build()
                    .getQueryParams();
            assertThat(parametres.keySet())
                    .containsExactlyInAnyOrder("ClientId", "ApiKey", "SenderId",
                            "Message", "MobileNumbers");
        }

        @Test
        @DisplayName("le numéro est normalisé : ni +, ni espaces")
        void numeroNormalise() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, CORPS_SUCCES))
                            .envoyer(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(parametreDecode("MobileNumbers")).isEqualTo("22890909090");
        }

        @Test
        @DisplayName("le nom d'expéditeur est tronqué à 11 caractères")
        void expediteurTronque() {
            WebClient.Builder constructeur = WebClient.builder()
                    .exchangeFunction(requete -> {
                        uriVue.set(requete.url());
                        return Mono.just(json(HttpStatus.OK, CORPS_SUCCES));
                    });
            var avecNomTropLong = new AfrikSmsProvider(constructeur, registre, disjoncteurReel(), URL, CLIENT_ID,
                    CLE_API, "RESTART-PLATEFORME-LONGUE", 5);

            StepVerifier.create(avecNomTropLong.envoyer(NUMERO, CONTENU)).verifyComplete();

            assertThat(parametreDecode("SenderId"))
                    .hasSize(AfrikSmsProvider.LONGUEUR_MAX_EXPEDITEUR)
                    .isEqualTo("RESTART-PLA");
        }

        /**
         * Le point qui justifie {@code encode()} avant {@code build()}.
         *
         * <p>Construire puis encoder laisserait {@code &} et {@code =} intacts,
         * puisqu'ils sont légaux dans une chaîne de requête : le message se
         * scinderait en paramètres supplémentaires, et AfrikSMS recevrait un
         * message tronqué — ou un {@code ApiKey} écrasé par un message
         * malveillant.
         */
        @Test
        @DisplayName("un message contenant & = % ? # reste UN seul paramètre")
        void aucuneInjectionDeParametre() {
            String hostile = "Code 482913 & ApiKey=vole % fin ? # suite";

            StepVerifier.create(fournisseur(json(HttpStatus.OK, CORPS_SUCCES))
                            .envoyer(NUMERO, hostile))
                    .verifyComplete();

            assertThat(uriVue.get().getRawQuery().split("&"))
                    .as("le message ne doit creer aucun parametre supplementaire")
                    .hasSize(5);
            assertThat(parametreDecode("Message")).isEqualTo(hostile);
            assertThat(parametreDecode("ApiKey")).isEqualTo(CLE_API);
        }

        @Test
        @DisplayName("les accents survivent à l'aller-retour")
        void accents() {
            String avecAccents = "Votre code de vérification Restart : 482913";

            StepVerifier.create(fournisseur(json(HttpStatus.OK, CORPS_SUCCES))
                            .envoyer(NUMERO, avecAccents))
                    .verifyComplete();

            assertThat(parametreDecode("Message")).isEqualTo(avecAccents);
        }

        private String parametreDecode(String nom) {
            String brut = UriComponentsBuilder.fromUri(uriVue.get()).build()
                    .getQueryParams().getFirst(nom);
            assertThat(brut).as("parametre %s absent", nom).isNotNull();
            return URLDecoder.decode(brut, StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("Lecture de la réponse")
    class LectureDeLaReponse {

        @Test
        @DisplayName("code 100 vaut succès")
        void codeCentEstUnSucces() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, CORPS_SUCCES))
                            .envoyer(NUMERO, CONTENU))
                    .verifyComplete();
        }

        /**
         * Le défaut que ce test existe pour empêcher : un refus d'AfrikSMS
         * arrive en <b>HTTP 200</b>. Ne regarder que le statut ferait passer
         * crédit épuisé, expéditeur inconnu ou numéro invalide pour un envoi
         * réussi, et l'utilisateur attendrait indéfiniment un SMS.
         */
        @ParameterizedTest(name = "code {0} vaut échec, en HTTP 200")
        @ValueSource(ints = {0, 101, 200, 400})
        void unCodeAutreQueCentEstUnEchec(int code) {
            String corps = "{\"code\":" + code + ",\"message\":\"refus\"}";

            StepVerifier.create(fournisseur(json(HttpStatus.OK, corps))
                            .envoyer(NUMERO, CONTENU))
                    .verifyError(NotificationException.class);

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains(String.valueOf(code)));
        }

        /**
         * Le journal de succès appartient au fournisseur, et à lui seul.
         *
         * <p>{@code VerificationCodeServiceImpl} l'écrivait sur un
         * {@code doOnSuccess} posé sur un {@code Mono<Void>} — qui se déclenche
         * aussi quand la source se termine <b>vide</b>. Or {@code sendSms} se
         * termine vide dans deux cas où rien n'est parti : {@code sms.enabled}
         * à faux, et le repli du disjoncteur. Le journal annonçait donc
         * « SMS envoyé avec succès » juste après « Disjoncteur SMS ouvert ».
         */
        @Test
        @DisplayName("le succès n'est journalisé que lorsque l'envoi est accepté")
        void leSuccesEstJournaliseUneSeuleFois() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, CORPS_SUCCES))
                            .envoyer(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("succès"));
        }

        @Test
        @DisplayName("un refus ne journalise aucun succès")
        void unRefusNeJournalisePasDeSucces() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, "{\"code\":401}"))
                            .envoyer(NUMERO, CONTENU))
                    .verifyError(NotificationException.class);

            assertThat(journauxEmis())
                    .allSatisfy(ligne -> assertThat(ligne).doesNotContain("succès"));
        }

        @Test
        @DisplayName("un code rendu en chaîne est accepté")
        void codeEnChaine() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, "{\"code\":\"100\"}"))
                            .envoyer(NUMERO, CONTENU))
                    .verifyComplete();
        }

        @ParameterizedTest(name = "corps illisible: {0}")
        @ValueSource(strings = {
                "",
                "pas du json",
                "{\"message\":\"sans code\"}",
                "<html><body>Service Unavailable</body></html>",
        })
        void corpsIllisibleEstUnEchec(String corps) {
            // Lu comme du texte puis analyse : un fournisseur tiers qui repond
            // du HTML ne doit pas produire une exception de type de media, qui
            // ne dirait rien de l'incident.
            StepVerifier.create(fournisseur(json(HttpStatus.OK, corps))
                            .envoyer(NUMERO, CONTENU))
                    .verifyError(NotificationException.class);
        }

        @Test
        @DisplayName("un corps absent ne passe pas pour un succès")
        void corpsAbsent() {
            // Sans defaultIfEmpty sur le Mono<String>, bodyToMono rendrait un
            // Mono VIDE : la chaine se terminerait vide et l'envoi paraitrait
            // reussi sans qu'aucun code n'ait ete verifie. Le piege du Mono vide,
            // deja rencontre neuf fois dans ce depot.
            StepVerifier.create(fournisseur(ClientResponse.create(HttpStatus.OK).build())
                            .envoyer(NUMERO, CONTENU))
                    .verifyError(NotificationException.class);
        }

        @ParameterizedTest(name = "HTTP {0} vaut échec")
        @ValueSource(ints = {401, 403, 429, 500, 503})
        void statutNonDeuxCentEstUnEchec(int statut) {
            StepVerifier.create(fournisseur(json(HttpStatus.valueOf(statut), CORPS_SUCCES))
                            .envoyer(NUMERO, CONTENU))
                    .verifyError(NotificationException.class);

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains(String.valueOf(statut)));
        }
    }

    @Nested
    @DisplayName("Rien ne fuit par les journaux ni par l'exception")
    class RienNeFuit {

        /**
         * Le piège central de cette classe.
         *
         * <p>Les paramètres voyagent dans la chaîne de requête, donc l'URI
         * contient la clé d'API <b>et</b> le code. Or le message d'un
         * {@code WebClientResponseException} ou d'un
         * {@code WebClientRequestException} de Spring inclut l'URI de la
         * requête. Laisser remonter cette exception suffirait à tout écrire
         * dans les journaux — et pas ici :
         * {@code VerificationCodeServiceImpl} journalise déjà l'erreur reçue
         * par {@code log.error(..., phoneNumber, error)}. La fuite serait
         * apparue dans un fichier qu'on n'aurait pas pensé à relire.
         */
        @Test
        @DisplayName("une panne de transport ne propage ni URI, ni cause")
        void panneDeTransport() {
            RuntimeException panneAvecUri = new RuntimeException(
                    "Connection refused: GET " + URL + "?ApiKey=" + CLE_API
                            + "&Message=" + CONTENU);

            StepVerifier.create(fournisseur(Mono.<ClientResponse>error(panneAvecUri))
                            .envoyer(NUMERO, CONTENU))
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(NotificationException.class);
                        assertThat(erreur.getMessage())
                                .doesNotContain(CLE_API)
                                .doesNotContain("482913");
                        assertThat(erreur.getCause())
                                .as("une cause portant l'URI serait journalisee par l'appelant")
                                .isNull();
                    });
        }

        @Test
        @DisplayName("l'expiration du délai ne propage rien non plus")
        void expirationDuDelai() {
            // Temps virtuel : `timeout` s'appuie sur Schedulers.parallel(), que
            // withVirtualTime remplace. Sans cela, ce test dormirait reellement
            // cinq secondes — et un test lent finit desactive.
            StepVerifier.withVirtualTime(() -> fournisseur(Mono.<ClientResponse>never())
                            .envoyer(NUMERO, CONTENU))
                    .expectSubscription()
                    .thenAwait(Duration.ofSeconds(6))
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(NotificationException.class);
                        assertThat(erreur.getCause()).isNull();
                    });
        }

        @ParameterizedTest(name = "chemin d''echec: {0}")
        @ValueSource(strings = {
                "{\"code\":401,\"message\":\"Invalid ApiKey\"}",
                "pas du json",
                "",
        })
        @DisplayName("aucun journal ne contient la clé d'API ni le code du SMS")
        void aucunJournalNeContientDeJustificatif(String corps) {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, corps))
                            .envoyer(NUMERO, CONTENU))
                    .verifyError(NotificationException.class);

            assertThat(journauxEmis())
                    .as("un chemin d'echec a bien journalise quelque chose")
                    .isNotEmpty()
                    .allSatisfy(ligne -> assertThat(ligne)
                            .doesNotContain(CLE_API)
                            .doesNotContain(CONTENU)
                            .doesNotContain("482913")
                            .doesNotContain(URL));
        }

        @Test
        @DisplayName("le corps de la réponse n'est jamais recopié dans les journaux")
        void leCorpsNEstPasRecopie() {
            // Un tiers peut faire figurer dans sa reponse ce qu'on lui a envoye.
            // Recopier le corps dans un journal rouvrirait la fuite par l'autre
            // bout.
            String corpsQuiRenvoieLeMessage =
                    "{\"code\":400,\"message\":\"Rejected: " + CONTENU + "\"}";

            StepVerifier.create(fournisseur(json(HttpStatus.OK, corpsQuiRenvoieLeMessage))
                            .envoyer(NUMERO, CONTENU))
                    .verifyError(NotificationException.class);

            assertThat(journauxEmis())
                    .allSatisfy(ligne -> assertThat(ligne).doesNotContain("482913"));
        }
    }

    @Nested
    @DisplayName("Registre des envois, pour que l'accusé désigne quelqu'un")
    class RegistreDesEnvois {

        @Test
        @DisplayName("le resourceId rendu par AfrikSMS est retenu avec le numéro")
        void resourceIdRetenu() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, CORPS_SUCCES))
                            .envoyer(NUMERO, CONTENU))
                    .verifyComplete();

            // Sans cette correspondance, l'accusé de réception à venir dirait
            // « P7COiD : injoignable » — une phrase qui ne designe personne.
            assertThat(registre.entrees).containsExactly(entry("P7COiD", "22890909090"));
        }

        @Test
        @DisplayName("le contenu du SMS n'entre jamais dans le registre")
        void leContenuNEstPasConserve() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, CORPS_SUCCES))
                            .envoyer(NUMERO, CONTENU))
                    .verifyComplete();

            // Conserver le code a usage unique dans Redis lui donnerait une
            // seconde vie bien apres sa peremption fonctionnelle.
            assertThat(registre.entrees.values())
                    .allSatisfy(valeur -> assertThat(valeur).doesNotContain("482913"));
        }

        @Test
        @DisplayName("une réponse sans resourceId reste un succès")
        void sansResourceId() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK, "{\"code\":100}"))
                            .envoyer(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(registre.entrees).isEmpty();
        }

        /**
         * Le point le plus facile à rater.
         *
         * <p>À cet instant, AfrikSMS a <b>accepté</b> le message : il va
         * partir. Faire échouer l'envoi parce que Redis est indisponible
         * annoncerait un échec à un utilisateur qui va recevoir son code, et le
         * ferait recommencer pour rien.
         */
        @Test
        @DisplayName("un registre en panne ne fait pas échouer un SMS déjà accepté")
        void registreEnPanneNEchouePas() {
            registre.panne = new IllegalStateException("Redis indisponible");

            StepVerifier.create(fournisseur(json(HttpStatus.OK, CORPS_SUCCES))
                            .envoyer(NUMERO, CONTENU))
                    .verifyComplete();
        }

        @Test
        @DisplayName("un refus du fournisseur ne retient rien")
        void unRefusNeRetientRien() {
            StepVerifier.create(fournisseur(json(HttpStatus.OK,
                            "{\"code\":401,\"resourceId\":\"XYZ\"}"))
                            .envoyer(NUMERO, CONTENU))
                    .verifyError(NotificationException.class);

            assertThat(registre.entrees).isEmpty();
        }
    }

    @Nested
    @DisplayName("Configuration")
    class Configuration {

        @Test
        @DisplayName("estConfigure exige les quatre valeurs")
        void exigeLesQuatreValeurs() {
            assertThat(construire(URL, CLIENT_ID, CLE_API, EXPEDITEUR).estConfigure()).isTrue();
            assertThat(construire("", CLIENT_ID, CLE_API, EXPEDITEUR).estConfigure()).isFalse();
            assertThat(construire(URL, "", CLE_API, EXPEDITEUR).estConfigure()).isFalse();
            assertThat(construire(URL, CLIENT_ID, "", EXPEDITEUR).estConfigure()).isFalse();
            assertThat(construire(URL, CLIENT_ID, CLE_API, "").estConfigure()).isFalse();
        }

        @Test
        @DisplayName("un null de configuration ne provoque pas de NullPointerException")
        void nullTolere() {
            assertThat(construire(null, null, null, null).estConfigure()).isFalse();
        }

        @Test
        @DisplayName("non configuré : échec immédiat, sans aucun appel réseau")
        void nonConfigureNAppellePas() {
            List<URI> appels = new ArrayList<>();
            WebClient.Builder constructeur = WebClient.builder()
                    .exchangeFunction(requete -> {
                        appels.add(requete.url());
                        return Mono.just(json(HttpStatus.OK, CORPS_SUCCES));
                    });
            var sansCle = new AfrikSmsProvider(constructeur, registre, disjoncteurReel(), URL, CLIENT_ID, "", EXPEDITEUR, 5);

            StepVerifier.create(sansCle.envoyer(NUMERO, CONTENU))
                    .verifyError(NotificationException.class);

            assertThat(appels).isEmpty();
        }

        @Test
        @DisplayName("un numéro inexploitable échoue sans appel réseau")
        void numeroInexploitableNAppellePas() {
            List<URI> appels = new ArrayList<>();
            WebClient.Builder constructeur = WebClient.builder()
                    .exchangeFunction(requete -> {
                        appels.add(requete.url());
                        return Mono.just(json(HttpStatus.OK, CORPS_SUCCES));
                    });
            var provider = new AfrikSmsProvider(constructeur, registre, disjoncteurReel(), URL, CLIENT_ID, CLE_API,
                    EXPEDITEUR, 5);

            StepVerifier.create(provider.envoyer("123", CONTENU))
                    .verifyError(NotificationException.class);

            // Appeler avec un numero invalide ferait voyager le code dans une
            // URI pour rien.
            assertThat(appels).isEmpty();
        }

        @Test
        @DisplayName("le nom du fournisseur est celui attendu dans sms.provider")
        void nomDuFournisseur() {
            assertThat(construire(URL, CLIENT_ID, CLE_API, EXPEDITEUR).nom())
                    .isEqualTo("afriksms")
                    .isEqualTo(AfrikSmsProvider.NOM);
        }

        private AfrikSmsProvider construire(String url, String clientId, String cle,
                                            String expediteur) {
            return new AfrikSmsProvider(WebClient.builder(), registre, disjoncteurReel(), url, clientId, cle, expediteur, 5);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Transport et refus du fournisseur ne sont PAS le meme echec")
    class TransportContreRefus {

        /**
         * La distinction qui rend le disjoncteur utile.
         *
         * <p>Les deux cas rendaient le meme {@code SMS_SERVICE_ERROR}. Or ils
         * appellent des reactions opposees : une panne de transport doit ouvrir
         * le disjoncteur — inutile de continuer a appeler un hote injoignable —
         * tandis qu'un refus du fournisseur signale qu'il <b>fonctionne</b> et
         * ne doit rien ouvrir.
         *
         * <p>Sans cette distinction, le disjoncteur aurait ete pose sur un code
         * d'erreur que le predicat ne compte pas : present, cable, et incapable
         * de s'ouvrir. Exactement le defaut qu'on vient de retirer ailleurs.
         */
        @Test
        @DisplayName("une panne de transport rend une INDISPONIBILITE")
        void transportRendIndisponibilite() {
            StepVerifier.create(fournisseur(Mono.<ClientResponse>error(
                            new java.net.ConnectException("hote injoignable")))
                            .envoyer(NUMERO, CONTENU))
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(NotificationException.class);
                        assertThat(((NotificationException) erreur).getErrorCode())
                                .withFailMessage("une panne de transport n'est pas "
                                        + "declaree comme indisponibilite : le "
                                        + "disjoncteur ne la comptera pas et ne "
                                        + "s'ouvrira jamais")
                                .isEqualTo(ErrorCode.SMS_PROVIDER_UNAVAILABLE);
                    });
        }

        @Test
        @DisplayName("un refus du fournisseur n'est PAS une indisponibilite")
        void refusNEstPasUneIndisponibilite() {
            // Code 401 « Invalid ApiKey » : le fournisseur a repondu, donc il
            // fonctionne. Le compter comme une panne ouvrirait le disjoncteur
            // sur une erreur de configuration, et masquerait la vraie cause.
            StepVerifier.create(fournisseur(json(HttpStatus.OK,
                            "{\"code\":401,\"message\":\"Invalid ApiKey\"}"))
                            .envoyer(NUMERO, CONTENU))
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(NotificationException.class);
                        assertThat(((NotificationException) erreur).getErrorCode())
                                .withFailMessage("un refus du fournisseur est compte "
                                        + "comme une panne : une cle d'API invalide "
                                        + "ouvrirait le disjoncteur au lieu de signaler "
                                        + "la configuration")
                                .isEqualTo(ErrorCode.SMS_SERVICE_ERROR);
                    });
        }

    }

    /**
     * Disjoncteur REEL, sur un registre neuf a chaque appel.
     *
     * <p>Pas un bouchon : c'est l'operateur qui compte les echecs, donc le
     * simuler reviendrait a ne rien verifier de ce qui a ete ajoute. Un
     * registre neuf par fournisseur evite qu'un test laisse un disjoncteur
     * ouvert pour le suivant — ce serait l'ordre d'execution comme condition
     * de reussite.
     *
     * <p>Seuil et fenetre volontairement larges : ces tests portent sur le
     * fournisseur, pas sur le disjoncteur. Avec une fenetre de 100 appels, il
     * ne s'ouvre jamais ici et ne masque aucune assertion.
     *
     * <p>Passe par la fabrique de {@code DisjoncteurSortantConfig} et non par un
     * constructeur direct : la fabrique porte les deux regles de production —
     * ce qui compte comme une panne, et ce qui est leve a l'ouverture. Les
     * recopier ici rendrait possible un test vert sur une regle differente de
     * celle qui tourne.
     */
    private static com.abdatytch.commons.resilience.IDisjoncteurSortant disjoncteurReel() {
        return com.abdatytch.notification_service.config.DisjoncteurSortantConfig.pour(
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(),
                50f, 100, 100, 30L);
    }
}
