package com.abdatytch.commons.plateforme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tout fichier YAML de configuration doit être lisible par snakeyaml.
 *
 * <h2>Le défaut que ce garde-fou ferme</h2>
 *
 * Deux clefs identiques dans un même document YAML ne fusionnent pas. Snakeyaml
 * — l'analyseur qu'utilise Spring Boot — les <b>refuse</b>, et le service ne
 * démarre pas du tout : {@code found duplicate key spring}. Ce n'est pas une
 * dégradation, c'est un refus de démarrage.
 *
 * <p>C'est arrivé deux fois dans ce dépôt, et chaque fois par le même geste :
 * ajouter un bloc de configuration <b>à la fin</b> du fichier, sans voir qu'une
 * clef de premier niveau du même nom existait déjà cent lignes plus haut. Un
 * {@code spring:} ajouté sous un {@code spring:} existant.
 *
 * <h2>Pourquoi une vérification avec snakeyaml, et pas avec un autre analyseur</h2>
 *
 * Les analyseurs ne sont pas d'accord entre eux, et c'est tout le piège :
 * <b>PyYAML tolère les clefs dupliquées</b> en gardant silencieusement la
 * dernière. Une vérification faite avec lui <i>passe</i> sur un fichier que
 * Spring rejette — c'est exactement ce qui s'est produit, et la vérification a
 * donné une fausse assurance avant que le test de contexte ne tombe.
 *
 * <p>Le seul contrôle qui vaille est donc celui fait par le <b>même</b>
 * analyseur que celui qui lira le fichier en production. Snakeyaml est déjà une
 * dépendance de test de ce module.
 *
 * <h2>Ce que ce test ne couvre pas</h2>
 *
 * Il vérifie que le document est lisible, pas que les clefs sont les bonnes.
 * Une propriété mal nommée ({@code spring.servlet.multipart} dans un service
 * WebFlux, ou la clef Prometheus de Spring Boot 2 sur Boot 4) est parfaitement
 * valide en YAML et silencieusement ignorée. Cela se détecte autrement.
 */
@DisplayName("Configuration YAML — aucun document illisible par Spring")
class ConfigurationYamlLisibleTest {

    /**
     * Plancher de détection.
     *
     * <p>Si la recherche de fichiers cesse de trouver quoi que ce soit, le test
     * passerait en ne vérifiant rien. Ce dépôt s'est déjà fait prendre par un
     * harnais vert qui ne mesurait plus rien.
     */
    private static final int PLANCHER_FICHIERS = 15;

    @Test
    @DisplayName("chaque fichier YAML se charge, donc sans clef dupliquee")
    void aucunDocumentIllisible() throws IOException {
        List<Path> fichiers = fichiersYaml();

        assertThat(fichiers)
                .withFailMessage("aucun fichier YAML trouve : l'analyse est cassee, "
                        + "et ce garde-fou ne protege plus rien")
                .hasSizeGreaterThanOrEqualTo(PLANCHER_FICHIERS);

        Map<String, String> illisibles = new LinkedHashMap<>();
        for (Path fichier : fichiers) {
            String contenu = Files.readString(fichier);
            try {
                // `loadAll` et non `load` : un fichier peut porter plusieurs
                // documents separes par `---`, et Spring les lit tous. `load`
                // echouerait sur le second pour une mauvaise raison.
                commeSpring().loadAll(contenu).forEach(document -> { });
            } catch (RuntimeException illisible) {
                illisibles.put(racineDuDepot().relativize(fichier).toString(),
                        premiereLigne(illisible.getMessage()));
            }
        }

        assertThat(illisibles)
                .withFailMessage("""
                        Ces fichiers YAML ne sont pas lisibles par snakeyaml, donc par \
                        Spring Boot :

                        %s

                        Le service concerne ne demarrera PAS — ce n'est pas une \
                        degradation, c'est un refus de demarrage.

                        Cause la plus courante : une clef de premier niveau dupliquee, \
                        parce qu'un bloc a ete ajoute a la fin du fichier alors que la \
                        meme clef existait plus haut. Deux `spring:` ne fusionnent pas. \
                        Fusionner le nouveau bloc DANS l'existant.""",
                        lisible(illisibles))
                .isEmpty();
    }

    /**
     * Un analyseur configuré <b>comme celui de Spring Boot</b>.
     *
     * <h3>Pourquoi ce n'est pas {@code new Yaml()}</h3>
     *
     * C'est ce que faisait la première version de ce test, et le contrôle à vide
     * l'a prise en défaut : avec les réglages par défaut, snakeyaml <b>accepte</b>
     * les clefs dupliquées et se contente d'un avertissement sur la sortie
     * standard — « WARNING: duplicate keys found : spring ». Le test passait donc
     * sur le fichier même qui venait d'empêcher user-service de démarrer.
     *
     * <p>Ce qui refuse, c'est {@code allowDuplicateKeys(false)}, et c'est
     * {@code OriginTrackedYamlLoader} de Spring Boot qui le pose. Reproduire le
     * réglage est tout l'objet de ce test : vérifier avec un analyseur plus
     * permissif que celui de production ne vérifie rien.
     *
     * <p>Troisième analyseur, troisième comportement : PyYAML, lui, garde
     * silencieusement la dernière clef, sans même un avertissement.
     */
    private static Yaml commeSpring() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options));
    }

    /**
     * Les fichiers de configuration du dépôt.
     *
     * <p>{@code config-repo/} et les ressources des modules. Les répertoires
     * {@code target/} sont écartés : ils contiennent des copies, et un échec y
     * désignerait un fichier qu'on ne modifie pas.
     */
    private static List<Path> fichiersYaml() throws IOException {
        try (Stream<Path> fichiers = Files.walk(racineDuDepot())) {
            return fichiers
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String nom = p.getFileName().toString();
                        return nom.endsWith(".yml") || nom.endsWith(".yaml");
                    })
                    .filter(p -> {
                        String chemin = p.toString();
                        return chemin.contains("/config-repo/")
                                || chemin.contains("/src/main/resources/")
                                || chemin.contains("/src/test/resources/");
                    })
                    .filter(p -> !p.toString().contains("/target/"))
                    .sorted()
                    .toList();
        }
    }

    private static String premiereLigne(String message) {
        if (message == null) {
            return "(sans message)";
        }
        int saut = message.indexOf('\n');
        return saut < 0 ? message : message.substring(0, saut);
    }

    private static String lisible(Map<String, String> illisibles) {
        List<String> lignes = new ArrayList<>();
        illisibles.forEach((fichier, cause) ->
                lignes.add("  - " + fichier + "\n      " + cause));
        return String.join("\n", lignes);
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
