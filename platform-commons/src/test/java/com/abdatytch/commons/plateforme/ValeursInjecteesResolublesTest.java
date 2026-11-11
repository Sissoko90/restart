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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toute clef injectee SANS valeur par defaut a une source declaree.
 *
 * <h2>Le defaut pince</h2>
 *
 * {@code AdminController} lisait {@code @Value("${admin.mail.from}")}. Le bloc
 * {@code admin.mail} a ete retire de ce module quand il a cesse d'expedier ses
 * courriels lui-meme, et cette annotation n'avait AUCUNE valeur par defaut : un
 * demarrage neuf echouait sur un marqueur non resolu.
 *
 * <h2>POURQUOI PERSONNE NE L'A VU</h2>
 *
 * Le service demarrait quand meme en local. Spring resout un marqueur contre
 * l'ENVIRONNEMENT, et {@code SystemEnvironmentPropertySource} fait la
 * correspondance souple {@code ADMIN_MAIL_FROM} vers {@code admin.mail.from}.
 * Le fichier {@code .env} de la machine portait encore cette variable, morte
 * depuis le retrait et jamais nettoyee.
 *
 * <p>C'est la forme la plus couteuse de ce genre de defaut : vert en local,
 * rouge partout ailleurs — un conteneur, une integration continue, une autre
 * machine. Ce test regarde donc les sources DECLAREES, jamais l'environnement
 * de celui qui le lance.
 *
 * <h2>Les quatre sources acceptees</h2>
 *
 * <ol>
 *   <li>{@code config-repo/dev/<module>-dev.yml} et
 *       {@code config-repo/dev/application-dev.yml} — la configuration
 *       centralisee ;</li>
 *   <li>l'{@code application*.yml} du module lui-meme ;</li>
 *   <li>{@code platform-commons-defaults.properties} — les defauts du socle,
 *       qui existent precisement pour qu'un service demarre sans la
 *       configuration centralisee ;</li>
 *   <li>{@code .env.example} sous la forme VARIABLE, pour les quelques clefs
 *       nommees directement d'apres une variable d'environnement. C'est bien
 *       {@code .env.example} qui compte, et jamais {@code .env} : le premier
 *       est versionne et vaut declaration, le second est la machine de
 *       quelqu'un.</li>
 * </ol>
 */
@DisplayName("Les valeurs injectees sans defaut sont resolubles")
class ValeursInjecteesResolublesTest {

    /**
     * {@code @Value("${clef}")} sans {@code :defaut}.
     *
     * <p>Le motif refuse les deux-points : {@code ${clef:defaut}} se resout
     * toujours, et n'a donc rien a declarer.
     */
    private static final Pattern INJECTION =
            Pattern.compile("@Value\\(\\s*\"\\$\\{([^}:]+)}\"\\s*\\)");

    @Test
    @DisplayName("aucune clef injectee n'est sans source declaree")
    void toutesResolubles() throws IOException {
        Set<String> declarees = sourcesDeclarees();
        Set<String> variables = variablesDeclarees();
        List<String> orphelines = new ArrayList<>();

        for (Path module : SourcesDuDepot.modules()) {
            String nom = module.getFileName().toString();
            for (Path source : SourcesDuDepot.sourcesDeProduction(module)) {
                String code = SourcesDuDepot.sansCommentairesJava(Files.readString(source));
                Matcher m = INJECTION.matcher(code);
                while (m.find()) {
                    String clef = m.group(1);
                    if (declarees.contains(clef) || variables.contains(enVariable(clef))) {
                        continue;
                    }
                    orphelines.add(nom + " : " + source.getFileName() + " lit " + clef);
                }
            }
        }

        assertThat(orphelines)
                .withFailMessage("""
                        Des clefs sont injectees sans valeur par defaut et sans \
                        source declaree :

                        %s

                        Un marqueur non resolu REFUSE le demarrage du service.

                        Il peut neanmoins demarrer sur votre machine : Spring \
                        resout aussi contre l'environnement, avec la \
                        correspondance souple ADMIN_MAIL_FROM vers \
                        admin.mail.from. Une variable oubliee dans un .env local \
                        suffit donc a masquer le defaut jusqu'au premier \
                        conteneur. C'est exactement ce qui est arrive a \
                        admin.mail.from.

                        Trois issues : declarer la clef dans config-repo ou dans \
                        l'application.yml du module, lui donner un defaut en \
                        ecrivant ${clef:valeur}, ou declarer sa variable dans \
                        .env.example.""", String.join("\\n", orphelines))
                .isEmpty();
    }

    /** Le garde-fou se garde : sans sources lues, il ne verifie rien. */
    @Test
    @DisplayName("le balayage lit bien des clefs et des sources")
    void leBalayageFonctionne() throws IOException {
        assertThat(sourcesDeclarees())
                .withFailMessage("aucune clef de configuration lue : le balayage "
                        + "est casse et ce test ne protege plus rien")
                .hasSizeGreaterThan(200);

        int injectees = 0;
        for (Path module : SourcesDuDepot.modules()) {
            for (Path source : SourcesDuDepot.sourcesDeProduction(module)) {
                Matcher m = INJECTION.matcher(
                        SourcesDuDepot.sansCommentairesJava(Files.readString(source)));
                while (m.find()) {
                    injectees++;
                }
            }
        }
        assertThat(injectees)
                .withFailMessage("%d injections sans defaut trouvees : le motif ne "
                        + "reconnait plus rien", injectees)
                .isGreaterThan(20);
    }

    // ==================================================================

    /** {@code a.b-c} devient {@code A_B_C}, comme le fait Spring. */
    private static String enVariable(String clef) {
        return clef.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static Set<String> variablesDeclarees() throws IOException {
        Path exemple = SourcesDuDepot.racineDuDepot().resolve(".env.example");
        Set<String> noms = new LinkedHashSet<>();
        if (!Files.exists(exemple)) {
            return noms;
        }
        for (String ligne : Files.readAllLines(exemple)) {
            Matcher m = Pattern.compile("^([A-Z][A-Z0-9_]*)=").matcher(ligne.trim());
            if (m.find()) {
                noms.add(m.group(1));
            }
        }
        return noms;
    }

    private static Set<String> sourcesDeclarees() throws IOException {
        Path racine = SourcesDuDepot.racineDuDepot();
        Set<String> clefs = new LinkedHashSet<>();

        Path depot = racine.resolve("config-repo");
        if (Files.isDirectory(depot)) {
            try (Stream<Path> fichiers = Files.walk(depot)) {
                for (Path f : fichiers.filter(p -> p.toString().endsWith(".yml")).toList()) {
                    clefs.addAll(clefsDuYaml(f));
                }
            }
        }

        for (Path module : SourcesDuDepot.modules()) {
            Path ressources = module.resolve("src/main/resources");
            if (!Files.isDirectory(ressources)) {
                continue;
            }
            try (Stream<Path> fichiers = Files.list(ressources)) {
                for (Path f : fichiers.toList()) {
                    String nom = f.getFileName().toString();
                    if (nom.startsWith("application") && nom.endsWith(".yml")) {
                        clefs.addAll(clefsDuYaml(f));
                    } else if (nom.endsWith("-defaults.properties")) {
                        Properties defauts = new Properties();
                        try (var flux = Files.newInputStream(f)) {
                            defauts.load(flux);
                        }
                        clefs.addAll(defauts.stringPropertyNames());
                    }
                }
            }
        }
        return clefs;
    }

    /** Les clefs APLATIES d'un fichier, tous documents confondus. */
    private static Set<String> clefsDuYaml(Path fichier) throws IOException {
        Set<String> clefs = new LinkedHashSet<>();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        for (Object document : new Yaml(new SafeConstructor(options))
                .loadAll(Files.readString(fichier))) {
            aplatir(document, "", clefs);
        }
        return clefs;
    }

    private static void aplatir(Object noeud, String prefixe, Set<String> clefs) {
        if (noeud instanceof Map<?, ?> carte) {
            carte.forEach((cle, valeur) -> aplatir(valeur, prefixe + cle + ".", clefs));
        } else if (!prefixe.isEmpty()) {
            clefs.add(prefixe.substring(0, prefixe.length() - 1));
        }
    }
}
