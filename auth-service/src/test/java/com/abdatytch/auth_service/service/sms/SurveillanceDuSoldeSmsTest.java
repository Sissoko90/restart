package com.abdatytch.auth_service.service.sms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.service.sms.IAdministrationSms.SoldeParPays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La tâche qui regarde le solde pour nous.
 *
 * <p>Ce qui s'y vérifie n'est pas le {@code cron} — Spring s'en charge — mais
 * les deux décisions qu'elle prend : <b>quand s'abstenir</b>, et <b>à quel
 * niveau alerter</b>. Une alerte au mauvais niveau ne sert à rien : noyée en
 * {@code INFO}, elle ne réveille personne ; criée en {@code ERROR} pour un solde
 * confortable, elle apprend à ignorer la ligne.
 */
@DisplayName("SurveillanceDuSoldeSms")
class SurveillanceDuSoldeSmsTest {

    private ListAppender<ILoggingEvent> journaux;
    private Logger loggerDeLaSurveillance;

    @BeforeEach
    void capturerLesJournaux() {
        journaux = new ListAppender<>();
        journaux.start();
        loggerDeLaSurveillance = (Logger) LoggerFactory.getLogger(SurveillanceDuSoldeSms.class);
        loggerDeLaSurveillance.addAppender(journaux);
    }

    @AfterEach
    void relacherLesJournaux() {
        loggerDeLaSurveillance.detachAppender(journaux);
        journaux.stop();
    }

    /**
     * Administration d'essai : compte les appels, sans reseau.
     *
     * <p>Implemente {@code IAdministrationSms} au lieu d'heriter de
     * {@code AfrikSmsAdministration}. L'heritage obligeait a appeler un
     * constructeur a huit arguments dont aucun ne servait, uniquement pour
     * redefinir une methode — et il faisait porter au test la construction
     * d'un {@code WebClient}.
     */
    private static final class AdministrationDEssai implements IAdministrationSms {
        private final AtomicInteger appels = new AtomicInteger();
        private final Mono<List<SoldeParPays>> reponse;

        AdministrationDEssai(Mono<List<SoldeParPays>> reponse) {
            this.reponse = reponse;
        }

        @Override
        public Mono<List<SoldeParPays>> consulterSolde() {
            appels.incrementAndGet();
            return reponse;
        }

        @Override
        public Mono<String> declarerUrlDeRappel() {
            // Jamais appelee par la surveillance. Une erreur plutot qu'un
            // Mono vide : si elle venait a l'etre, le test doit le dire.
            throw new UnsupportedOperationException(
                    "la surveillance ne declare pas l'URL de rappel");
        }
    }

    private SurveillanceDuSoldeSms surveillance(
            AdministrationDEssai administration, boolean active, boolean smsActif,
            String fournisseur, int seuil) {
        return new SurveillanceDuSoldeSms(administration, active, smsActif, fournisseur, seuil);
    }

    private List<ILoggingEvent> evenements() {
        return List.copyOf(journaux.list);
    }

    private boolean aUnEvenement(Level niveau, String fragment) {
        return evenements().stream().anyMatch(e -> e.getLevel() == niveau
                && e.getFormattedMessage().contains(fragment));
    }

    @Nested
    @DisplayName("Quand s'abstenir")
    class QuandSAbstenir {

        private AdministrationDEssai administration;

        @BeforeEach
        void preparer() {
            administration = new AdministrationDEssai(
                    Mono.just(List.of(new SoldeParPays("Mali", 5000))));
        }

        @Test
        @DisplayName("surveillance coupée : aucun appel")
        void surveillanceCoupee() {
            surveillance(administration, false, true, "afriksms", 100).verifierLeSolde();
            assertThat(administration.appels).hasValue(0);
        }

        @Test
        @DisplayName("sms.enabled faux : aucun appel, aucun crédit ne se consomme")
        void envoiCoupe() {
            surveillance(administration, true, false, "afriksms", 100).verifierLeSolde();
            assertThat(administration.appels).hasValue(0);
        }

        /**
         * La consultation de solde est <b>propre à AfrikSMS</b>.
         *
         * <p>Sous Twilio, l'appeler interrogerait un compte qu'on n'utilise
         * pas, et en sortirait une alerte sur un crédit qui ne sert à rien —
         * une alerte fausse, donc une alerte qu'on apprend à ignorer.
         */
        @ParameterizedTest(name = "fournisseur = {0}")
        @ValueSource(strings = {"twilio", "orange", ""})
        void fournisseurAutreQueAfriksms(String fournisseur) {
            surveillance(administration, true, true, fournisseur, 100).verifierLeSolde();
            assertThat(administration.appels).hasValue(0);
        }

        @Test
        @DisplayName("afriksms, quelle que soit la casse : le relevé a lieu")
        void afriksmsQuelleQueSoitLaCasse() {
            surveillance(administration, true, true, "  AfrikSMS  ", 100).verifierLeSolde();
            assertThat(administration.appels).hasValue(1);
        }

        @Test
        @DisplayName("un fournisseur nul ne provoque pas de NullPointerException")
        void fournisseurNul() {
            surveillance(administration, true, true, null, 100).verifierLeSolde();
            assertThat(administration.appels).hasValue(0);
        }
    }

    @Nested
    @DisplayName("À quel niveau alerter")
    class NiveauDAlerte {

        private SurveillanceDuSoldeSms surveillanceAvecSeuil(int seuil) {
            return surveillance(new AdministrationDEssai(Mono.empty()),
                    true, true, "afriksms", seuil);
        }

        /**
         * L'exemple de la documentation AfrikSMS montre un solde à {@code -2}.
         *
         * <p>Un seuil testé par {@code == 0} manquerait donc exactement le cas
         * où le crédit est déjà dépassé.
         */
        @ParameterizedTest(name = "solde {0} -> {1}")
        @CsvSource({
                "-2, ERROR",
                "0, ERROR",
                "1, WARN",
                "100, WARN",
                "101, INFO",
                "5000, INFO",
        })
        void niveauSelonLeSolde(int solde, String niveauAttendu) {
            surveillanceAvecSeuil(100)
                    .alerterSiNecessaire(List.of(new SoldeParPays("Mali", solde)));

            assertThat(evenements())
                    .singleElement()
                    .satisfies(e -> assertThat(e.getLevel().toString()).isEqualTo(niveauAttendu));
        }

        @Test
        @DisplayName("le seuil d'alerte est configurable")
        void seuilConfigurable() {
            surveillanceAvecSeuil(1000)
                    .alerterSiNecessaire(List.of(new SoldeParPays("Mali", 500)));

            // Avec le seuil par defaut de 100, 500 serait un simple INFO.
            assertThat(aUnEvenement(Level.WARN, "500")).isTrue();
        }

        @Test
        @DisplayName("chaque pays est évalué séparément")
        void chaquePaysSeparement() {
            surveillanceAvecSeuil(100).alerterSiNecessaire(List.of(
                    new SoldeParPays("Burkina-Faso", 0),
                    new SoldeParPays("Mali", -2),
                    new SoldeParPays("Togo", 1500)));

            // Un solde confortable sur un pays ne doit pas masquer un credit
            // epuise sur un autre : les SMS vers le Mali ne partent plus.
            assertThat(aUnEvenement(Level.ERROR, "Burkina-Faso")).isTrue();
            assertThat(aUnEvenement(Level.ERROR, "Mali")).isTrue();
            assertThat(aUnEvenement(Level.INFO, "Togo")).isTrue();
        }

        @Test
        @DisplayName("une liste vide est signalée, pas prise pour un solde sain")
        void listeVide() {
            surveillanceAvecSeuil(100).alerterSiNecessaire(List.of());

            // Ne rien journaliser laisserait croire que tout va bien, alors que
            // le releve n'a rien rendu.
            assertThat(aUnEvenement(Level.WARN, "aucun solde")).isTrue();
        }
    }

    @Nested
    @DisplayName("Robustesse de la tâche")
    class Robustesse {

        /**
         * {@code ScheduledTasksConfig} a connu, dans ce dépôt, trois purges qui
         * ne purgeaient rien pendant que les journaux annonçaient leur bon
         * déroulement. Une tâche périodique muette est indistinguable d'une
         * tâche qui fonctionne.
         */
        @Test
        @DisplayName("un échec de relevé est signalé et ne propage pas")
        void echecSignaleEtNonPropage() {
            var enPanne = new AdministrationDEssai(
                    Mono.error(new AuthException("fournisseur injoignable")));

            // Ne doit pas lever : une exception ici interromprait l'ordonnanceur.
            surveillance(enPanne, true, true, "afriksms", 100).verifierLeSolde();

            assertThat(aUnEvenement(Level.ERROR, "impossible")).isTrue();
        }

        @Test
        @DisplayName("le relevé s'exécute vraiment, le Mono n'est pas jeté")
        void leMonoEstSouscrit() {
            var administration = new AdministrationDEssai(
                    Mono.just(List.of(new SoldeParPays("Mali", 0))));

            surveillance(administration, true, true, "afriksms", 100).verifierLeSolde();

            // Deux verifications distinctes : l'appel a eu lieu, ET son resultat
            // a ete traite. La premiere seule passerait si le Mono rendu etait
            // construit puis jete — le defaut exact des anciennes purges.
            assertThat(administration.appels).hasValue(1);
            assertThat(aUnEvenement(Level.ERROR, "Mali")).isTrue();
        }
    }
}
