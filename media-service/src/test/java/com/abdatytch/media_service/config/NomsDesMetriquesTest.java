package com.abdatytch.media_service.config;

import com.abdatytch.media_service.repository.MediaFileRepository;
import com.abdatytch.media_service.service.DocumentMetricsService;
import com.abdatytch.media_service.service.impl.DocumentMetricsServiceImpl;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les noms que Prometheus voit réellement.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * Micrometer <b>renomme</b> : il convertit les points en tirets bas, ajoute
 * {@code _total} aux compteurs et le nom de l'unité de base aux chronomètres.
 * Un compteur déclaré {@code documents.uploaded.total} et un chronomètre
 * {@code documents.upload.duration} n'apparaissent donc pas sous ces noms-là
 * dans le format d'exposition.
 *
 * <p>Or les tableaux de bord et les règles d'alerte sont écrits à la main, avec
 * le nom qu'on croit. S'il diffère d'un suffixe, le panneau reste vide et
 * l'alerte ne se déclenche jamais — sans que rien ne le signale, puisqu'une
 * requête PromQL sans série correspondante n'est pas une erreur, c'est un
 * résultat vide.
 *
 * <p>Ce dépôt connaît déjà ce défaut sous « alertes muettes », et
 * {@code MetriquesDesAlertesTest} de platform-commons garde le fait qu'une
 * alerte cite une métrique <b>déclarée dans le code</b>. Il ne garde pas que le
 * nom <b>exposé</b> soit celui que la requête emploie. C'est cet écart-ci que ce
 * test ferme.
 *
 * <p>En cas d'échec, le message liste toutes les séries réellement exposées :
 * il doit permettre de corriger la requête sans démarrer le service.
 */
@DisplayName("Noms des metriques exposees a Prometheus")
class NomsDesMetriquesTest {

    /** Capture un nom de série en début de ligne du format d'exposition. */
    private static final Pattern NOM_DE_SERIE =
            Pattern.compile("(?m)^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{|\\s)");

    private String exposition;
    private Set<String> seriesExposees;

    @BeforeEach
    void setUp() {
        PrometheusMeterRegistry registre = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MetricsConfig config = new MetricsConfig();

        // Les memes declarations que la production, sur un registre en memoire.
        Counter envois = config.documentUploadCounter(registre);
        Counter approuves = config.documentApprovedCounter(registre);
        Counter rejetes = config.documentRejectedCounter(registre);
        Counter supprimes = config.documentDeletedCounter(registre);
        Timer chronoEnvoi = config.documentUploadTimer(registre);
        Timer chronoTelechargement = config.documentDownloadTimer(registre);

        envois.increment();
        approuves.increment();
        rejetes.increment();
        supprimes.increment();
        chronoEnvoi.record(Duration.ofMillis(120));
        chronoTelechargement.record(Duration.ofMillis(40));

        // Les trois compteurs etiquetes sont construits a la demande par
        // DocumentMetricsServiceImpl. Ils sont declenches par LE SERVICE REEL, et
        // non recopies a la main : un nom recopie ferait passer ce test alors que
        // la production exposerait autre chose — soit exactement le defaut qu'il
        // est charge d'empecher.
        DocumentMetricsService metriques = new DocumentMetricsServiceImpl(
                envois, approuves, rejetes, supprimes,
                chronoEnvoi, chronoTelechargement, registre);

        metriques.incrementSecurityThreatCounter("KNOWN_MALWARE_SIGNATURE");
        metriques.incrementQualityRejectedCounter("ILLISIBLE");
        metriques.incrementStorageFailureCounter("DEPOT");

        // La jauge s'enregistre dans son constructeur. Le depot n'est pas
        // sollicite ici : seul `rafraichir()` l'interroge, et le nom de la serie
        // est pose des la construction.
        new JaugeFileDeValidation(registre, org.mockito.Mockito.mock(MediaFileRepository.class));

        exposition = registre.scrape();
        seriesExposees = new TreeSet<>();
        Matcher m = NOM_DE_SERIE.matcher(exposition);
        while (m.find()) {
            seriesExposees.add(m.group(1));
        }
    }

    private void exige(String serie) {
        assertThat(seriesExposees)
                .withFailMessage("la serie '%s' n'est PAS exposee. Micrometer renomme : "
                        + "tout tableau de bord ou alerte qui l'interroge restera vide, "
                        + "en silence. Series reellement exposees : %s",
                        serie, seriesExposees)
                .contains(serie);
    }

    @Test
    @DisplayName("les quatre compteurs de documents sont exposes")
    void compteursExposes() {
        exige("documents_uploaded_total");
        exige("documents_approved_total");
        exige("documents_rejected_total");
        exige("documents_deleted_total");
    }

    /**
     * Le compteur de menaces, et son étiquette.
     *
     * <p>L'étiquette a été ajoutée parce que le paramètre était reçu et
     * <b>ignoré</b> : on comptait les menaces sans savoir lesquelles. Le test
     * vérifie donc la série <b>et</b> la présence de l'étiquette — sans elle, le
     * compteur serait exposé mais ne distinguerait rien.
     */
    @Test
    @DisplayName("le compteur de menaces porte bien une etiquette type")
    void compteurDeMenacesEtiquete() {
        exige("documents_security_threats_total");

        assertThat(exposition)
                .withFailMessage("le compteur de menaces est expose SANS etiquette de "
                        + "type : il compte les menaces sans dire lesquelles, ce qui "
                        + "n'oriente aucune decision")
                .contains("type=\"KNOWN_MALWARE_SIGNATURE\"");
    }

    /**
     * Les buckets, sans lesquels aucun quantile n'est calculable.
     *
     * <p>Un {@code Timer} ne publie par défaut que {@code _count}, {@code _sum}
     * et {@code _max}. Le tableau de bord appelle {@code histogram_quantile} sur
     * {@code _bucket} : il fallait {@code publishPercentileHistogram}.
     */
    @Test
    @DisplayName("les chronometres exposent leurs buckets, pour histogram_quantile")
    void bucketsExposes() {
        exige("documents_upload_duration_seconds_bucket");
        exige("documents_download_duration_seconds_bucket");
    }

    @Test
    @DisplayName("les chronometres exposent aussi count et sum")
    void compteEtSommeExposes() {
        exige("documents_upload_duration_seconds_count");
        exige("documents_upload_duration_seconds_sum");
    }

    /**
     * Les trois métriques qui manquaient, et ce que leur absence cachait.
     *
     * <p>Elles étaient documentées en tête du fichier d'alertes comme « non
     * alertables », faute de série derrière. Chacune couvre un angle mort
     * distinct :
     * <ul>
     *   <li><b>rejets qualité</b> — {@code documents_rejected_total} ne compte
     *       que les rejets d'administrateur ; un refus sous 70/100
     *       n'incrémentait rien, donc ni un seuil trop sévère ni un client qui
     *       envoie mal n'était visible ;</li>
     *   <li><b>file de validation</b> — un empilement signifie des inscriptions
     *       bloquées, une panne sans erreur ni latence ;</li>
     *   <li><b>échecs de stockage</b> — la sonde de santé dit si MinIO répond,
     *       pas si les écritures passent.</li>
     * </ul>
     */
    @Test
    @DisplayName("les trois metriques ajoutees sont exposees, avec leurs etiquettes")
    void metriquesAjouteesExposees() {
        exige("documents_quality_rejected_total");
        exige("documents_pending_validation");
        exige("documents_storage_failures_total");

        assertThat(exposition)
                .withFailMessage("le compteur de rejets qualite est expose sans sa raison : "
                        + "ILLISIBLE et SCORE_INSUFFISANT ne se corrigent pas de la meme "
                        + "facon, les confondre n'oriente aucune decision")
                .contains("raison=\"ILLISIBLE\"");

        assertThat(exposition)
                .withFailMessage("le compteur d'echecs de stockage est expose sans son "
                        + "operation : un depot qui echoue et une lecture qui echoue ne "
                        + "disent pas la meme chose")
                .contains("operation=\"DEPOT\"");
    }
}
