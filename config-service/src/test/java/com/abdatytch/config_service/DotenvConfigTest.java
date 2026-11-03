package com.abdatytch.config_service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chargement du fichier {@code .env} de repli.
 *
 * <h2>Pourquoi ce chargeur merite des tests</h2>
 *
 * config-service resout lui-meme les substitutions des fichiers de
 * {@code config-repo} avant de les servir : {@code ${SUPER_ADMIN_EMAIL:}} dans
 * {@code auth-service-dev.yml} est remplace par la valeur presente dans
 * l'environnement <b>de config-service</b>. Ce chargeur est donc la source de
 * repli de toute la plateforme quand le coffre n'est pas joignable.
 *
 * <p>Ses defauts ne se manifestaient par aucune erreur. {@code directory("..")}
 * etait relatif au repertoire de travail : lance depuis le module, le chemin
 * designait la racine du depot et tout fonctionnait ; lance depuis la racine, il
 * designait le repertoire au-dessus du depot. Avec {@code ignoreIfMissing()},
 * <b>l'echec etait silencieux</b> — le serveur servait des valeurs vides, et
 * chaque service demarrait avec des champs non renseignes sans qu'aucun ne dise
 * pourquoi.
 *
 * <p>C'est exactement le genre de panne qu'un test attrape et qu'une relecture
 * laisse passer : le code a l'air juste, et il l'est, pour un repertoire de
 * travail sur deux.
 */
@DisplayName("DotenvConfig - chargement du fichier de repli")
class DotenvConfigTest {

    private final DotenvConfig chargeur = new DotenvConfig();

    private void ecrireEnv(Path repertoire, String contenu) throws IOException {
        Files.writeString(repertoire.resolve(".env"), contenu);
    }

    // ==================================================================
    @Nested
    @DisplayName("Recherche de la racine")
    class RechercheDeLaRacine {

        @Test
        @DisplayName("le fichier est trouve dans le repertoire de depart")
        void trouveSurPlace(@TempDir Path racine) throws IOException {
            ecrireEnv(racine, "CLE=valeur");

            assertThat(chargeur.trouverRacine(racine.toFile()))
                    .isNotNull()
                    .satisfies(trouve -> assertThat(trouve.getCanonicalFile())
                            .isEqualTo(racine.toFile().getCanonicalFile()));
        }

        @Test
        @DisplayName("le fichier est trouve en remontant depuis un sous-repertoire")
        void trouveEnRemontant(@TempDir Path racine) throws IOException {
            // Le cas reel : la commande est lancee depuis le module, le fichier
            // est a la racine du depot. C'est ce que l'ancien directory("..")
            // couvrait, et le seul cas qu'il couvrait.
            ecrireEnv(racine, "CLE=valeur");
            Path module = Files.createDirectories(racine.resolve("config-service"));

            assertThat(chargeur.trouverRacine(module.toFile()))
                    .isNotNull()
                    .satisfies(trouve -> assertThat(trouve.getCanonicalFile())
                            .isEqualTo(racine.toFile().getCanonicalFile()));
        }

        @Test
        @DisplayName("le fichier est trouve depuis plusieurs niveaux en dessous")
        void trouveDepuisPlusieursNiveaux(@TempDir Path racine) throws IOException {
            // Ce que l'ancien code ne couvrait pas : lance depuis la racine du
            // depot, ".." designait le repertoire au-dessus et le fichier etait
            // introuvable — silencieusement.
            ecrireEnv(racine, "CLE=valeur");
            Path profond = Files.createDirectories(
                    racine.resolve("module").resolve("target").resolve("classes"));

            assertThat(chargeur.trouverRacine(profond.toFile()))
                    .isNotNull()
                    .satisfies(trouve -> assertThat(trouve.getCanonicalFile())
                            .isEqualTo(racine.toFile().getCanonicalFile()));
        }

        @Test
        @DisplayName("sans fichier, la recherche rend null au lieu de boucler")
        void aucunFichier(@TempDir Path vide) {
            // La remontee s'arrete apres un nombre fixe de niveaux. Sans borne,
            // elle grimperait jusqu'a la racine du systeme et pourrait ramasser
            // un .env appartenant a un autre projet.
            assertThat(chargeur.trouverRacine(vide.toFile())).isNull();
        }

        @Test
        @DisplayName("un repertoire nomme .env n'est pas confondu avec un fichier")
        void repertoireHomonyme(@TempDir Path racine) throws IOException {
            // isFile() et non exists() : un repertoire passerait le second test
            // puis ferait echouer la lecture.
            Files.createDirectory(racine.resolve(".env"));

            assertThat(chargeur.trouverRacine(racine.toFile())).isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Priorite des sources")
    class PrioriteDesSources {

        @Test
        @DisplayName("le fichier est ajoute EN DERNIER, apres les sources existantes")
        void ajouteEnDernier(@TempDir Path racine) throws Exception {
            // Le point le plus important de ce chargeur. Un .env oublie sur un
            // serveur ne doit pas ecraser la configuration injectee par
            // l'orchestrateur ou le coffre : ce serait l'inverse d'un repli.
            //
            // L'ancien addFirst lui donnait la priorite sur tout.
            ecrireEnv(racine, "TEST_DOTENV_EMAIL=repli@exemple.test");

            StandardEnvironment environnement = new StandardEnvironment();
            MutablePropertySources sources = environnement.getPropertySources();
            sources.addFirst(new MapPropertySource("environnementReel",
                    Map.of("TEST_DOTENV_EMAIL", "reel@exemple.test")));

            executerDepuis(racine, environnement);

            assertThat(environnement.getProperty("TEST_DOTENV_EMAIL"))
                    .withFailMessage("le fichier de repli a ecrase l'environnement reel")
                    .isEqualTo("reel@exemple.test");
        }

        @Test
        @DisplayName("le fichier comble ce que l'environnement reel ne fournit pas")
        void combleLesManques(@TempDir Path racine) throws Exception {
            // L'autre moitie du contrat : etre en dernier ne doit pas vouloir
            // dire etre ignore.
            ecrireEnv(racine, "TEST_DOTENV_EMAIL=repli@exemple.test");

            StandardEnvironment environnement = new StandardEnvironment();
            executerDepuis(racine, environnement);

            assertThat(environnement.getProperty("TEST_DOTENV_EMAIL"))
                    .isEqualTo("repli@exemple.test");
        }

        @Test
        @DisplayName("toutes les cles du fichier sont exposees")
        void toutesLesCles(@TempDir Path racine) throws Exception {
            // Les noms portent un prefixe propre au test. La premiere version
            // employait les vrais noms de la plateforme, dont
            // APP_PUBLIC_BASE_URL — que le pom parent definit comme vraie
            // variable d'environnement pour les tests. Le repli cedait donc la
            // priorite, correctement, et le test echouait en accusant le code.
            //
            // Toute assertion sur une valeur de repli doit porter sur un nom
            // absent de l'environnement du processus, sinon elle mesure la
            // priorite des sources au lieu du chargement.
            ecrireEnv(racine, """
                    TEST_DOTENV_EMAIL=tech@exemple.test
                    TEST_DOTENV_PHONE=+22300000000
                    TEST_DOTENV_URL=http://exemple.test
                    """);

            StandardEnvironment environnement = new StandardEnvironment();
            executerDepuis(racine, environnement);

            assertThat(environnement.getProperty("TEST_DOTENV_EMAIL")).isEqualTo("tech@exemple.test");
            assertThat(environnement.getProperty("TEST_DOTENV_PHONE")).isEqualTo("+22300000000");
            assertThat(environnement.getProperty("TEST_DOTENV_URL")).isEqualTo("http://exemple.test");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Aucune fuite")
    class AucuneFuite {

        @Test
        @DisplayName("aucune valeur n'est recopiee dans les proprietes systeme")
        void pasDeProprieteSysteme(@TempDir Path racine) throws Exception {
            // L'ancien code appelait System.setProperty sur chaque entree. Les
            // proprietes systeme vivent pour la duree du processus, apparaissent
            // dans les vidages memoire, et sont lisibles par tout code du meme
            // processus. Le commentaire justifiait cette recopie par une forme
            // de substitution prefixee par "env.", employee nulle part dans le
            // depot.
            String cle = "CLE_DE_TEST_QUI_NE_DOIT_PAS_FUIR";
            ecrireEnv(racine, cle + "=valeur-sensible");

            try {
                executerDepuis(racine, new StandardEnvironment());

                assertThat(System.getProperty(cle))
                        .withFailMessage("la valeur a ete recopiee dans les proprietes systeme")
                        .isNull();
            } finally {
                System.clearProperty(cle);
            }
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Robustesse")
    class Robustesse {

        @Test
        @DisplayName("sans fichier, le demarrage n'echoue pas")
        void aucunFichierNEmpechePasLeDemarrage(@TempDir Path vide) {
            // Le repli manquant est un probleme de configuration, pas une raison
            // de refuser de demarrer : l'environnement reel peut tout fournir.
            StandardEnvironment environnement = new StandardEnvironment();
            int sourcesAvant = compterSources(environnement);

            org.assertj.core.api.Assertions
                    .assertThatCode(() -> executerDepuis(vide, environnement))
                    .doesNotThrowAnyException();

            assertThat(compterSources(environnement))
                    .withFailMessage("une source vide a ete ajoutee alors qu'aucun fichier n'existe")
                    .isEqualTo(sourcesAvant);
        }

        @Test
        @DisplayName("un fichier vide ne fait pas echouer le demarrage")
        void fichierVide(@TempDir Path racine) throws IOException {
            ecrireEnv(racine, "");

            org.assertj.core.api.Assertions
                    .assertThatCode(() -> executerDepuis(racine, new StandardEnvironment()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("une valeur longue sur une seule ligne est lue entierement")
        void valeurLongue(@TempDir Path racine) throws Exception {
            // JWT_DEV_PRIVATE_KEY fait 1624 caracteres sur une ligne. Une
            // troncature produirait une cle invalide, donc un service incapable
            // de signer — avec une erreur survenant bien plus loin.
            String longue = "A".repeat(1624);
            ecrireEnv(racine, "JWT_DEV_PRIVATE_KEY=" + longue);

            StandardEnvironment environnement = new StandardEnvironment();
            executerDepuis(racine, environnement);

            assertThat(environnement.getProperty("JWT_DEV_PRIVATE_KEY")).isEqualTo(longue);
        }
    }

    // ==================================================================

    /**
     * Execute le chargeur comme si le repertoire de travail etait
     * {@code repertoire}.
     *
     * <p>{@code user.dir} est une propriete globale de la JVM : elle est
     * restauree dans tous les cas, sans quoi un test ferait echouer les suivants
     * de maniere difficile a rattacher a sa cause.
     */
    private void executerDepuis(Path repertoire, StandardEnvironment environnement) {
        String precedent = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", repertoire.toAbsolutePath().toString());
            chargeur.postProcessEnvironment(environnement, null);
        } finally {
            System.setProperty("user.dir", precedent);
        }
    }

    private int compterSources(StandardEnvironment environnement) {
        return (int) environnement.getPropertySources().stream().count();
    }
}
