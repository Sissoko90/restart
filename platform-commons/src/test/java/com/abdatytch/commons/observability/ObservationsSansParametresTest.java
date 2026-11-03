package com.abdatytch.commons.observability;

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
 * Aucune observation ne doit porter les VALEURS des parametres de requete.
 *
 * <h2>Ce que cette option ferait</h2>
 *
 * {@code management.observations.r2dbc.include-parameter-values} etiquette les
 * observations avec les valeurs reelles passees aux requetes SQL. Dans
 * auth-service, ces valeurs sont des <b>codes de verification a usage
 * unique</b>, des numeros de telephone, des adresses electroniques et des
 * empreintes de mot de passe.
 *
 * <h2>Pourquoi c'est pire qu'un journal</h2>
 *
 * Une etiquette part dans les traces <b>et</b> dans Prometheus. Et une serie
 * Prometheus :
 *
 * <ul>
 *   <li>n'est pas effaçable par {@code AccountErasureService}, contrairement aux
 *       journaux — un effacement RGPD laisserait donc les donnees en place ;</li>
 *   <li>a une cardinalite non bornee : une etiquette par valeur distincte de
 *       parametre ferait croitre la memoire du serveur sans limite.</li>
 * </ul>
 *
 * <p>C'est la meme regle que celle appliquee aux metriques metier — aucune
 * etiquette d'identifiant — et au journal de l'accuse de reception SMS, dont le
 * champ {@code message} n'est jamais lu parce qu'il peut contenir un echo du
 * code envoye.
 *
 * <h2>Pourquoi un test alors que le defaut est deja « false »</h2>
 *
 * Parce que c'est exactement le genre d'option qu'on active « juste pour
 * deboguer une requete » et qu'on oublie de remettre. Le defaut protege le
 * deploiement initial ; ce test protege tous les suivants.
 */
@DisplayName("Observations — jamais les valeurs des parametres de requete")
class ObservationsSansParametresTest {

    /** Variantes d'ecriture de la meme propriete. */
    private static final List<String> FORMES_ACTIVEES = List.of(
            "include-parameter-values: true",
            "include-parameter-values:true",
            "includeParameterValues: true",
            "includeParameterValues:true");

    private static final int PLANCHER_FICHIERS_EXAMINES = 5;

    @Test
    @DisplayName("aucune configuration n'active les valeurs de parametres")
    void aucuneConfigurationNActive() throws IOException {
        List<Path> configurations = configurations();

        assertThat(configurations)
                .withFailMessage("aucune configuration balayee : ce garde-fou serait "
                        + "vert sans rien verifier")
                .hasSizeGreaterThanOrEqualTo(PLANCHER_FICHIERS_EXAMINES);

        List<String> fautives = new ArrayList<>();
        for (Path configuration : configurations) {
            String contenu = Files.readString(configuration).replace("\"", "").replace("'", "");
            for (String forme : FORMES_ACTIVEES) {
                if (contenu.contains(forme)) {
                    fautives.add(configuration.getFileName() + " (" + forme + ")");
                    break;
                }
            }
        }

        assertThat(fautives)
                .withFailMessage("""
                        Ces configurations etiquettent les observations avec les VALEURS \
                        des parametres de requete : %s

                        Dans auth-service ces valeurs sont des codes de verification a \
                        usage unique, des numeros de telephone et des empreintes de mot \
                        de passe.

                        Une etiquette part dans les traces ET dans Prometheus. Une serie \
                        Prometheus n'est pas effaçable par AccountErasureService — un \
                        effacement RGPD laisserait les donnees en place — et sa \
                        cardinalite n'est pas bornee.

                        Pour deboguer une requete, utiliser les journaux, qui sont \
                        effaçables.""", fautives)
                .isEmpty();
    }

    @Test
    @DisplayName("la regle est ecrite explicitement la ou le proxy R2DBC est actif")
    void regleEcriteExplicitement() throws IOException {
        // r2dbc-proxy declenche l'observation des requetes. La ou il est
        // present, la regle doit etre VISIBLE dans la configuration et pas
        // seulement implicite : une valeur par defaut ne se lit pas.
        Path pom = racineDuDepot().resolve("auth-service/pom.xml");
        if (!Files.exists(pom) || !Files.readString(pom).contains("r2dbc-proxy")) {
            return;
        }

        Path configuration = racineDuDepot().resolve("config-repo/dev/auth-service-dev.yml");
        assertThat(Files.readString(configuration))
                .withFailMessage("r2dbc-proxy est actif et la regle n'est pas ecrite : "
                        + "poser include-parameter-values: false explicitement, pour "
                        + "que la decision soit lisible a cote de la configuration")
                .contains("include-parameter-values: false");
    }

    private static List<Path> configurations() throws IOException {
        Path racine = racineDuDepot();
        List<Path> fichiers = new ArrayList<>();
        for (Path base : List.of(racine.resolve("config-repo"), racine)) {
            if (!Files.exists(base)) {
                continue;
            }
            try (Stream<Path> flux = Files.walk(base, base.equals(racine) ? 3 : 10)) {
                flux.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                        // Fichiers REGULIERS uniquement : `monitoring/` contient
                        // des REPERTOIRES nommes `prometheus.yml` et
                        // `loki-config.yml`, crees par Docker quand une source
                        // de montage n'existait pas. Les lire leve
                        // « Is a directory ».
                        .filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains("/target/"))
                        .filter(p -> !p.toString().contains("/node_modules/"))
                        .forEach(fichiers::add);
            }
        }
        return fichiers.stream().distinct().sorted().toList();
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
