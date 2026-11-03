package com.abdatytch.auth_service;

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
 * Tout depot doit etre declare dans {@code AuthServiceApplicationTests}.
 *
 * <h2>Pourquoi ce garde-fou existe</h2>
 *
 * R2DBC est desactive dans le profil de test : les trois auto-configurations
 * sont exclues par {@code application-test.yml}. Aucun depot n'est donc cree,
 * et {@code AuthServiceApplicationTests} les remplace <b>un par un</b> par des
 * {@code @MockitoBean}.
 *
 * <p>C'est une liste a maintenir d'accord avec le paquet {@code repository}, et
 * l'oubli se manifeste de la pire façon : le contexte ne demarre plus, et
 * l'erreur — « No qualifying bean of type ... available » — designe le service
 * qui consomme le depot, pas le depot manquant ni la liste a completer.
 *
 * <p>C'est arrive en ajoutant {@code AppareilDeConfianceRepository} : l'echec
 * accusait {@code accountErasureService}, son « constructor parameter 5 ».
 *
 * <h2>Pourquoi un test de source</h2>
 *
 * La question est structurelle : la liste des champs annotes couvre-t-elle le
 * paquet ? Monter un contexte pour y repondre couterait plus cher que la regle,
 * et echouerait de toute façon par l'erreur illisible que ce test remplace.
 *
 * <p>Meme forme que {@code OrdreDuRappelOAuth2Test} et
 * {@code RoutageCoherentTest}.
 */
@DisplayName("Tout depot est declare dans le test de contexte")
class DepotsSimulesComplets {

    private static final Path PAQUET_DEPOTS =
            Path.of("src/main/java/com/abdatytch/auth_service/repository");

    private static final Path TEST_DE_CONTEXTE =
            Path.of("src/test/java/com/abdatytch/auth_service/AuthServiceApplicationTests.java");

    @Test
    @DisplayName("chaque depot du paquet est remplace par un @MockitoBean")
    void chaqueDepotEstSimule() throws IOException {
        String testDeContexte = Files.readString(TEST_DE_CONTEXTE);

        List<String> manquants = new ArrayList<>();
        try (Stream<Path> fichiers = Files.list(PAQUET_DEPOTS)) {
            for (Path fichier : fichiers.toList()) {
                String nom = fichier.getFileName().toString();
                if (!nom.endsWith("Repository.java")) {
                    continue;
                }
                String type = nom.substring(0, nom.length() - ".java".length());
                // Le type doit apparaitre comme TYPE d'un champ annote, et non
                // seulement dans l'import generique du paquet.
                if (!testDeContexte.contains("@MockitoBean private " + type + " ")) {
                    manquants.add(type);
                }
            }
        }

        assertThat(manquants)
                .withFailMessage("""
                        Ces depots ne sont pas declares dans AuthServiceApplicationTests :

                          %s

                        R2DBC est desactive en test, donc aucun depot n'est cree. Le \
                        contexte ne demarrera pas, et l'erreur designera le SERVICE qui \
                        consomme le depot — pas le depot manquant.

                        Ajouter, dans AuthServiceApplicationTests :
                          @MockitoBean private <Type> <nomDuChamp>;""",
                        String.join("\n  ", manquants))
                .isEmpty();
    }

    @Test
    @DisplayName("le paquet des depots est bien lu")
    void paquetBienLu() throws IOException {
        // Sans cette assertion, un chemin devenu faux rendrait une liste vide et
        // le garde-fou passerait en ne verifiant rien. Le piege exact qui a
        // failli rendre RoutageCoherentTest inutile.
        try (Stream<Path> fichiers = Files.list(PAQUET_DEPOTS)) {
            long depots = fichiers
                    .filter(f -> f.getFileName().toString().endsWith("Repository.java"))
                    .count();
            assertThat(depots)
                    .withFailMessage("aucun depot lu : le chemin du paquet a change "
                            + "et ce garde-fou ne protege plus rien")
                    .isGreaterThan(5);
        }
    }
}
