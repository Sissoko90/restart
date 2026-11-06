package com.abdatytch.commons.plateforme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aucun bean planifie n'est rechargeable a chaud.
 *
 * <h2>Pourquoi cette combinaison est interdite</h2>
 *
 * {@code ScheduledAnnotationBeanPostProcessor} enregistre les taches
 * {@code @Scheduled} <b>une fois</b>, au demarrage. Un rechargement de
 * configuration detruit et recree les beans {@code @RefreshScope} — mais ne
 * reenregistre aucune tache.
 *
 * <p>Selon le cas, la tache continue de tourner avec l'ancienne configuration,
 * ou cesse de tourner. Dans les deux cas <b>rien ne le signale</b> : pas
 * d'erreur, pas d'avertissement. Une surveillance de solde qui s'arrete en
 * silence est precisement le genre de panne que ce depot passe son temps a
 * deterrer.
 *
 * <h2>Pourquoi un test et non une convention</h2>
 *
 * Les deux annotations sont utiles separement, et chacune se pose pour une bonne
 * raison. Leur rencontre est un accident : quelqu'un rend un bean rechargeable
 * parce qu'il porte un seuil, sans remarquer qu'il porte aussi une tache. Rien
 * dans le code ne les met face a face — sauf ce test.
 *
 * <p>Le reglage d'un bean planifie est une <b>cadence</b>, pas un interrupteur
 * d'incident : un redemarrage pour le changer est acceptable. Voir
 * {@link com.abdatytch.commons.config.RechargementAChaud}.
 */
@DisplayName("Rechargement à chaud et tâches planifiées ne se mélangent pas")
class RefreshScopeSansPlanificationTest {

    private static Path racineDuDepot() {
        Path courant = Path.of("").toAbsolutePath();
        for (int niveau = 0; niveau < 5 && courant != null; niveau++) {
            if (Files.isDirectory(courant.resolve("config-repo"))) {
                return courant;
            }
            courant = courant.getParent();
        }
        throw new IllegalStateException("Racine du depot introuvable");
    }

    /**
     * Retire les commentaires avant d'analyser.
     *
     * <p>Sans cela, ce test se declencherait sur les javadocs qui EXPLIQUENT
     * pourquoi la combinaison est interdite — dont celle de
     * {@code RechargementAChaud}, qui nomme les deux annotations dans la meme
     * phrase. Un garde-fou declenche par sa propre explication est une erreur
     * commise trois fois dans ce depot.
     */
    private static String sansCommentaires(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    /**
     * Cherche une annotation, sous ses DEUX formes d'ecriture.
     *
     * <p>Un {@code contains("@RefreshScope")} nu ne voit pas la forme
     * pleinement qualifiee,
     * {@code @org.springframework.cloud.context.config.annotation.RefreshScope}
     * — il n'y a pas d'arobase juste devant le nom simple.
     *
     * <p>Ce n'est pas une precaution theorique : en eprouvant ce garde-fou,
     * j'ai pose l'annotation interdite sous cette forme et <b>le test est reste
     * vert</b>. L'angle mort etait reel, et c'est la mutation qui l'a revele —
     * pas la relecture.
     */
    private static boolean porte(String code, String annotation) {
        return code.matches("(?s).*@(?:[\\w.]+\\.)?" + annotation + "\\b.*");
    }

    @Test
    @DisplayName("aucune classe ne porte à la fois @RefreshScope et @Scheduled")
    void aucuneClasseNePorteLesDeux() throws IOException {
        Path racine = racineDuDepot();
        List<String> fautives = new ArrayList<>();
        int rechargeables = 0;

        try (Stream<Path> modules = Files.list(racine)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> fichiers = Files.walk(sources)) {
                    for (Path fichier : fichiers
                            .filter(f -> f.toString().endsWith(".java")).toList()) {

                        String code = sansCommentaires(Files.readString(fichier));
                        if (!porte(code, "RefreshScope")) {
                            continue;
                        }
                        rechargeables++;
                        if (porte(code, "Scheduled")) {
                            fautives.add(module.getFileName() + "/"
                                    + fichier.getFileName());
                        }
                    }
                }
            }
        }

        // Le garde-fou se garde lui-meme : s'il ne trouve plus aucun bean
        // rechargeable, c'est qu'il ne cherche plus au bon endroit — et il
        // serait vert sans rien verifier.
        assertThat(rechargeables)
                .withFailMessage("Aucun bean @RefreshScope trouve dans le depot : "
                        + "ce garde-fou ne verifie plus rien")
                .isPositive();

        assertThat(fautives)
                .withFailMessage("""
                        Ces classes portent @RefreshScope ET @Scheduled : %s

                        Les taches planifiees sont enregistrees une fois, au \
                        demarrage. Un rechargement recree le bean sans \
                        reenregistrer la tache : elle continue avec l'ancienne \
                        configuration, ou cesse de tourner — sans rien signaler.

                        Le reglage d'un bean planifie est une cadence, pas un \
                        interrupteur d'incident : retirer @RefreshScope et \
                        accepter le redemarrage.""", fautives)
                .isEmpty();
    }
}
