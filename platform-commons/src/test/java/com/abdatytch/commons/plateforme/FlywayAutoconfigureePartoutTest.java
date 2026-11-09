package com.abdatytch.commons.plateforme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tout module qui embarque Flyway embarque aussi son AUTOCONFIGURATION.
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
 * <h2>Ce qui a ete constate au premier demarrage reel</h2>
 *
 * Trois bases sur cinq contenaient ZERO table, sans
 * {@code flyway_schema_history}. La quatrieme, {@code auth_service_db}, etait
 * restee a la version 3 — datee du jour precedant la montee en Boot 4 — alors
 * que les migrations 4 a 8 etaient dans le jar depuis des semaines. Appareils de
 * confiance, codes de secours, alerte de nouvelle connexion, changement de
 * numero, empreintes de jetons : cinq fonctionnalites livrees et testees, dont
 * la table ou la colonne n'existait pas.
 *
 * <p>Le symptome visible etait ailleurs, et trompeur : « bad SQL grammar ...
 * FROM "surcharge_de_modele" » au rechargement des modeles. Un defaut de
 * requete, en apparence.
 *
 * <h2>La correction existait, appliquee a UN module sur quatre</h2>
 *
 * auth-service portait la dependance, avec un commentaire qui racontait tout le
 * diagnostic. notification-service, setting-service et security-service ne
 * l'avaient pas. C'est la forme la plus courante du defaut de ce depot : non
 * pas une regle absente, mais une regle appliquee a une partie des endroits qui
 * en ont besoin.
 *
 * <p>D'ou ce test, qui lit les POM plutot que de faire confiance a la
 * relecture : ce module est le seul a voir l'arborescence entiere, comme
 * {@code ModuleLivrableTest} et {@code ContratDesEvenementsTest}.
 *
 * <h2>Ce que ce test ne couvre pas</h2>
 *
 * Il verifie une DECLARATION, pas une execution. Il ne dit pas qu'une migration
 * s'est appliquee — cela demande une base. Il dit qu'aucun module ne peut
 * declarer des migrations qu'aucune autoconfiguration ne lirait.
 */
@DisplayName("Flyway — tout module qui l'embarque embarque son autoconfiguration")
class FlywayAutoconfigureePartoutTest {

    private static final String BIBLIOTHEQUE = "flyway-core";
    private static final String AUTOCONFIGURATION = "spring-boot-flyway";

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

    @Test
    @DisplayName("aucun module ne declare flyway-core sans spring-boot-flyway")
    void autoconfigurationPresentePartout() throws IOException {
        List<Path> avecFlyway = new ArrayList<>();
        List<String> sansAutoconfiguration = new ArrayList<>();

        try (var modules = Files.list(racineDuDepot())) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path pom = module.resolve("pom.xml");
                if (!Files.exists(pom)) {
                    continue;
                }
                String contenu = sansCommentaires(Files.readString(pom));
                if (!declare(contenu, BIBLIOTHEQUE)) {
                    continue;
                }
                avecFlyway.add(pom);
                if (!declare(contenu, AUTOCONFIGURATION)) {
                    sansAutoconfiguration.add(module.getFileName().toString());
                }
            }
        }

        // Le garde-fou se garde lui-meme : sans module trouve, il serait vert
        // en n'examinant rien. Ce depot s'est deja fait prendre par un harnais
        // dont le chemin de balayage etait faux.
        assertThat(avecFlyway)
                .withFailMessage("""
                        Aucun pom.xml declarant %s n'a ete trouve depuis %s.

                        Soit le balayage est casse, soit plus aucun module \
                        n'utilise Flyway. Dans les deux cas ce test ne verifie \
                        plus rien.""", BIBLIOTHEQUE, racineDuDepot())
                .isNotEmpty();

        assertThat(sansAutoconfiguration)
                .withFailMessage("""
                        Ces modules declarent %s SANS %s : %s

                        Consequence : la bibliotheque est sur le chemin de \
                        classe, spring.flyway.* est servi par config-service, et \
                        AUCUNE migration ne s'applique. Sans erreur, sans \
                        avertissement, sans ligne de journal.

                        C'est ainsi que trois bases sur cinq se sont retrouvees \
                        avec zero table, et auth_service_db bloquee cinq \
                        migrations en arriere pendant des semaines.""",
                        BIBLIOTHEQUE, AUTOCONFIGURATION, sansAutoconfiguration)
                .isEmpty();
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
