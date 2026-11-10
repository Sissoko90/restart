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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un module declare dans le reacteur doit etre livrable.
 *
 * <h2>Le defaut que ce garde-fou attrape</h2>
 *
 * Ajouter un module au {@code pom.xml} racine suffit a ce qu'il compile, passe
 * ses tests et apparaisse en {@code BUILD SUCCESS}. Rien dans la chaine ne
 * verifie qu'il peut <b>demarrer</b>.
 *
 * <p>C'est arrive avec {@code security-service} : construit, teste, soixante
 * assertions au vert — et aucun fichier dans {@code config-repo}. Donc aucun
 * port, aucune source de donnees, aucun Redis. Le module aurait echoue au
 * premier {@code docker compose up}, et sa base n'existait pas non plus. Un
 * commentaire de {@code setting-service} renvoyait meme a un fichier de
 * configuration qui n'avait jamais ete ecrit.
 *
 * <p>Il manquait aussi dans le generateur de collections Postman : les
 * endpoints existaient, et rien ne permettait de les appeler.
 *
 * <h2>Pourquoi ces trois listes, et pas une convention</h2>
 *
 * Chacune est une liste tenue a la main, dans un format different — YAML,
 * Python, SQL — et aucune n'est lue par le code qu'elle configure. L'oubli ne
 * casse donc rien de visible : il se manifeste au demarrage, c'est-a-dire
 * ailleurs et plus tard.
 */
@DisplayName("Tout module livrable est configure, routable et appelable")
class ModuleLivrableTest {

    /**
     * Modules qui n'ont pas de configuration propre, et pourquoi.
     *
     * <p>Liste BORNEE et justifiee, et non une facilite : chaque exception doit
     * se defendre, sinon le garde-fou ne garde plus rien.
     */
    private static final Map<String, String> SANS_CONFIGURATION = Map.of(
            // Bibliotheque partagee : aucun demarrage, aucun port.
            "platform-commons", "bibliotheque, pas un service",
            // C'est LUI qui sert config-repo : il ne peut pas s'y configurer.
            "config-service", "sert config-repo, ne peut s'y configurer");

    @Test
    @DisplayName("chaque service a son fichier dans config-repo")
    void chaqueServiceEstConfigure() throws IOException {
        Path racine = racineDuDepot();
        List<String> manquants = new ArrayList<>();

        for (String module : modulesDuReacteur(racine)) {
            if (SANS_CONFIGURATION.containsKey(module)) {
                continue;
            }
            Path configuration = racine.resolve("config-repo/dev/" + module + "-dev.yml");
            if (!Files.exists(configuration)) {
                manquants.add(module);
            }
        }

        assertThat(manquants)
                .as("modules du reacteur sans fichier config-repo/dev/<module>-dev.yml. "
                        + "Ils compilent, passent leurs tests, et n'ont ni port ni "
                        + "source de donnees : l'echec n'arrive qu'au demarrage")
                .isEmpty();
    }

    @Test
    @DisplayName("chaque port est attribue a UN seul service")
    void portsSansCollision() throws IOException {
        Path racine = racineDuDepot();
        Map<String, List<String>> parPort = new LinkedHashMap<>();

        try (Stream<Path> fichiers = Files.list(racine.resolve("config-repo/dev"))) {
            for (Path fichier : fichiers.sorted().toList()) {
                String nom = fichier.getFileName().toString();
                if (!nom.endsWith("-dev.yml") || nom.startsWith("application")) {
                    continue;
                }
                port(Files.readString(fichier)).ifPresent(port ->
                        parPort.computeIfAbsent(port, cle -> new ArrayList<>()).add(nom));
            }
        }

        assertThat(parPort).as("ports lus dans config-repo").isNotEmpty();

        List<String> collisions = parPort.entrySet().stream()
                .filter(entree -> entree.getValue().size() > 1)
                .map(entree -> entree.getKey() + " <- " + entree.getValue())
                .toList();

        // Deux services sur le meme port : le second refuse de demarrer, et
        // l'erreur — « Address already in use » — ne nomme pas le premier.
        assertThat(collisions).as("ports attribues deux fois").isEmpty();
    }

    @Test
    @DisplayName("chaque service a controleurs est dans le generateur Postman")
    void chaqueServiceEstAppelable() throws IOException {
        Path racine = racineDuDepot();
        String generateur = Files.readString(
                racine.resolve("scripts/postman/extract_endpoints.py"));
        List<String> manquants = new ArrayList<>();

        for (String module : modulesDuReacteur(racine)) {
            Path sources = racine.resolve(module + "/src/main/java");
            if (!Files.exists(sources) || !aDesControleurs(sources)) {
                continue;
            }
            if (!generateur.contains("\"" + module + "\"")) {
                manquants.add(module);
            }
        }

        assertThat(manquants)
                .as("services exposant des endpoints et absents de "
                        + "scripts/postman/extract_endpoints.py. Les endpoints "
                        + "existent, et rien ne permet de les appeler")
                .isEmpty();
    }

    @Test
    @DisplayName("chaque base nommee par une configuration est creee")
    void chaqueBaseEstCreee() throws IOException {
        Path racine = racineDuDepot();
        String creations = Files.readString(
                racine.resolve("docker/postgres-init/01-create-databases.sql"));
        List<String> manquantes = new ArrayList<>();

        // Seules les bases PostgreSQL : c'est ce que cree le script verifie.
        //
        // Un motif plus large a ete essaye, et il a accuse media_service_db —
        // qui est une base MongoDB, nommee dans une URI mongodb://. Un
        // garde-fou qui signale ce qu'il ne gouverne pas se fait desactiver,
        // et c'est alors ce qu'il gouvernait vraiment qui n'est plus garde.
        Pattern nomDeBase = Pattern.compile(
                "(?:r2dbc:|jdbc:)postgresql://[^/\\s]+/\\$?\\{?[A-Z_]*:?([a-z0-9_]+_db)");
        try (Stream<Path> fichiers = Files.list(racine.resolve("config-repo/dev"))) {
            for (Path fichier : fichiers.sorted().toList()) {
                if (!fichier.getFileName().toString().endsWith("-dev.yml")) {
                    continue;
                }
                Matcher base = nomDeBase.matcher(Files.readString(fichier));
                while (base.find()) {
                    String nom = base.group(1);
                    if (!creations.contains(nom)) {
                        manquantes.add(nom + " (" + fichier.getFileName() + ")");
                    }
                }
            }
        }

        assertThat(manquantes)
                .as("bases nommees par une configuration et absentes de "
                        + "docker/postgres-init/01-create-databases.sql. Flyway "
                        + "echouera au demarrage sur « database does not exist »")
                .isEmpty();
    }

    /**
     * Modules que la passerelle ne route pas, et pourquoi.
     *
     * <p>Liste BORNEE et justifiee, comme {@link #SANS_CONFIGURATION}.
     */
    private static final Map<String, String> SANS_ROUTAGE = Map.of(
            "platform-commons", "bibliotheque, aucun endpoint",
            "config-service", "sert config-repo, joint directement par les services",
            // La passerelle ne se route pas elle-meme : elle EST le routage.
            "gateway-service", "c'est elle qui route");

    @Test
    @DisplayName("chaque service a controleurs est JOIGNABLE par la passerelle")
    void chaqueServiceEstJoignable() throws IOException {
        Path racine = racineDuDepot();

        // IL N'Y A QU'UN SEUL MECANISME DE ROUTAGE, et ce test en lisait trois.
        //
        // Sa version precedente acceptait trois preuves : une mention dans le
        // RouterFunction, une adresse d'equilibrage dans le YAML, ou une
        // mention dans CheminCible. Les deux dernieres ne prouvaient rien.
        //
        // Le YAML : gateway-service ne declare pas
        // spring-cloud-starter-gateway, donc aucun moteur ne lisait ses onze
        // routes — qui etaient de surcroit indentees sous la clef
        // `geolocation:` et non sous `spring:`. Elles sont retirees.
        //
        // CheminCible : il dit vers QUEL service un prefixe doit aller, ce qui
        // sert au mode maintenance et a la signature. Il ne route rien. Un
        // prefixe peut y figurer sans qu'aucune route ne le serve — et six
        // l'etaient.
        //
        // Ne reste donc que la seule preuve qui en soit une.
        String routeur = Files.readString(racine.resolve(
                "gateway-service/src/main/java/com/abdatytch/gateway_service/"
                        + "config/GatewayRoutesConfig.java"));

        List<String> injoignables = new ArrayList<>();
        for (String module : modulesDuReacteur(racine)) {
            if (SANS_ROUTAGE.containsKey(module)) {
                continue;
            }
            Path sources = racine.resolve(module + "/src/main/java");
            if (!Files.exists(sources) || !aDesControleurs(sources)) {
                continue;
            }
            boolean route = routeur.contains("\"" + module + "\"");
            if (!route) {
                injoignables.add(module);
            }
        }

        assertThat(injoignables)
                .as("services exposant des endpoints et routes par AUCUNE route "
                        + "de la passerelle. Leurs endpoints existent, "
                        + "sont testes, et rendent 404 depuis l'exterieur — "
                        + "indistinguable d'un chemin inexistant. C'est arrive a "
                        + "security-service, complet et configure, dont les sept "
                        + "endpoints etaient injoignables")
                .isEmpty();
    }

    @Test
    @DisplayName("le garde-fou lit bien le reacteur")
    void leReacteurEstLu() throws IOException {
        // Sans cette assertion, un chemin casse rendrait une liste vide et
        // chacun des tests ci-dessus passerait sans rien verifier. Le defaut
        // exact qu'un garde-fou doit s'interdire.
        assertThat(modulesDuReacteur(racineDuDepot()))
                .hasSizeGreaterThan(8)
                .contains("auth-service", "gateway-service", "security-service");
    }

    private static List<String> modulesDuReacteur(Path racine) throws IOException {
        String pom = Files.readString(racine.resolve("pom.xml"));
        Matcher module = Pattern.compile("<module>([^<]+)</module>").matcher(pom);
        List<String> modules = new ArrayList<>();
        while (module.find()) {
            modules.add(module.group(1).trim());
        }
        return modules;
    }

    private static java.util.Optional<String> port(String yaml) {
        // Le port du SERVEUR, et non celui de Redis ou de PostgreSQL : seule la
        // forme « port: » en debut de ligne indentee de deux espaces, sous
        // `server:`, nous interesse. Les autres sont plus profondement indentes.
        Matcher port = Pattern.compile("(?m)^  port:\\s*(\\d+)\\s*$").matcher(yaml);
        return port.find() ? java.util.Optional.of(port.group(1)) : java.util.Optional.empty();
    }

    private static boolean aDesControleurs(Path sources) throws IOException {
        try (Stream<Path> fichiers = Files.walk(sources)) {
            return fichiers.anyMatch(fichier -> {
                String nom = fichier.getFileName().toString();
                return nom.endsWith("Controller.java");
            });
        }
    }

    /**
     * Racine du depot.
     *
     * <p>Le repertoire de travail d'un test Maven est celui du module : remonter
     * jusqu'au marqueur de racine est la seule façon fiable d'atteindre
     * {@code config-repo/} et le {@code pom.xml} du reacteur. Meme forme que
     * {@code MetriquesDesAlertesTest}.
     */
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
