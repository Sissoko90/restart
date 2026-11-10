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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Une clef que Spring definit sous {@code spring.} n'est lue que la.
 *
 * <h2>Le defaut que ce garde-fou ferme</h2>
 *
 * Une clef de configuration inconnue est <b>ignoree en silence</b>. Pas
 * d'erreur, pas d'avertissement, pas de ligne de journal : le bloc est dans le
 * fichier, servi par config-service, visible dans {@code /actuator/env}, et il
 * ne regle rien.
 *
 * <h2>Les deux cas mesures le 2026-11-10</h2>
 *
 * <ol>
 *   <li><b>interface-service</b> portait
 *       {@code gateway.cloud.consul.discovery.health-check-path} au lieu de
 *       {@code spring.cloud.consul.discovery...}. Le service s'enregistrait
 *       donc avec le defaut du cadre, {@code /actuator/health}, qui agrege
 *       TOUS les indicateurs : une panne de Redis ou de la base le faisait
 *       retirer de la decouverte alors que le processus servait tres bien les
 *       requetes sans rapport ;</li>
 *   <li><b>gateway-service</b> portait tout un
 *       {@code geolocation.cloud.gateway.routes} : <b>onze routes</b> avec
 *       leurs predicats et leurs coupe-circuits. Six prefixes n'existaient que
 *       la — la boite de reception de notification-service et ses endpoints
 *       d'appareil, donc les notifications push mobiles, la surface
 *       developpeur d'interface-service, ses deux chemins {@code /dev}, et le
 *       flux temps reel. Aucun n'etait joignable a travers la passerelle.</li>
 * </ol>
 *
 * <p>Cinq autres services declaraient la meme clef Consul au bon endroit :
 * l'intention etait donc sans ambiguite, et l'erreur purement d'indentation.
 *
 * <h2>Pourquoi cette liste de clefs, et pas une autre</h2>
 *
 * La liste est COURTE et etablie par mesure, pas par principe. Chaque nom a
 * ete cherche dans les 29 fichiers de configuration du depot, et retenu
 * seulement si tous ses emplacements legitimes sont connus :
 *
 * <ul>
 *   <li>{@code cloud}, {@code flyway}, {@code datasource} : aucun usage hors
 *       de {@code spring.} ;</li>
 *   <li>{@code r2dbc} : trois usages legitimes sous {@code management.} —
 *       Actuator reprend les noms de technologies pour ses indicateurs de
 *       sante et ses observations. D'ou la tolerance ci-dessous.</li>
 * </ul>
 *
 * <p>Sont volontairement EXCLUS les noms qui ont de vrais homonymes
 * applicatifs, parce qu'un garde-fou a faux positifs finit desactive :
 *
 * <ul>
 *   <li>{@code kafka} : ce depot a un espace applicatif {@code kafka.consumer.
 *       resilience.*}, lu par {@code @Value} dans user-service ;</li>
 *   <li>{@code mail} : {@code admin.mail} et {@code admin.critical.mail}
 *       existent, et {@code spring.mail.properties.mail} aussi ;</li>
 *   <li>{@code application} : {@code management.metrics.tags.application} est
 *       une etiquette de metrique, pas la clef de Spring ;</li>
 *   <li>{@code security} : la plateforme a son propre {@code security.*}.</li>
 * </ul>
 *
 * <h2>Ce que ce test ne couvre pas</h2>
 *
 * Il verifie un EMPLACEMENT, pas un nom. Une clef bien placee mais mal
 * orthographiee — {@code spring.flywayy} — reste ignoree en silence, et cela se
 * detecte autrement. Il ne verifie pas non plus qu'un moteur existe pour lire
 * la clef : les onze routes du cas 2 etaient inertes pour DEUX raisons, et
 * celle-la est gardee par {@code RoutageCoherentTest.aucuneRouteEnYaml}.
 */
@DisplayName("Configuration YAML — les clefs de Spring sont sous spring:")
class ClesDeSpringSousSpringTest {

    /** Les noms que Spring Boot ne definit que sous {@code spring.}. */
    private static final Set<String> SURVEILLES =
            Set.of("cloud", "flyway", "datasource", "r2dbc");

    /**
     * Le seul parent tolere en dehors de {@code spring}.
     *
     * <p>Actuator nomme ses indicateurs et ses observations d'apres la
     * technologie : {@code management.health.r2dbc.enabled},
     * {@code management.observations.r2dbc}. Ce sont de vraies clefs, lues, et
     * les refuser serait un faux positif.
     */
    private static final String TOLERE = "management";

    /** Plancher de detection : sans fichiers, ce test ne verifie rien. */
    private static final int PLANCHER_FICHIERS = 15;

    private record Egare(String fichier, String cheminDeLaClef) {
        @Override
        public String toString() {
            return "  - " + fichier + " : " + cheminDeLaClef;
        }
    }

    @Test
    @DisplayName("aucune clef de Spring n'est imbriquee sous un espace applicatif")
    void clefsAuBonEndroit() throws IOException {
        List<Path> fichiers = fichiersYaml();

        assertThat(fichiers)
                .withFailMessage("aucun fichier YAML trouve : le balayage est "
                        + "casse, et ce garde-fou ne protege plus rien")
                .hasSizeGreaterThanOrEqualTo(PLANCHER_FICHIERS);

        List<Egare> egares = new ArrayList<>();
        for (Path fichier : fichiers) {
            String nom = racineDuDepot().relativize(fichier).toString();
            for (Object document : commeSpring().loadAll(Files.readString(fichier))) {
                parcourir(document, new ArrayList<>(), nom, egares);
            }
        }

        assertThat(egares)
                .withFailMessage("""
                        Ces clefs de Spring sont imbriquees sous un espace \
                        applicatif :

                        %s

                        Spring ne les lira PAS, et ne dira rien : une clef \
                        inconnue est ignoree en silence. Le bloc sera dans le \
                        fichier, servi par config-service, visible dans \
                        /actuator/env, et il ne reglera rien.

                        C'est ainsi qu'interface-service s'est enregistre dans \
                        Consul avec le mauvais chemin de sante, et que onze \
                        routes de la passerelle n'ont jamais rien route.

                        Les deplacer DANS le bloc `spring:` existant, et non \
                        en ajouter un second : deux clefs de premier niveau \
                        identiques font refuser le fichier par snakeyaml, donc \
                        echouer le demarrage.""",
                        String.join("\n", egares.stream().map(Egare::toString).toList()))
                .isEmpty();
    }

    /**
     * Descend l'arbre en tenant la pile des parents.
     *
     * <p>Une lecture de l'ARBRE et non du texte, deliberement. Trois
     * garde-fous de ce depot se sont fait prendre par une lecture de texte :
     * un commentaire de POM qui contenait l'artefact exige, une expression
     * reguliere qui prenait un litteral pour une ouverture de commentaire, et
     * un test qui comptait des routes YAML sans pouvoir voir leur indentation.
     * Le defaut vise ici EST une question d'emplacement : seul l'arbre le
     * montre.
     */
    private static void parcourir(Object noeud, List<String> pile,
                                  String fichier, List<Egare> egares) {
        if (!(noeud instanceof Map<?, ?> carte)) {
            return;
        }
        for (Map.Entry<?, ?> entree : carte.entrySet()) {
            String clef = String.valueOf(entree.getKey());

            // Une clef POINTEE declare son chemin complet : Spring la lit telle
            // quelle, et son premier segment est ce qui compte.
            String premierSegment = clef.contains(".") ? clef.substring(0, clef.indexOf('.')) : clef;
            List<String> pileEffective = new ArrayList<>(pile);
            if (clef.contains(".")) {
                pileEffective.add(premierSegment);
            }

            if (SURVEILLES.contains(clef) && !emplacementTolere(pile)) {
                egares.add(new Egare(fichier,
                        String.join(".", pile) + (pile.isEmpty() ? "" : ".") + clef));
            }

            List<String> suite = new ArrayList<>(pileEffective);
            suite.add(clef.contains(".") ? clef.substring(clef.indexOf('.') + 1) : clef);
            parcourir(entree.getValue(), suite, fichier, egares);
        }
    }

    /**
     * Les emplacements acceptes pour une clef surveillee.
     *
     * <p>Enfant direct de {@code spring}, n'importe ou sous {@code spring} —
     * {@code spring.cloud.config} est legitime — ou n'importe ou sous
     * {@code management}, pour les indicateurs d'Actuator.
     */
    private static boolean emplacementTolere(List<String> pile) {
        if (pile.isEmpty()) {
            return false;
        }
        String racine = pile.get(0);
        return "spring".equals(racine) || TOLERE.equals(racine);
    }

    /** Le meme analyseur que celui de Spring Boot : il refuse les clefs dupliquees. */
    private static Yaml commeSpring() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options));
    }

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
