package com.abdatytch.user_service.service;

import com.abdatytch.user_service.constants.FileStorageConstants;
import com.abdatytch.user_service.exception.FileStorageException;
import com.abdatytch.user_service.service.impl.LocalFileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Envoi de fichier par le chemin reactif.
 *
 * <h2>La faille que ces tests ferment</h2>
 *
 * {@code LocalFileStorageService} comporte une methode
 * {@code isValidImage(MultipartFile)} qui applique une liste blanche
 * d'extensions et une taille maximale. Elle etait <b>inatteignable</b> : sa
 * signature prend un {@code MultipartFile}, le type bloquant du monde servlet,
 * et le seul appelant etait la surcharge bloquante de {@code storeFile}. Or le
 * controleur emprunte la surcharge reactive, qui prend un {@code FilePart}.
 *
 * <p>Cette surcharge-la ne validait <b>rien</b> : ni extension, ni taille.
 * L'extension etait reprise telle quelle de {@code filePart.filename()}, donc
 * fournie par l'appelant. Une validation qui existe et qu'on n'atteint jamais ne
 * protege rien — meme nature que les 33 {@code @PreAuthorize} inertes trouves
 * dans auth-service.
 *
 * <p>Deux precisions, pour ne pas exagerer la portee :
 * <ul>
 *   <li>il n'y avait <b>pas</b> de traversee de repertoire :
 *       {@code lastIndexOf(".")} empeche qu'un {@code ..} figure dans
 *       l'extension, puisqu'il faudrait un point supplementaire apres lui ;</li>
 *   <li>un fichier SVG ou HTML depose n'etait pas directement executable :
 *       {@code FileReactiveController} impose {@code image/jpeg} au moment de
 *       servir. Mais l'en-tete {@code nosniff} manquait, laissant la porte au
 *       reniflement de contenu.</li>
 * </ul>
 */
@DisplayName("Envoi de fichier reactif - validation et plafond")
class LocalFileStorageReactiveTest {

    private static final String UTILISATEUR = "utilisateur-1";

    private LocalFileStorageService service;

    @BeforeEach
    void setUp(@TempDir Path repertoire) {
        service = new LocalFileStorageService();
        ReflectionTestUtils.setField(service, "uploadDir", repertoire.toString());
        ReflectionTestUtils.setField(service, "baseUrl", "http://exemple.test/files");
    }

    /** Partie multipart synthetique, de nom et de taille choisis. */
    private FilePart partie(String nom, int octets) {
        var fabrique = new DefaultDataBufferFactory();
        byte[] contenu = new byte[octets];
        // Un contenu non nul, pour que l'ecriture ait quelque chose a faire.
        java.util.Arrays.fill(contenu, (byte) 'A');

        return new FilePart() {
            @Override
            public String filename() {
                return nom;
            }

            @Override
            public String name() {
                return "file";
            }

            @Override
            public HttpHeaders headers() {
                return new HttpHeaders();
            }

            @Override
            public Flux<DataBuffer> content() {
                // Decoupe en morceaux : c'est ainsi qu'arrive un envoi reel, et
                // c'est ce qui rend le comptage progressif necessaire.
                int morceau = 8 * 1024;
                return Flux.fromStream(Stream.iterate(0, i -> i + morceau)
                                .limit((octets + morceau - 1) / morceau == 0
                                        ? 1 : (octets + morceau - 1) / morceau))
                        .map(debut -> {
                            int fin = Math.min(debut + morceau, octets);
                            DataBuffer tampon = fabrique.allocateBuffer(Math.max(fin - debut, 1));
                            if (fin > debut) {
                                tampon.write(contenu, debut, fin - debut);
                            }
                            return tampon;
                        });
            }

            @Override
            public Mono<Void> transferTo(Path dest) {
                return Mono.error(new UnsupportedOperationException(
                        "transferTo n'est plus utilise : l'ecriture passe par "
                                + "DataBufferUtils pour pouvoir compter les octets"));
            }
        };
    }

    // ==================================================================
    @Nested
    @DisplayName("Liste blanche d'extensions")
    class Extensions {

        @ParameterizedTest(name = "{0} est accepte")
        @ValueSource(strings = {"photo.jpg", "photo.JPEG", "photo.png", "image.gif", "image.webp"})
        @DisplayName("les extensions d'images sont acceptees, casse indifferente")
        void extensionsAcceptees(String nom) {
            StepVerifier.create(service.storeFile(partie(nom, 1024), UTILISATEUR))
                    .assertNext(url -> assertThat(url).startsWith("http://exemple.test/files/"))
                    .verifyComplete();
        }

        @ParameterizedTest(name = "{0} est refuse")
        @ValueSource(strings = {"x.svg", "x.html", "x.jsp", "x.php", "x.sh", "x.exe", "sansextension"})
        @DisplayName("tout le reste est refuse")
        void extensionsRefusees(String nom) {
            // Ces noms passaient avant : l'extension etait reprise telle quelle.
            StepVerifier.create(service.storeFile(partie(nom, 1024), UTILISATEUR))
                    .expectError(FileStorageException.class)
                    .verify();
        }

        @Test
        @DisplayName("l'extension enregistree est reconstruite, pas recopiee du nom fourni")
        void extensionReconstruite(@TempDir Path ignore) {
            // Le nom contient des majuscules et du texte avant le point. Le
            // fichier ecrit doit porter une extension normalisee, issue de la
            // liste blanche — et non un fragment du nom de l'appelant.
            StepVerifier.create(service.storeFile(partie("../../Photo.PNG", 512), UTILISATEUR))
                    .assertNext(url -> assertThat(url)
                            .endsWith(".png")
                            .doesNotContain("..")
                            .doesNotContain("Photo"))
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Plafond de taille")
    class Plafond {

        @Test
        @DisplayName("un fichier sous le plafond passe")
        void sousLePlafond() {
            int taille = (int) FileStorageConstants.MAX_FILE_SIZE - 1024;

            StepVerifier.create(service.storeFile(partie("photo.jpg", taille), UTILISATEUR))
                    .expectNextCount(1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un fichier au-dela du plafond est refuse")
        void auDelaDuPlafond() {
            // Avant, aucune limite : un seul appel pouvait remplir le disque, et
            // ce service ecrit dans le meme systeme de fichiers que les journaux.
            int taille = (int) FileStorageConstants.MAX_FILE_SIZE + 1024;

            StepVerifier.create(service.storeFile(partie("photo.jpg", taille), UTILISATEUR))
                    .expectError(FileStorageException.class)
                    .verify();
        }

        @Test
        @DisplayName("le fichier partiel est supprime apres un depassement")
        void partielSupprime(@TempDir Path repertoire) {
            // Conserver ce qu'on vient de refuser serait absurde, et laisserait
            // malgre tout grossir le disque.
            ReflectionTestUtils.setField(service, "uploadDir", repertoire.toString());
            int taille = (int) FileStorageConstants.MAX_FILE_SIZE + 1024;

            StepVerifier.create(service.storeFile(partie("photo.jpg", taille), UTILISATEUR))
                    .expectError(FileStorageException.class)
                    .verify();

            Path dossier = repertoire.resolve(UTILISATEUR);
            if (Files.isDirectory(dossier)) {
                try (var contenu = Files.list(dossier)) {
                    assertThat(contenu.toList())
                            .withFailMessage("un fichier partiel subsiste apres refus")
                            .isEmpty();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        }

        @Test
        @DisplayName("le plafond ne se fie pas a la taille annoncee par l'appelant")
        void plafondIndependantDeLEnTete() {
            // Les en-tetes de la partie sont vides dans ce test : aucun
            // Content-Length n'est fourni. Le refus doit quand meme survenir,
            // puisqu'il repose sur les octets REELLEMENT recus. S'y fier
            // laisserait passer un envoi annoncant 1 Ko et en transmettant un
            // gigaoctet.
            int taille = (int) FileStorageConstants.MAX_FILE_SIZE + 1;

            FilePart sansEnTete = partie("photo.jpg", taille);
            assertThat(sansEnTete.headers().getContentLength()).isEqualTo(-1);

            StepVerifier.create(service.storeFile(sansEnTete, UTILISATEUR))
                    .expectError(FileStorageException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Ecriture effective")
    class Ecriture {

        @Test
        @DisplayName("le contenu est reellement ecrit sur le disque")
        void contenuEcrit(@TempDir Path repertoire) {
            // Sans cette verification, tous les tests ci-dessus pourraient
            // passer avec une implementation qui ne stocke rien.
            ReflectionTestUtils.setField(service, "uploadDir", repertoire.toString());

            StepVerifier.create(service.storeFile(partie("photo.png", 4096), UTILISATEUR))
                    .expectNextCount(1)
                    .verifyComplete();

            Path dossier = repertoire.resolve(UTILISATEUR);
            try (var contenu = Files.list(dossier)) {
                var fichiers = contenu.toList();
                assertThat(fichiers).hasSize(1);
                assertThat(Files.size(fichiers.get(0))).isEqualTo(4096);
                assertThat(Files.readString(fichiers.get(0), StandardCharsets.UTF_8))
                        .startsWith("AAAA");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Suppression de la photographie")
    class Suppression {

        /**
         * Le chemin se deduit de l'URL, et NON de la base d'URL courante.
         *
         * <p>L'ancienne version faisait {@code fileUrl.replace(baseUrl + "/", "")} :
         * la suppression etait liee a la valeur du moment de
         * {@code file.base.url}. Une URL enregistree sous une base differente ne
         * correspondait plus, le chemin obtenu etait l'URL entiere,
         * {@code Files.exists} rendait {@code false}, et la methode sortait
         * <b>sans rien supprimer ni rien signaler</b>.
         *
         * <p>Ce n'est pas theorique : la base d'URL designait le port du service
         * au lieu de la passerelle, et a du etre corrigee. Sans ce correctif,
         * toutes les photos deja enregistrees devenaient ineffacables — en
         * silence, sur le chemin d'un effacement RGPD.
         */
        @Test
        @DisplayName("une URL enregistrée sous une AUTRE base est quand même supprimée")
        void baseDurlDifferente(@TempDir Path repertoire) throws Exception {
            service = new LocalFileStorageService();
            ReflectionTestUtils.setField(service, "uploadDir", repertoire.toString());
            ReflectionTestUtils.setField(service, "baseUrl", "http://passerelle.test/api/v1/files");

            Path dossier = repertoire.resolve("compte-7");
            Files.createDirectories(dossier);
            Path photo = dossier.resolve("photo.jpg");
            Files.writeString(photo, "contenu");

            // URL de l'ANCIENNE base : hote et prefixe differents.
            service.deleteProfilePicture("http://localhost:8083/api/v1/files/compte-7/photo.jpg");

            assertThat(photo)
                    .withFailMessage("la photographie subsiste : la suppression depend "
                            + "encore de la base d'URL courante")
                    .doesNotExist();
        }

        @Test
        @DisplayName("une URL avec paramètre de requête désigne le même fichier")
        void parametreDeRequeteIgnore(@TempDir Path repertoire) throws Exception {
            service = new LocalFileStorageService();
            ReflectionTestUtils.setField(service, "uploadDir", repertoire.toString());
            ReflectionTestUtils.setField(service, "baseUrl", "http://passerelle.test/api/v1/files");

            Path dossier = repertoire.resolve("compte-7");
            Files.createDirectories(dossier);
            Path photo = dossier.resolve("photo.jpg");
            Files.writeString(photo, "contenu");

            service.deleteProfilePicture(
                    "http://passerelle.test/api/v1/files/compte-7/photo.jpg?v=2");

            assertThat(photo).doesNotExist();
        }

        @Test
        @DisplayName("la traversée de répertoire est refusée")
        void traverseeRefusee(@TempDir Path repertoire) throws Exception {
            service = new LocalFileStorageService();
            ReflectionTestUtils.setField(service, "uploadDir", repertoire.resolve("envois").toString());
            ReflectionTestUtils.setField(service, "baseUrl", "http://passerelle.test/api/v1/files");

            Files.createDirectories(repertoire.resolve("envois"));
            Path horsZone = repertoire.resolve("secret.txt");
            Files.writeString(horsZone, "a ne pas supprimer");

            // La valeur vient de notre propre base, mais une colonne se modifie.
            service.deleteProfilePicture(
                    "http://passerelle.test/api/v1/files/../secret.txt");

            assertThat(horsZone)
                    .withFailMessage("un segment « .. » dans l'URL stockee a permis de "
                            + "supprimer hors du repertoire d'envoi")
                    .exists();
        }

        @Test
        @DisplayName("une URL nulle ou vide ne lève rien")
        void urlAbsente() {
            service.deleteProfilePicture(null);
            service.deleteProfilePicture("");
            // Aucune exception : l'effacement d'un compte sans photo est le cas
            // courant, pas une anomalie.
        }
    }
}
