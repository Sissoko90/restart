package com.abdatytch.notification_service.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.abdatytch.notification_service.service.impl.SmsServiceImpl;
import com.abdatytch.notification_service.service.sms.AfrikSmsProvider;
import com.abdatytch.notification_service.service.sms.ISmsProvider;
import com.abdatytch.notification_service.service.sms.TwilioSmsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le service d'envoi de SMS n'avait <b>aucun test</b>.
 *
 * <p>C'est la onzième fois dans ce dépôt qu'une protection écrite n'était
 * jamais exercée : les deux fuites du code à usage unique qui vivaient dans
 * {@code sendViaTwilio} auraient été vues par n'importe quel test qui aurait
 * regardé les journaux émis. Celui-ci le fait.
 */
@DisplayName("SmsServiceImpl")
class SmsServiceImplTest {

    private static final String NUMERO = "+22890909090";
    private static final String CONTENU = "Votre code Restart est 482913";

    /** Fournisseur d'essai, qui note ce qu'on lui demande. */
    private static final class FournisseurEspion implements ISmsProvider {
        private final String nom;
        private final boolean configure;
        private final List<String> envois = new ArrayList<>();

        FournisseurEspion(String nom, boolean configure) {
            this.nom = nom;
            this.configure = configure;
        }

        @Override
        public String nom() {
            return nom;
        }

        @Override
        public boolean estConfigure() {
            return configure;
        }

        @Override
        public Mono<Void> envoyer(String numeroDeTelephone, String contenu) {
            envois.add(numeroDeTelephone + "|" + contenu);
            return Mono.empty();
        }
    }

    private FournisseurEspion afriksms;
    private FournisseurEspion twilio;
    private ListAppender<ILoggingEvent> journaux;
    private Logger loggerDuService;

    @BeforeEach
    void preparer() {
        afriksms = new FournisseurEspion(AfrikSmsProvider.NOM, true);
        twilio = new FournisseurEspion(TwilioSmsProvider.NOM, true);
        journaux = new ListAppender<>();
        journaux.start();
        loggerDuService = (Logger) LoggerFactory.getLogger(SmsServiceImpl.class);
        loggerDuService.addAppender(journaux);
    }

    @AfterEach
    void relacher() {
        loggerDuService.detachAppender(journaux);
        journaux.stop();
    }

    private SmsServiceImpl service(String nomDemande, boolean actif, boolean journaliserCodes) {
        return new SmsServiceImpl(List.of(afriksms, twilio), nomDemande, actif,
                journaliserCodes);
    }

    private List<String> journauxEmis() {
        return journaux.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Nested
    @DisplayName("Choix du fournisseur")
    class ChoixDuFournisseur {

        @Test
        @DisplayName("afriksms est le fournisseur retenu")
        void afriksmsParDefaut() {
            StepVerifier.create(service(AfrikSmsProvider.NOM, true, false)
                            .sendSms(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(afriksms.envois).containsExactly(NUMERO + "|" + CONTENU);
            assertThat(twilio.envois).isEmpty();
        }

        @Test
        @DisplayName("twilio reste activable, sans recompilation")
        void twilioActivable() {
            StepVerifier.create(service(TwilioSmsProvider.NOM, true, false)
                            .sendSms(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(twilio.envois).containsExactly(NUMERO + "|" + CONTENU);
            assertThat(afriksms.envois).isEmpty();
        }

        @ParameterizedTest(name = "« {0} » designe afriksms")
        @ValueSource(strings = {"afriksms", "AfrikSMS", "AFRIKSMS", "  afriksms  "})
        @DisplayName("la casse et les espaces d'un .env sont tolérés")
        void casseEtEspaces(String valeurDeEnv) {
            StepVerifier.create(service(valeurDeEnv, true, false).sendSms(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(afriksms.envois).hasSize(1);
        }

        /**
         * Le choix de la défaillance.
         *
         * <p>Un {@code @ConditionalOnProperty} par fournisseur aurait produit,
         * sur une faute de frappe, un contexte <b>sans aucun transporteur</b> :
         * le service démarrait et les SMS cessaient de partir en silence,
         * jusqu'à ce qu'un utilisateur signale ne pas recevoir son code. Ici le
         * démarrage échoue, et le message nomme les valeurs acceptées.
         */
        @ParameterizedTest(name = "« {0} » empeche le demarrage")
        @ValueSource(strings = {"twillio", "afrik-sms", "orange", ""})
        void unNomInconnuEmpecheLeDemarrage(String inconnu) {
            assertThatThrownBy(() -> service(inconnu, true, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("afriksms")
                    .hasMessageContaining("twilio");
        }

        @Test
        @DisplayName("un nom nul empêche aussi le démarrage")
        void nomNul() {
            assertThatThrownBy(() -> service(null, true, false))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Avertissements au démarrage")
    class AvertissementsAuDemarrage {

        @Test
        @DisplayName("un fournisseur retenu mais non configuré est signalé, pas découvert au premier envoi")
        void fournisseurNonConfigure() {
            var sansConfiguration = new FournisseurEspion(AfrikSmsProvider.NOM, false);
            var service = new SmsServiceImpl(List.of(sansConfiguration),
                    AfrikSmsProvider.NOM, true, false);

            service.verifierLaConfiguration();

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne)
                            .contains("afriksms")
                            .contains("configur"));
        }

        @Test
        @DisplayName("sms.enabled = false est signalé au démarrage")
        void envoiCoupe() {
            service(AfrikSmsProvider.NOM, false, false).verifierLaConfiguration();

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("sms.enabled"));
        }

        @Test
        @DisplayName("tout est en place : le fournisseur actif est nommé")
        void toutEnPlace() {
            service(AfrikSmsProvider.NOM, true, false).verifierLaConfiguration();

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("afriksms"));
        }
    }

    @Nested
    @DisplayName("Les deux interrupteurs ne disent pas la même chose")
    class LesDeuxInterrupteurs {

        @Test
        @DisplayName("sms.enabled = false : rien n'est remis au fournisseur")
        void envoiCoupe() {
            StepVerifier.create(service(AfrikSmsProvider.NOM, false, false)
                            .sendSms(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(afriksms.envois).isEmpty();
            assertThat(twilio.envois).isEmpty();
        }

        /**
         * Le défaut que la séparation des deux drapeaux corrige.
         *
         * <p>L'affichage du code était conditionné à {@code !sms.enabled}. Or
         * couper les SMS est une décision d'<b>exploitation</b> — fournisseur en
         * panne, quota épuisé, maintenance — que l'on prend aussi en
         * production. Tous les codes de vérification partaient alors dans les
         * journaux, sans que personne ne l'ait demandé.
         */
        @Test
        @DisplayName("envoi coupé sans l'interrupteur de développement : le code n'est pas affiché")
        void coupeMaisSansAffichage() {
            StepVerifier.create(service(AfrikSmsProvider.NOM, false, false)
                            .sendSms(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(journauxEmis())
                    .allSatisfy(ligne -> assertThat(ligne).doesNotContain("482913"));
        }

        @Test
        @DisplayName("l'interrupteur de développement, explicitement posé, affiche le code")
        void developpementLocal() {
            StepVerifier.create(service(AfrikSmsProvider.NOM, false, true)
                            .sendSms(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(journauxEmis())
                    .anySatisfy(ligne -> assertThat(ligne).contains("482913"));
        }

        @Test
        @DisplayName("envoi actif : le code n'est pas affiché, même avec l'interrupteur de développement")
        void actifEtDeveloppement() {
            // L'affichage sert a lire un code qu'on ne recoit pas. Quand l'envoi
            // fonctionne, on le recoit : il n'y a rien a afficher.
            StepVerifier.create(service(AfrikSmsProvider.NOM, true, true)
                            .sendSms(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(journauxEmis())
                    .allSatisfy(ligne -> assertThat(ligne).doesNotContain("482913"));
        }
    }

    @Nested
    @DisplayName("Journaux de l'envoi")
    class JournauxDeLEnvoi {

        @ParameterizedTest(name = "actif={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("le contenu du SMS n'apparaît jamais, dans aucun des deux états")
        void leContenuNApparaitJamais(boolean actif) {
            StepVerifier.create(service(AfrikSmsProvider.NOM, actif, false)
                            .sendSms(NUMERO, CONTENU))
                    .verifyComplete();

            assertThat(journauxEmis())
                    .isNotEmpty()
                    .allSatisfy(ligne -> assertThat(ligne).doesNotContain(CONTENU));
        }

        @Test
        @DisplayName("une erreur du fournisseur est propagée, non avalée")
        void erreurPropagee() {
            ISmsProvider enPanne = new ISmsProvider() {
                @Override
                public String nom() {
                    return AfrikSmsProvider.NOM;
                }

                @Override
                public boolean estConfigure() {
                    return true;
                }

                @Override
                public Mono<Void> envoyer(String numero, String contenu) {
                    return Mono.error(new IllegalStateException("panne"));
                }
            };

            // Avaler l'erreur ferait croire a l'appelant que le SMS est parti,
            // et VerificationCodeServiceImpl enregistrerait un code que
            // l'utilisateur ne recevra jamais.
            StepVerifier.create(new SmsServiceImpl(List.of(enPanne), AfrikSmsProvider.NOM,
                            true, false).sendSms(NUMERO, CONTENU))
                    .verifyError(IllegalStateException.class);
        }
    }
}
