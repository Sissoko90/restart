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

    /**
     * Modules dont les journaux ne vont nulle part, et pourquoi.
     *
     * <p>Liste BORNEE, comme {@link #SANS_CONFIGURATION} : une exception doit
     * se defendre.
     */
    private static final Map<String, String> SANS_JOURNALISATION = Map.of(
            // Bibliotheque partagee : elle n'a pas de processus, donc pas de
            // configuration de journalisation. Ses lignes sortent par celle du
            // module qui l'embarque.
            "platform-commons", "bibliotheque, pas un processus");

    @Test
    @DisplayName("chaque service expedie des journaux exploitables a l'agregateur")
    void chaqueServiceJournaliseVersLAgregateur() throws IOException {
        Path racine = racineDuDepot();
        Map<String, List<String>> defauts = new LinkedHashMap<>();

        for (String module : modulesDuReacteur(racine)) {
            if (SANS_JOURNALISATION.containsKey(module)) {
                continue;
            }
            List<String> manques = new ArrayList<>();

            Path fichier = racine.resolve(module + "/src/main/resources/logback-spring.xml");
            if (!Files.exists(fichier)) {
                // Sans ce fichier, Spring Boot journalise sur la console avec
                // son format par defaut : lisible, sans identifiant de trace, et
                // jamais expedie. Le module journalise tres bien localement,
                // donc rien ne signale que ses lignes n'arrivent nulle part.
                defauts.put(module, List.of("aucun logback-spring.xml"));
                continue;
            }

            // Commentaires RETIRES. Ce fichier-ci explique longuement pourquoi
            // traceId et Loki sont la : un garde-fou qui lit le texte brut se
            // contente de ces explications et accepte une configuration qui ne
            // les applique pas. C'est arrive deux fois le 2026-11-13, a un
            // autre garde-fou de ce depot.
            String xml = SourcesDuDepot.sansCommentairesXml(Files.readString(fichier));

            if (!xml.contains("%X{traceId")) {
                // Mesure du 2026-11-14 : media-service expediait vers Loki
                // depuis sa creation, sans identifiant de trace. La chaine
                // d'expedition marchait et son CONTENU ne servait a rien --
                // des lignes qu'on ne peut recouper ni avec une trace, ni avec
                // les journaux des autres services.
                manques.add("aucun %X{traceId} dans les motifs");
            }
            if (!xml.contains("%X{spanId")) {
                manques.add("aucun %X{spanId} dans les motifs");
            }
            if (!xml.contains("Loki4jAppender")) {
                manques.add("aucun appender Loki");
            } else if (!profilsDeLoki(xml)) {
                // Loki n'est demarre qu'ou son infrastructure est deployee. Un
                // appender actif en developpement echoue a chaque lot, et
                // loki4j deverse une pile complete par tentative : un demarrage
                // normal ressemble a une panne. Et une exclusion de « dev » ne
                // suffit pas -- les tests tournent SANS profil actif.
                manques.add("appender Loki hors d'une liste blanche de profils "
                        + "(prod | staging | observability)");
            }

            String pom = SourcesDuDepot.sansCommentairesXml(
                    Files.readString(racine.resolve(module + "/pom.xml")));

            // Un %X{traceId} dans le motif ne vaut rien sans ce qui le
            // REMPLIT. Spring Boot 4 a modularise son auto-configuration : le
            // pont micrometer-tracing-bridge-brave n'apporte que les classes,
            // et c'est cet artefact qui cable le Tracer et le decorateur de
            // MDC.
            //
            // Mesure du 2026-11-14 : sur huit services en marche, un seul
            // portait cet artefact. Les sept autres ecrivaient « traceId= »
            // des centaines de milliers de fois, toujours vide. Le champ etait
            // la, la valeur ne venait jamais -- et c'est le pire des deux
            // etats, parce qu'un champ vide se lit comme une requete sans
            // trace et non comme un tracage absent.
            if (xml.contains("%X{traceId")
                    && !pom.contains("spring-boot-micrometer-tracing")) {
                manques.add("motif avec %X{traceId} mais aucune auto-configuration "
                        + "de tracage (spring-boot-micrometer-tracing-brave) : le champ "
                        + "restera vide");
            }

            // Et le PONT, qui n'est PAS tire transitivement par
            // l'auto-configuration. Mesure du 2026-11-14 : cinq modules
            // avaient l'auto-configuration sans le pont, donc aucune
            // implementation de Tracer, donc un champ vide malgre les deux
            // autres conditions reunies.
            //
            // Trois morceaux sont necessaires et aucun ne suffit. Ce test en
            // verifie deux ; le troisieme, la propagation du contexte
            // reactif, est verifie par propagationDuContexteReactifActivee.
            if (xml.contains("%X{traceId")
                    && !pom.contains("micrometer-tracing-bridge")) {
                manques.add("motif avec %X{traceId} mais aucun pont de tracage "
                        + "(micrometer-tracing-bridge-brave) : aucune implementation "
                        + "de Tracer, le champ restera vide");
            }

            if (xml.contains("Loki4jAppender") && !pom.contains("loki-logback-appender")) {
                // Le fichier sans la dependance fait echouer le DEMARRAGE sur
                // une classe d'appender introuvable. L'inverse -- la dependance
                // sans le fichier -- est seulement du poids mort, et c'etait le
                // cas de config-service et notification-service.
                manques.add("appender Loki declare sans la dependance loki4j dans le POM");
            }

            if (!manques.isEmpty()) {
                defauts.put(module, manques);
            }
        }

        assertThat(defauts)
                .as("""
                        Modules dont les journaux ne sont pas exploitables.

                        Trois formes du meme defaut, toutes rencontrees le \
                        2026-11-14 : pas de fichier du tout (cinq modules), un \
                        fichier sans appender Loki (interface-service), et un \
                        appender Loki sans identifiant de trace (media-service).

                        Aucune ne se voyait : chacun de ces modules journalisait \
                        parfaitement sur sa propre console.""")
                .isEmpty();
    }

    @Test
    @DisplayName("la propagation du contexte reactif est activee pour toute la plateforme")
    void propagationDuContexteReactifActivee() throws IOException {
        Path partagee = racineDuDepot().resolve("config-repo/dev/application-dev.yml");
        String yaml = Files.readString(partagee);

        // La valeur par defaut est « limited ». L'auto-configuration du tracage
        // suffit a remplir le MDC d'une tache PLANIFIEE, qui tourne sur un fil
        // dedie -- mais pas celui d'une requete reactive, que WebFlux execute
        // sur les boucles d'evenements de Netty et les ordonnanceurs de
        // Reactor.
        //
        // Mesure du 2026-11-14 : auto-configuration seule, 0 ligne tracee ;
        // cette propriete seule, 0 ligne tracee ; les deux, 255. Aucune des
        // deux ne suffit, et c'est pourquoi le defaut a survecu si longtemps :
        // auth-service avait l'air de tracer.
        assertThat(yaml)
                .as("""
                        spring.reactor.context-propagation absent de la \
                        configuration partagee.

                        Sans « auto », le MDC de logback est perdu a chaque \
                        bascule d'ordonnanceur : %X{traceId} est vide sur TOUTES \
                        les requetes de TOUS les services, et le mode d'emploi \
                        d'observability/README.md -- trouver le traceId dans les \
                        logs, puis la trace -- n'est pas realisable.""")
                .contains("context-propagation: auto");
    }

    /**
     * L'appender Loki est-il borne a une liste blanche de profils ?
     *
     * <p>Verifie que le bloc {@code springProfile} qui l'entoure nomme au moins
     * {@code prod} et n'est pas une negation. Un {@code name="!dev"} laisserait
     * les tests, qui tournent sans profil actif, expedier dans le vide.
     */
    private static boolean profilsDeLoki(String xml) {
        int appender = xml.indexOf("Loki4jAppender");
        String avant = xml.substring(0, appender);
        int ouverture = avant.lastIndexOf("<springProfile");
        if (ouverture < 0) {
            return false;
        }
        int finBalise = avant.indexOf('>', ouverture);
        if (finBalise < 0) {
            return false;
        }
        String balise = avant.substring(ouverture, finBalise);
        return balise.contains("prod") && !balise.contains("!");
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
