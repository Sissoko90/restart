package com.abdatytch.commons.plateforme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tout module qui PORTE DES MIGRATIONS peut reellement les appliquer.
 *
 * <h2>Le defaut que ce garde-fou ferme</h2>
 *
 * Spring Boot 4 a eclate ses autoconfigurations en modules distincts.
 * {@code FlywayAutoConfiguration} vit desormais dans {@code spring-boot-flyway}
 * et non plus dans {@code spring-boot-autoconfigure} : {@code flyway-core} seul
 * met la BIBLIOTHEQUE sur le chemin de classe, et <b>personne ne lit
 * {@code spring.flyway.*}</b>.
 *
 * <p>Il n'y a alors ni erreur, ni avertissement, ni ligne de journal. La
 * configuration donne toutes les apparences d'etre en vigueur : elle est servie
 * par config-service, visible dans {@code /actuator/env}, et aucune migration
 * ne s'applique.
 *
 * <h2>Ce que la premiere version de ce test ne voyait pas</h2>
 *
 * Elle partait de {@code flyway-core} : <i>un module qui declare la
 * bibliotheque doit declarer l'autoconfiguration</i>. C'est une implication, et
 * une implication dont la premisse est fausse est vraie — donc verte.
 *
 * <p>Deux modules ne declaraient NI l'un NI l'autre tout en embarquant onze
 * fichiers de migration : user-service (2) et interface-service (9). Ce test
 * les passait en silence, et son propre javadoc affirmait pourtant
 * « qu'aucun module ne peut declarer des migrations qu'aucune
 * autoconfiguration ne lirait ». L'affirmation etait fausse.
 *
 * <p>Mesure sur l'environnement de developpement le 2026-11-10 :
 * {@code user_service_db} portait six tables et aucune
 * {@code flyway_schema_history} — dont cinq appartenant a un service retire du
 * projet, et sans {@code user_profiles} ni {@code audit_logs} ;
 * {@code interface_db} etait restee a la version 8, datee du 2026-04-17.
 *
 * <p>D'ou l'inversion de la premisse. Ce qui doit declencher l'exigence n'est
 * pas une dependance — un choix — mais la PRESENCE DE MIGRATIONS, c'est-a-dire
 * l'intention. Un repertoire {@code db/migration} non vide est une promesse que
 * le schema est versionne ; les trois declarations sont ce qui la tient.
 *
 * <h2>Pourquoi TROIS artefacts</h2>
 *
 * <ul>
 *   <li>{@code flyway-core} : la bibliotheque ;</li>
 *   <li>{@code flyway-database-postgresql} : depuis Flyway 10, le support par
 *       moteur vit dans un artefact separe, et {@code flyway-core} ne l'amene
 *       pas. Sans lui l'echec est au moins VISIBLE — « No database found to
 *       handle jdbc:postgresql » — contrairement aux deux autres manques ;</li>
 *   <li>{@code spring-boot-flyway} : l'autoconfiguration, celle dont l'absence
 *       ne dit rien.</li>
 * </ul>
 *
 * <h2>Ce que ce test ne couvre pas</h2>
 *
 * Il verifie une DECLARATION, pas une execution. Il ne dit pas qu'une migration
 * s'est appliquee — cela demande une base. Il dit qu'aucun module ne peut
 * porter des migrations qu'aucune autoconfiguration ne lirait.
 *
 * <p>Il ne verifie pas non plus le pilote JDBC : Flyway n'a pas d'API reactive,
 * donc un module en R2DBC doit aussi declarer {@code org.postgresql:postgresql}.
 * Les deux modules corriges le declaraient deja — avec le commentaire « pour
 * Flyway », alors que Flyway n'y etait pas.
 */
@DisplayName("Flyway — tout module qui porte des migrations peut les appliquer")
class FlywayAutoconfigureePartoutTest {

    private static final String BIBLIOTHEQUE = "flyway-core";
    private static final String MOTEUR = "flyway-database-postgresql";
    private static final String AUTOCONFIGURATION = "spring-boot-flyway";

    /** Les trois declarations exigees, dans l'ordre ou elles sont citees. */
    private static final List<String> EXIGES =
            List.of(BIBLIOTHEQUE, MOTEUR, AUTOCONFIGURATION);

    private static final String MIGRATIONS = "src/main/resources/db/migration";

    /**
     * Les COMMENTAIRES XML sont retires avant toute recherche.
     *
     * <h3>Pourquoi, et ce que leur oubli a produit</h3>
     *
     * La premiere version de ce test cherchait les chaines nues dans le texte du
     * POM. Or le commentaire qui explique la dependance — celui-la meme qui
     * raconte le diagnostic — contient les mots « spring-boot-flyway » et
     * « flyway-core ».
     *
     * <p>Resultat mesure par mutation : retirer la DECLARATION de
     * setting-service laissait ce test VERT, parce que le commentaire la
     * mentionnait encore. Un garde-fou dont la documentation suffit a le
     * satisfaire.
     *
     * <p>C'est la seconde fois dans la meme passe qu'une lecture de TEXTE a la
     * place d'une lecture de STRUCTURE a trompe un garde-fou de ce depot : la
     * premiere, un retrait de commentaires par expression reguliere avalait
     * {@code pathMatchers("/admin/&#42;&#42;")} en prenant son litteral pour une
     * ouverture de commentaire.
     *
     * <p>Un commentaire XML ne s'imbrique pas et ne peut pas contenir
     * {@code --}, donc cette expression est sure ici — contrairement au cas
     * Java, ou {@code /*} apparait dans des litteraux de chaine.
     */
    private static String sansCommentaires(String pom) {
        return pom.replaceAll("(?s)<!--.*?-->", " ");
    }

    /** La DECLARATION, et non la mention : une balise, pas une chaine. */
    private static boolean declare(String pomSansCommentaires, String artefact) {
        return pomSansCommentaires.contains("<artifactId>" + artefact + "</artifactId>");
    }

    /** Vrai si le module embarque au moins un script de migration. */
    private static boolean porteDesMigrations(Path module) throws IOException {
        Path dossier = module.resolve(MIGRATIONS);
        if (!Files.isDirectory(dossier)) {
            return false;
        }
        try (var fichiers = Files.list(dossier)) {
            return fichiers.anyMatch(f -> f.getFileName().toString().endsWith(".sql"));
        }
    }

    @Test
    @DisplayName("un module qui porte des migrations declare les trois artefacts Flyway")
    void migrationsToujoursApplicables() throws IOException {
        List<String> avecMigrations = new ArrayList<>();
        Map<String, List<String>> manques = new LinkedHashMap<>();

        for (Path module : modules()) {
            if (!porteDesMigrations(module)) {
                continue;
            }
            String nom = module.getFileName().toString();
            avecMigrations.add(nom);

            String contenu = sansCommentaires(Files.readString(module.resolve("pom.xml")));
            List<String> absents = EXIGES.stream()
                    .filter(artefact -> !declare(contenu, artefact))
                    .toList();
            if (!absents.isEmpty()) {
                manques.put(nom, absents);
            }
        }

        // Le garde-fou se garde lui-meme : sans module trouve, il serait vert en
        // n'examinant rien. Ce depot s'est deja fait prendre par un harnais dont
        // le chemin de balayage etait faux.
        assertThat(avecMigrations)
                .withFailMessage("""
                        Aucun module portant %s n'a ete trouve depuis %s.

                        Soit le balayage est casse, soit plus aucun module \
                        n'utilise Flyway. Dans les deux cas ce test ne verifie \
                        plus rien.""", MIGRATIONS, racineDuDepot())
                .isNotEmpty();

        assertThat(manques)
                .withFailMessage("""
                        Ces modules portent des migrations SANS pouvoir les \
                        appliquer : %s

                        Consequence selon l'artefact manquant :
                          - %s                 : aucune migration du jar n'est lue ;
                          - %s  : « No database found to handle jdbc:postgresql » ;
                          - %s   : la bibliotheque est sur le chemin de classe, \
                        spring.flyway.* est servi par config-service, et AUCUNE \
                        migration ne s'applique — sans erreur, sans \
                        avertissement, sans ligne de journal.

                        C'est ainsi que trois bases sur cinq se sont retrouvees \
                        avec zero table, auth_service_db bloquee six migrations \
                        en arriere, et interface_db figee sept mois.""",
                        manques, BIBLIOTHEQUE, MOTEUR, AUTOCONFIGURATION)
                .isEmpty();
    }

    /**
     * Reciproque : la bibliotheque ne se declare pas sans son autoconfiguration.
     *
     * <p>Le test principal part des migrations, ce qui couvre tous les modules
     * d'aujourd'hui. Celui-ci garde le cas ou un module declarerait
     * {@code flyway-core} pour une autre raison — un utilitaire, un test
     * d'integration — et ou la meme confusion reviendrait.
     */
    @Test
    @DisplayName("aucun module ne declare flyway-core sans spring-boot-flyway")
    void autoconfigurationPresentePartout() throws IOException {
        List<String> sansAutoconfiguration = new ArrayList<>();

        for (Path module : modules()) {
            String contenu = sansCommentaires(Files.readString(module.resolve("pom.xml")));
            if (declare(contenu, BIBLIOTHEQUE) && !declare(contenu, AUTOCONFIGURATION)) {
                sansAutoconfiguration.add(module.getFileName().toString());
            }
        }

        assertThat(sansAutoconfiguration)
                .withFailMessage("""
                        Ces modules declarent %s SANS %s : %s

                        La bibliotheque est sur le chemin de classe et personne \
                        ne lit spring.flyway.*.""",
                        BIBLIOTHEQUE, AUTOCONFIGURATION, sansAutoconfiguration)
                .isEmpty();
    }

    /** Les repertoires de modules portant un {@code pom.xml}. */
    private static List<Path> modules() throws IOException {
        try (var contenu = Files.list(racineDuDepot())) {
            return contenu.filter(Files::isDirectory)
                    .filter(dossier -> Files.exists(dossier.resolve("pom.xml")))
                    .sorted()
                    .toList();
        }
    }

    private static Path racineDuDepot() {
        Path courant = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && courant != null; i++) {
            if (Files.exists(courant.resolve("docker-compose-minimal.yml"))) {
                return courant;
            }
            courant = courant.getParent();
        }
        throw new AssertionError(
                "Racine du depot introuvable depuis " + Path.of("").toAbsolutePath());
    }
}
