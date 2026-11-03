package com.abdatytch.auth_service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Garde-fou : Flyway est-il en mesure de migrer, ou seulement déclaré ?
 *
 * <h2>Ce que ce test a trouvé en étant écrit</h2>
 *
 * {@code auth_service_db} contenait <b>zéro table</b>, et pas même la table
 * {@code flyway_schema_history}. Les trois migrations étaient pourtant dans le
 * jar, {@code spring.flyway.enabled} valait {@code true}, le pilote JDBC et
 * {@code flyway-core} étaient sur le classpath, et le service démarrait en
 * bonne santé — {@code r2dbc: UP} compris.
 *
 * <p>Il manquait {@code org.springframework.boot:spring-boot-flyway}. Spring
 * Boot 4 a éclaté ses autoconfigurations en modules distincts :
 * {@code FlywayAutoConfiguration} y vit désormais. Sans ce module,
 * {@code flyway-core} est une bibliothèque que <b>personne n'appelle</b>, et
 * les propriétés {@code spring.flyway.*} sont lues par personne. Aucune erreur,
 * aucun avertissement.
 *
 * <p>Le symptôme n'apparaissait qu'à l'autre bout de la chaîne, et sous une
 * forme qui ne désignait pas la cause :
 *
 * <pre>
 * Échec du seeding : bad SQL grammar [SELECT ... FROM "users" WHERE ...]
 * </pre>
 *
 * Une table absente, donc aucun compte administrateur — donc aucune connexion
 * possible, donc aucun endpoint protégé testable. Une dépendance manquante
 * rendait tout le service inutilisable, sans rien dire.
 *
 * <h2>Pourquoi un contrôle de classpath, et non un test d'intégration</h2>
 *
 * {@code AuthPersistenceIntegrationTest} vérifie la persistance sur un
 * Testcontainer, et il passait : Flyway y est invoqué autrement, ou le schéma y
 * est posé par le conteneur. Un test qui passe sur une infrastructure jetable ne
 * dit rien du déploiement réel.
 *
 * <p>Ce qui manquait est <b>vérifiable sans base</b> : la classe
 * d'autoconfiguration est présente, ou elle ne l'est pas.
 */
@DisplayName("Flyway peut réellement migrer, et pas seulement être déclaré")
class FlywayEstReellementActifTest {

    /**
     * La classe qui lit {@code spring.flyway.*} et lance la migration.
     *
     * <p>Son nom a changé en Spring Boot 4 : elle était dans
     * {@code org.springframework.boot.autoconfigure.flyway}, elle est désormais
     * dans {@code org.springframework.boot.flyway.autoconfigure}. Une mise à
     * niveau qui déplace une autoconfiguration hors du module qu'on a en
     * dépendance la désactive en silence.
     */
    private static final String AUTOCONFIGURATION =
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";

    @Test
    @DisplayName("l'autoconfiguration Flyway est sur le classpath")
    void autoconfigurationPresente() {
        assertThatCode(() -> Class.forName(AUTOCONFIGURATION))
                .withFailMessage("""
                        %s est absent du classpath.

                        flyway-core seul ne migre RIEN : il met la bibliothèque à
                        disposition, mais personne ne lit spring.flyway.* et aucune
                        migration ne s'exécute. Aucune erreur n'est émise, et le
                        service démarre en bonne santé sur une base vide.

                        Ajouter à auth-service/pom.xml :
                          org.springframework.boot:spring-boot-flyway""",
                        AUTOCONFIGURATION)
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("le pilote JDBC est présent, Flyway n'a pas d'API réactive")
    void piloteJdbcPresent() {
        // R2DBC ne suffit pas : Flyway passe par JDBC, uniquement au demarrage.
        // Un service migre en R2DBC qui retirerait le pilote JDBC desactiverait
        // Flyway de la meme facon silencieuse.
        assertThatCode(() -> Class.forName("org.postgresql.Driver"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("des migrations existent à l'emplacement que la configuration déclare")
    void migrationsPresentes() throws IOException {
        // `locations: classpath:db/migration` dans config-repo. Un chemin qui ne
        // correspond a rien laisserait Flyway s'executer sans rien appliquer, et
        // creer une table d'historique vide — une base vide avec toutes les
        // apparences d'une migration reussie.
        Path dossier = Path.of("src/main/resources/db/migration");
        assertThat(dossier)
                .withFailMessage("dossier de migrations introuvable : %s",
                        dossier.toAbsolutePath())
                .exists();

        try (Stream<Path> fichiers = Files.list(dossier)) {
            List<String> sql = fichiers
                    .map(p -> p.getFileName().toString())
                    .filter(nom -> nom.endsWith(".sql"))
                    .sorted()
                    .toList();

            assertThat(sql)
                    .withFailMessage("aucune migration .sql : Flyway créerait une table "
                            + "d'historique vide sur une base vide")
                    .isNotEmpty();

            // Le prefixe de version est ce qui rend un fichier migrable. Un
            // fichier mal nomme est IGNORE par Flyway, sans erreur.
            assertThat(sql)
                    .allSatisfy(nom -> assertThat(nom)
                            .withFailMessage("migration mal nommée (%s) : Flyway ignore "
                                    + "en silence un fichier sans préfixe V<n>__", nom)
                            .matches("V\\d+__.+\\.sql"));
        }
    }
}
