package com.abdatytch.auth_service.service.sms;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.abdatytch.auth_service.exception.AuthException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Twilio reste une implémentation complète, donc elle se teste.
 *
 * <p>Elle n'avait aucun test. Son traitement d'erreur — qui remplace
 * l'exception du SDK pour que le contenu du SMS ne remonte pas dans les
 * journaux d'un appelant — n'était atteignable qu'avec un appel réseau réel,
 * c'est-à-dire jamais. {@code TwilioSmsProvider.creerMessage} existe pour
 * ouvrir cette couture.
 */
@DisplayName("TwilioSmsProvider")
class TwilioSmsProviderTest {

    // Valeurs FACTICES : voir la note identique dans AfrikSmsProviderTest.
    private static final String SID = "AC00000000000000000000000000000000";
    private static final String JETON = "00000000000000000000000000000000";
    private static final String EXPEDITEUR = "+10000000000";
    private static final String NUMERO = "+22890909090";
    private static final String CONTENU = "Votre code Restart est 482913";

    private ListAppender<ILoggingEvent> journaux;
    private Logger loggerDuFournisseur;

    @BeforeEach
    void capturerLesJournaux() {
        journaux = new ListAppender<>();
        journaux.start();
        loggerDuFournisseur = (Logger) LoggerFactory.getLogger(TwilioSmsProvider.class);
        loggerDuFournisseur.addAppender(journaux);
    }

    @AfterEach
    void relacherLesJournaux() {
        loggerDuFournisseur.detachAppender(journaux);
        journaux.stop();
    }

    private List<String> journauxEmis() {
        return journaux.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Fournisseur dont l'appel au SDK est remplacé : aucune connexion réseau.
     *
     * <p>{@code init()} n'est volontairement pas appelé, pour ne pas armer
     * l'état statique global de {@code Twilio} depuis un test.
     */
    private static final class TwilioDEssai extends TwilioSmsProvider {
        private final RuntimeException panne;
        private final List<String> appels = new ArrayList<>();

        TwilioDEssai(String sid, String jeton, String expediteur, RuntimeException panne) {
            super(sid, jeton, expediteur);
            this.panne = panne;
        }

        @Override
        void creerMessage(String numeroDeTelephone, String contenu) {
            appels.add(numeroDeTelephone + "|" + contenu);
            if (panne != null) {
                throw panne;
            }
        }
    }

    @Nested
    @DisplayName("Configuration")
    class Configuration {

        @Test
        @DisplayName("le nom est celui attendu dans sms.provider")
        void nom() {
            assertThat(new TwilioDEssai(SID, JETON, EXPEDITEUR, null).nom())
                    .isEqualTo("twilio")
                    .isEqualTo(TwilioSmsProvider.NOM);
        }

        @Test
        @DisplayName("estConfigure exige les trois valeurs")
        void exigeLesTroisValeurs() {
            assertThat(new TwilioDEssai(SID, JETON, EXPEDITEUR, null).estConfigure()).isTrue();
            assertThat(new TwilioDEssai("", JETON, EXPEDITEUR, null).estConfigure()).isFalse();
            assertThat(new TwilioDEssai(SID, "", EXPEDITEUR, null).estConfigure()).isFalse();
            assertThat(new TwilioDEssai(SID, JETON, "", null).estConfigure()).isFalse();
        }

        @Test
        @DisplayName("un null de configuration ne provoque pas de NullPointerException")
        void nullTolere() {
            assertThat(new TwilioDEssai(null, null, null, null).estConfigure()).isFalse();
        }

        @Test
        @DisplayName("non configuré : échec immédiat, sans appel au SDK")
        void nonConfigureNAppellePas() {
            var sansJeton = new TwilioDEssai(SID, "", EXPEDITEUR, null);

            StepVerifier.create(sansJeton.envoyer(NUMERO, CONTENU))
                    .verifyError(AuthException.class);

            assertThat(sansJeton.appels).isEmpty();
        }
    }

    @Nested
    @DisplayName("Envoi")
    class Envoi {

        @Test
        @DisplayName("le message est remis au SDK, et le succès journalisé")
        void remisAuSdk() {
            var provider = new TwilioDEssai(SID, JETON, EXPEDITEUR, null);

            StepVerifier.create(provider.envoyer(NUMERO, CONTENU)).verifyComplete();

            assertThat(provider.appels).containsExactly(NUMERO + "|" + CONTENU);
            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("succès"));
        }

        /**
         * La raison d'être de ce fichier.
         *
         * <p>Les {@code ApiException} de Twilio portent dans leur message l'URL
         * de l'API appelée, et selon le cas une description de la requête
         * refusée. Envelopper cette exception — {@code new AuthException(code,
         * e)} — suffirait à la faire ressortir : {@code VerificationCodeServiceImpl}
         * journalise l'erreur reçue par {@code log.error(..., phoneNumber,
         * error)}, et un logger imprime toute la chaîne des causes. Le contenu
         * du SMS, donc le code à usage unique, atterrirait dans les journaux —
         * depuis un fichier qu'on n'aurait pas pensé à relire.
         */
        @Test
        @DisplayName("l'exception du SDK est remplacée, jamais enveloppée")
        void exceptionDuSdkRemplacee() {
            RuntimeException panneBavarde = new RuntimeException(
                    "Unable to create record: POST https://api.twilio.com/2010-04-01/"
                            + "Accounts/" + SID + "/Messages.json Body=" + CONTENU);
            var provider = new TwilioDEssai(SID, JETON, EXPEDITEUR, panneBavarde);

            StepVerifier.create(provider.envoyer(NUMERO, CONTENU))
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(AuthException.class);
                        assertThat(erreur.getMessage())
                                .doesNotContain("482913")
                                .doesNotContain(SID);
                        assertThat(erreur.getCause())
                                .as("une cause bavarde serait journalisee par l'appelant")
                                .isNull();
                    });
        }

        @Test
        @DisplayName("aucun journal ne contient le contenu du SMS sur un échec")
        void aucuneFuiteParLesJournaux() {
            RuntimeException panneBavarde = new RuntimeException("echec avec " + CONTENU);
            var provider = new TwilioDEssai(SID, JETON, EXPEDITEUR, panneBavarde);

            StepVerifier.create(provider.envoyer(NUMERO, CONTENU))
                    .verifyError(AuthException.class);

            assertThat(journauxEmis())
                    .isNotEmpty()
                    .allSatisfy(ligne -> assertThat(ligne)
                            .doesNotContain(CONTENU)
                            .doesNotContain("482913"));
        }

        @Test
        @DisplayName("un échec ne journalise aucun succès")
        void unEchecNeJournalisePasDeSucces() {
            var provider = new TwilioDEssai(SID, JETON, EXPEDITEUR,
                    new RuntimeException("panne"));

            StepVerifier.create(provider.envoyer(NUMERO, CONTENU))
                    .verifyError(AuthException.class);

            assertThat(journauxEmis())
                    .allSatisfy(ligne -> assertThat(ligne).doesNotContain("succès"));
        }
    }
}
