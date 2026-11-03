package com.abdatytch.media_service;

import com.abdatytch.media_service.service.impl.DocumentQualityServiceImpl;
import com.abdatytch.media_service.service.impl.EncryptionServiceImpl;
import com.abdatytch.media_service.service.impl.ImageCompressionServiceImpl;
import com.abdatytch.media_service.service.impl.MalwareDetectionServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Vérifie que le parcours d'upload ne bloque aucun thread réactif.
 *
 * <p>L'upload est le chemin le plus coûteux du service : lecture du fichier,
 * scan malware, compression, analyse de qualité, chiffrement, envoi MinIO. Toutes
 * ces étapes sont intrinsèquement bloquantes (ImageIO, MessageDigest, SDK MinIO)
 * et doivent donc s'exécuter sur {@code Schedulers.boundedElastic()}.
 *
 * <p>Ces tests souscrivent sur {@code Schedulers.parallel()} — surveillé par
 * BlockHound au même titre que l'EventLoop Netty. Si un {@code subscribeOn} venait
 * à être retiré par mégarde, le test échouerait immédiatement.
 *
 * <p>Les composants sont instanciés réellement : aucune infrastructure externe
 * n'est requise pour ceux testés ici (MinIO est exclu, il demanderait un serveur).
 */
@DisplayName("media-service : absence d'appels bloquants (BlockHound)")
class NoBlockingCallsTest {

    private static final int TIMEOUT_SECONDS = 30;

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
    static void installBlockHound() {
        Schedulers.shutdownNow();
        BlockHound.install();
    }

    private ImageCompressionServiceImpl imageCompressionService;
    private MalwareDetectionServiceImpl malwareDetectionService;
    private DocumentQualityServiceImpl documentQualityService;
    private EncryptionServiceImpl encryptionService;

    private byte[] sampleImage;

    @BeforeEach
    void setUp() throws Exception {
        imageCompressionService = new ImageCompressionServiceImpl();
        ReflectionTestUtils.setField(imageCompressionService, "defaultQuality", 0.85f);
        ReflectionTestUtils.setField(imageCompressionService, "maxImageWidth", 4096);
        ReflectionTestUtils.setField(imageCompressionService, "maxImageHeight", 4096);

        malwareDetectionService = new MalwareDetectionServiceImpl();
        documentQualityService = new DocumentQualityServiceImpl();

        encryptionService = new EncryptionServiceImpl();
        // 32 caractères : clé AES-256 factice, réservée aux tests
        ReflectionTestUtils.setField(encryptionService, "masterKey", "test-encryption-master-key-32chr");
        ReflectionTestUtils.setField(encryptionService, "encryptionEnabled", true);

        sampleImage = buildSampleJpeg();
    }

    /**
     * Image JPEG réelle : ImageIO refuse un tableau d'octets arbitraire, et c'est
     * bien le décodage d'image qui nous intéresse ici.
     */
    private byte[] buildSampleJpeg() throws Exception {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 200, 200);
        graphics.setColor(Color.BLACK);
        graphics.drawString("DOCUMENT", 40, 100);
        graphics.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("BlockHound est bien actif : un Thread.sleep sur un thread réactif est détecté")
    void blockHoundIsActive() {
        // Contrôle négatif : sans lui, « aucun blocage détecté » pourrait simplement
        // vouloir dire que BlockHound n'est pas installé.
        Mono<Long> blocking = Mono.fromCallable(() -> {
            Thread.sleep(10);
            return 1L;
        }).subscribeOn(Schedulers.parallel());

        assertThatThrownBy(() -> blocking.block(Duration.ofSeconds(5)))
                .hasRootCauseInstanceOf(BlockingOperationError.class);
    }

    @Test
    @DisplayName("la compression d'image (ImageIO) ne bloque pas le thread appelant")
    void imageCompressionDoesNotBlockCaller() {
        assertThatCode(() -> {
            byte[] compressed = imageCompressionService.compressImage(sampleImage)
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(TIMEOUT_SECONDS));
            assertThat(compressed).isNotEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("le scan de sécurité ne bloque pas le thread appelant")
    void malwareScanDoesNotBlockCaller() {
        assertThatCode(() -> {
            var scan = malwareDetectionService.scanFile(sampleImage, "image/jpeg", "document.jpg")
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(TIMEOUT_SECONDS));
            assertThat(scan).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("le calcul du hash ne bloque pas le thread appelant")
    void hashCalculationDoesNotBlockCaller() {
        assertThatCode(() -> {
            String hash = malwareDetectionService.calculateFileHash(sampleImage)
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(TIMEOUT_SECONDS));
            assertThat(hash).isNotBlank();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("l'analyse de qualité (ImageIO) ne bloque pas le thread appelant")
    void qualityAnalysisDoesNotBlockCaller() {
        assertThatCode(() -> documentQualityService.analyzeImageQuality(sampleImage, "image/jpeg")
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(TIMEOUT_SECONDS)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("le chiffrement AES ne bloque pas le thread appelant")
    void encryptionDoesNotBlockCaller() {
        assertThatCode(() -> {
            byte[] encrypted = encryptionService.encryptDocument(sampleImage, "user-1", "doc-1")
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(TIMEOUT_SECONDS));
            assertThat(encrypted).isNotEmpty();
        }).doesNotThrowAnyException();
    }
}
