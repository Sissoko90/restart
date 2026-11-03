package com.abdatytch.commons.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toute metrique interrogee par une alerte doit etre emise par quelqu'un.
 *
 * <h2>Le defaut que ce test ferme</h2>
 *
 * {@code observability/prometheus/alerts.yml} contenait les deux seules alertes
 * de securite de la plateforme :
 *
 * <pre>
 * rate(jwt_validation_failures_total[5m])  &gt; 0.1
 * rate(authentication_failures_total[5m])  &gt; 0.5
 * </pre>
 *
 * Aucune de ces deux familles n'existe. Verifie sur l'endpoint reel
 * d'auth-service — 610 series exposees, ni l'une ni l'autre dedans — et aucun
 * code du depot ne les cree : ce qui est emis s'appelle
 * {@code auth_login_failure_total}.
 *
 * <p>Deux autres regles etaient dans le meme cas :
 * {@code hikaricp_connections_*} sur des services qui utilisent R2DBC, et
 * {@code redis_commands_failed_total}, que le client Lettuce n'emet pas.
 *
 * <h2>Pourquoi rien ne le signalait</h2>
 *
 * Prometheus evalue sans erreur une regle portant sur une metrique absente :
 * l'expression rend un ensemble vide, la condition est fausse, l'alerte ne se
 * declenche pas. Il n'y a ni avertissement au demarrage, ni trace dans les
 * journaux. Le fichier d'alertes donne donc l'apparence d'une couverture, et
 * c'est la pire forme — on ne cherche pas ce qu'on croit avoir.
 *
 * <p>Meme nature que les 33 {@code @PreAuthorize} inertes, que la liste noire
 * d'adresses que personne n'alimentait, et que le garde-fou de methode compare
 * avec {@code HttpMethod.matches} : une protection ecrite, presente, jamais
 * atteinte.
 *
 * <h2>Ce que ce test ne verifie pas</h2>
 *
 * Qu'une metrique soit emise ne dit pas que le code qui l'emet <b>tourne</b>.
 * {@code recordTokenRevocation} existe dans {@code AuthMetrics} et n'est appele
 * par personne : ce test l'accepterait. La verification du cablage est faite
 * par {@link #aucuneMetriqueDefinieNEstOrpheline()}, qui porte sur les
 * metriques d'authentification.
 */
@DisplayName("Alertes Prometheus — aucune ne doit porter sur une metrique inexistante")
class MetriquesDesAlertesTest {

    /**
     * Metriques fournies par le cadre d'execution ou l'infrastructure.
     *
     * <p>Elles n'apparaissent dans aucun {@code Counter.builder} du depot parce
     * que ce n'est pas nous qui les creons. La liste est <b>exhaustive et
     * fermee</b> a dessein : y ajouter un prefixe large comme {@code ""}
     * rendrait ce test incapable de rien detecter.
     */
    private static final Set<String> METRIQUES_DE_PLATEFORME = Set.of(
            // Spring Boot / Micrometer
            "http_server_requests",
            "http_client_requests",
            "jvm_memory_used_bytes", "jvm_memory_max_bytes", "jvm_memory_committed_bytes",
            "jvm_gc_pause", "jvm_threads_live_threads", "jvm_threads_states_threads",
            "process_cpu_usage", "process_uptime_seconds", "process_files_open_files",
            "process_start_time_seconds",
            "system_cpu_usage", "system_load_average_1m",
            "disk_free_bytes", "disk_total_bytes",
            "logback_events",
            "application_ready_time_seconds",
            // R2DBC — pilote reactif reellement utilise par ces services
            "r2dbc_pool_acquired_connections", "r2dbc_pool_allocated_connections",
            "r2dbc_pool_idle_connections", "r2dbc_pool_max_allocated_connections",
            "r2dbc_pool_pending_connections",
            // Observation `r2dbc.query`, fournie par R2dbcObservationAutoConfiguration
            // des que r2dbc-proxy est au classpath. Nom verifie dans
            // QueryObservationConvention du jar, pas suppose.
            "r2dbc_query_seconds",
            // Redis via Lettuce
            "lettuce_command_completion", "lettuce_command_firstresponse",
            "lettuce_active", "lettuce",
            // MongoDB — MongoMetricsConnectionPoolListener, auto-configure par
            // Spring Boot quand le pilote est present (media-service ne le
            // desactive pas)
            "mongodb_driver_pool_size", "mongodb_driver_pool_checkedout",
            "mongodb_driver_pool_waitqueuesize",
            "mongodb_driver_commands",
            // Kafka
            "kafka_consumer_fetch_manager_records_lag",
            "kafka_producer_record_send", "spring_kafka_listener",
            // Prometheus lui-meme et exporters
            "up", "scrape_duration_seconds", "scrape_samples_scraped",
            "prometheus_notifications_dropped", "prometheus_rule_evaluation_failures",
            "node_filesystem_avail_bytes", "node_filesystem_size_bytes",
            "node_memory_MemAvailable_bytes", "node_cpu_seconds",
            // Resilience4j
            "resilience4j_circuitbreaker_state", "resilience4j_circuitbreaker_calls",
            // Verifiees presentes sur l'endpoint reel d'auth-service, parmi les
            // onze familles resilience4j exposees. Les instances creees par le
            // registre Spring sont instrumentees par resilience4j-micrometer ;
            // une instance creee hors de lui ne le serait pas.
            "resilience4j_circuitbreaker_not_permitted_calls",
            "resilience4j_circuitbreaker_failure_rate",
            "resilience4j_circuitbreaker_slow_call_rate",
            "resilience4j_circuitbreaker_buffered_calls",
            "resilience4j_circuitbreaker_slow_calls",
            "resilience4j_bulkhead_available_concurrent_calls",
            "resilience4j_ratelimiter_available_permissions");

    /**
     * Plancher de detection.
     *
     * <p>Si l'analyse des fichiers de regles cesse de fonctionner — chemin
     * change, format modifie — elle rendrait zero metrique, et le test
     * passerait en ne verifiant rien. Ce depot s'est deja fait prendre deux
     * fois par un harnais vert qui ne mesurait plus rien.
     */
    private static final int PLANCHER_METRIQUES_EXAMINEES = 20;

    private static final int PLANCHER_FICHIERS_DE_REGLES = 3;

    /**
     * Plancher du nombre de classes de metriques attendues.
     *
     * <p>Meme raison que les deux planchers precedents : si la recherche de
     * fichiers cesse de trouver quoi que ce soit, le test passerait en ne
     * verifiant rien.
     */
    private static final int PLANCHER_CLASSES_DE_METRIQUES = 4;

    /** Identifiants candidats dans une expression PromQL, etiquettes deja retirees. */
    private static final Pattern METRIQUE = Pattern.compile("\\b([a-z][a-z0-9_]{3,})\\b");

    /**
     * Litteral en forme de nom de metrique Micrometer.
     *
     * <p>Minuscules, et <b>au moins un point</b> : c'est ce qui le distingue
     * d'une valeur d'etiquette ({@code "blocage"}, {@code "user-service"}) ou
     * d'un nom de champ. Ce motif n'est applique qu'aux classes de metriques,
     * ou un tel litteral ne peut rien etre d'autre.
     */
    private static final Pattern LITTERAL_DE_METRIQUE =
            Pattern.compile("\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)+)\"");

    /** Fonctions et mots-cles PromQL, a ne pas confondre avec des metriques. */
    private static final Set<String> MOTS_PROMQL = Set.of(
            "rate", "irate", "increase", "sum", "avg", "min", "max", "count",
            "histogram_quantile", "topk", "bottomk", "quantile", "stddev",
            "by", "without", "and", "or", "unless", "on", "ignoring",
            "group_left", "group_right", "absent", "absent_over_time",
            "delta", "idelta", "deriv", "predict_linear", "changes", "resets",
            "clamp_max", "clamp_min", "abs", "ceil", "floor", "round", "sqrt",
            "exp", "ln", "log2", "log10", "time", "timestamp", "vector",
            "sum_over_time", "avg_over_time", "max_over_time", "min_over_time",
            "count_over_time", "last_over_time", "label_replace", "label_join",
            "humanizePercentage", "humanize", "humanizeDuration", "value",
            "labels", "alertname", "application", "instance", "severity", "job");

    // ==================================================================
    @Nested
    @DisplayName("Chaque metrique interrogee a une source")
    class ChaqueMetriqueAUneSource {

        @Test
        @DisplayName("aucune alerte ne porte sur une metrique que rien n'emet")
        void aucuneMetriqueFantome() throws IOException {
            Set<String> emises = metriquesEmisesParLeDepot();
            Map<String, List<String>> interrogees = metriquesDesRegles();

            assertThat(interrogees)
                    .withFailMessage("aucune metrique extraite des fichiers de regles : "
                            + "l'analyse est cassee, et ce test ne verifie plus rien")
                    .hasSizeGreaterThanOrEqualTo(PLANCHER_METRIQUES_EXAMINEES);

            Map<String, List<String>> fantomes = new LinkedHashMap<>();
            interrogees.forEach((metrique, alertes) -> {
                if (!estConnue(metrique, emises)) {
                    fantomes.put(metrique, alertes);
                }
            });

            assertThat(fantomes)
                    .withFailMessage("""
                            Ces metriques sont interrogees par des alertes et AUCUN code \
                            du depot ne les emet :

                            %s

                            Prometheus evalue une telle regle sans erreur : l'expression \
                            rend un ensemble vide, la condition est fausse, l'alerte ne se \
                            declenche JAMAIS. Rien ne le signale, ni au demarrage ni dans \
                            les journaux.

                            Trois corrections possibles :
                              - corriger le nom (verifier sur /actuator/prometheus du service) ;
                              - emettre la metrique depuis le detenteur de la regle metier ;
                              - si elle vient du cadre d'execution ou de l'infrastructure, \
                            l'ajouter a METRIQUES_DE_PLATEFORME dans ce test — apres avoir \
                            VERIFIE qu'elle est bien exposee.""",
                            rendreLisible(fantomes))
                    .isEmpty();
        }

        /**
         * Les tableaux de bord sont dans le meme cas que les alertes.
         *
         * <p>Un panneau qui interroge une metrique absente ne produit ni erreur
         * ni message : il affiche « No data », indistinguable d'une periode sans
         * activite. Mesure au moment d'ecrire ce test : le panneau « Database
         * Connection Pool (HikariCP) » du tableau de bord auth-service
         * interrogeait {@code hikaricp_connections_*} sur un service qui utilise
         * R2DBC — vide depuis sa creation.
         *
         * <p>C'est pire qu'une alerte muette : on REGARDE le panneau, et on
         * conclut que tout va bien.
         */
        @Test
        @DisplayName("aucun panneau Grafana ne porte sur une metrique inexistante")
        void aucunPanneauFantome() throws IOException {
            Set<String> emises = metriquesEmisesParLeDepot();
            Map<String, List<String>> interrogees = metriquesDesTableauxDeBord();

            assertThat(interrogees)
                    .withFailMessage("aucune metrique extraite des tableaux de bord : "
                            + "l'analyse est cassee, et ce test ne verifie plus rien")
                    .hasSizeGreaterThanOrEqualTo(PLANCHER_METRIQUES_EXAMINEES);

            Map<String, List<String>> fantomes = new LinkedHashMap<>();
            interrogees.forEach((metrique, panneaux) -> {
                if (!estConnue(metrique, emises)) {
                    fantomes.put(metrique, panneaux);
                }
            });

            assertThat(fantomes)
                    .withFailMessage("""
                            Ces metriques sont interrogees par des panneaux Grafana et \
                            AUCUN code du depot ne les emet :

                            %s

                            Le panneau affichera « No data », ce qui ne se distingue pas \
                            d'une periode sans activite. On regarde le tableau de bord, et \
                            on en conclut que tout va bien.""",
                            rendreLisible(fantomes))
                    .isEmpty();
        }

        @Test
        @DisplayName("tous les fichiers de regles sont charges par prometheus.yml")
        void tousLesFichiersSontCharges() throws IOException {
            // `user-service-alerts.yml` — onze alertes — existait et n'etait pas
            // charge : `rule_files` ne listait que `alerts.yml`. Prometheus n'a
            // aucune raison de signaler un fichier qu'on ne lui demande pas de
            // lire.
            Path repertoire = racineDuDepot().resolve("observability/prometheus");
            String config = Files.readString(repertoire.resolve("prometheus.yml"));

            List<Path> fichiersDeRegles = fichiersDeRegles();
            assertThat(fichiersDeRegles)
                    .hasSizeGreaterThanOrEqualTo(PLANCHER_FICHIERS_DE_REGLES);

            List<String> nonCharges = new ArrayList<>();
            for (Path fichier : fichiersDeRegles) {
                String nom = fichier.getFileName().toString();
                boolean cite = config.contains("'" + nom + "'")
                        || config.contains("\"" + nom + "\"")
                        // Le glob couvre tout ce qui finit par -alerts.yml
                        || (config.contains("'*-alerts.yml'") && nom.endsWith("-alerts.yml"));
                if (!cite) {
                    nonCharges.add(nom);
                }
            }

            assertThat(nonCharges)
                    .withFailMessage("""
                            Ces fichiers de regles existent et ne sont pas charges par \
                            prometheus.yml : %s

                            Les alertes qu'ils contiennent ne seront jamais evaluees, et \
                            Prometheus demarrera sans rien dire.""", nonCharges)
                    .isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Chaque metrique definie a un appelant")
    class ChaqueMetriqueAUnAppelant {

        /**
         * Une metrique definie et jamais incrementee n'existe pas a l'execution.
         *
         * <p>Micrometer ne cree la serie qu'au premier appel : une methode
         * {@code recordXxx} que personne n'appelle ne produit aucune metrique,
         * donc aucun panneau et aucune alerte. Mesure au moment d'ecrire ce
         * test : {@code recordTokenRevocation} etait dans ce cas, et
         * {@code recordAccountLockout} etait appele depuis un seul des quatre
         * chemins qui verrouillent.
         */
        @Test
        @DisplayName("aucune methode recordXxx d'AuthMetrics n'est orpheline")
        void aucuneMetriqueDefinieNEstOrpheline() throws IOException {
            Path racine = racineDuDepot();
            Path source = racine.resolve(
                    "auth-service/src/main/java/com/abdatytch/auth_service/metrics/AuthMetrics.java");
            assertThat(source)
                    .withFailMessage("AuthMetrics introuvable : ce garde-fou serait vert "
                            + "sans rien verifier")
                    .exists();

            Set<String> definies = new TreeSet<>();
            Matcher m = Pattern.compile("public void (record[A-Za-z]+)\\s*\\(")
                    .matcher(Files.readString(source));
            while (m.find()) {
                definies.add(m.group(1));
            }
            assertThat(definies)
                    .withFailMessage("aucune methode extraite d'AuthMetrics : analyse cassee")
                    .hasSizeGreaterThanOrEqualTo(8);

            String sourcesAuth = concatenerSources(
                    racine.resolve("auth-service/src/main/java"));

            Set<String> orphelines = new TreeSet<>();
            for (String methode : definies) {
                // Un seul appel suffit, mais il doit venir d'ailleurs que de la
                // definition : on compte les occurrences de « .methode( ».
                if (!sourcesAuth.contains("." + methode + "(")) {
                    orphelines.add(methode);
                }
            }

            assertThat(orphelines)
                    .withFailMessage("""
                            Ces metriques sont DEFINIES et jamais incrementees : %s

                            Micrometer ne cree la serie qu'au premier appel. Une metrique \
                            jamais incrementee n'apparait pas sur /actuator/prometheus, donc \
                            son panneau reste vide et son alerte ne se declenche jamais — \
                            sans que rien ne distingue cela d'une absence d'incident.

                            Soit l'appeler depuis le detenteur de la regle, soit la retirer.""",
                            orphelines)
                    .isEmpty();
        }

        /**
         * Une classe de metriques que personne n'injecte n'emet rien.
         *
         * <h3>Le defaut que ce test ferme</h3>
         *
         * {@code UserServiceMetrics} enregistrait quatorze compteurs et trois
         * jauges dans un {@code @PostConstruct}. La classe etait un
         * {@code @Component}, donc instanciee, donc les series apparaissaient
         * bien sur {@code /actuator/prometheus} — a zero. Et <b>aucune classe ne
         * l'injectait</b> : les compteurs ne pouvaient pas bouger.
         *
         * <p>Le tableau de bord user-service interrogeait seize de ces series.
         * Il affichait des zeros plats depuis sa creation, ce qui ne se
         * distingue pas d'un service inactif — c'est d'ailleurs ainsi que le
         * defaut a ete remarque : par un panneau CPU a 0 %.
         *
         * <h3>Pourquoi le garde-fou precedent ne le voyait pas</h3>
         *
         * {@link #aucuneMetriqueDefinieNEstOrpheline()} ne regardait qu'un
         * fichier, {@code AuthMetrics}, et ne cherchait que les methodes
         * nommees {@code recordXxx}. Un garde-fou pose sur un seul module ne dit
         * rien des dix autres.
         *
         * <h3>Pourquoi au niveau de la CLASSE et non de la methode</h3>
         *
         * Les deux, et la classe d'abord : une classe de metriques dont une
         * seule methode est appelee est suspecte, mais une classe dont
         * <b>aucune</b> n'est injectee est forcement morte. Le controle de
         * classe ne peut pas donner de faux positif.
         */
        @Test
        @DisplayName("aucune classe de metriques n'est orpheline")
        void aucuneClasseDeMetriquesNEstOrpheline() throws IOException {
            List<Path> classes = classesDeMetriques();
            assertThat(classes)
                    .withFailMessage("aucune classe de metriques trouvee : l'analyse est "
                            + "cassee, et ce test ne verifie plus rien")
                    .hasSizeGreaterThanOrEqualTo(PLANCHER_CLASSES_DE_METRIQUES);

            Map<String, List<String>> orphelines = new LinkedHashMap<>();
            for (Path classe : classes) {
                String nom = classe.getFileName().toString().replace(".java", "");
                if (!estInjecteeAilleurs(nom, classe)) {
                    orphelines.put(nom, List.of(
                            racineDuDepot().relativize(classe).toString()));
                }
            }

            assertThat(orphelines)
                    .withFailMessage("""
                            Ces classes de metriques ne sont injectees NULLE PART : %s

                            Une classe de metriques que rien n'injecte enregistre peut-etre \
                            ses series — si elle le fait dans un @PostConstruct — mais aucune \
                            ne sera jamais incrementee. Le panneau qui les interroge affiche \
                            des zeros plats, ce qui ne se distingue pas d'un service inactif.

                            C'est exactement ce qu'etait UserServiceMetrics : quatorze \
                            compteurs, trois jauges, zero appelant, et un tableau de bord de \
                            seize panneaux pose dessus.

                            Soit la cabler la ou l'evenement se produit, soit la retirer.""",
                            orphelines)
                    .isEmpty();
        }

        /**
         * Le nom de la classe apparait-il dans une autre source principale ?
         *
         * <p>Une injection par constructeur, un champ, un import, un appel
         * statique : toutes ces formes citent le nom. Son absence totale des
         * autres sources est donc une preuve, pas une presomption.
         */
        private boolean estInjecteeAilleurs(String nom, Path saPropreSource) throws IOException {
            Pattern reference = Pattern.compile("\\b" + Pattern.quote(nom) + "\\b");
            try (Stream<Path> fichiers = Files.walk(racineDuDepot())) {
                for (Path source : fichiers
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> p.toString().contains("/src/main/java/"))
                        .filter(p -> !p.toString().contains("/target/"))
                        .filter(p -> !p.equals(saPropreSource))
                        .toList()) {
                    if (reference.matcher(Files.readString(source)).find()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // ==================================================================
    // Outillage
    // ==================================================================

    /**
     * La comparaison porte sur la RACINE des deux cotes.
     *
     * <p>Le cote interroge est deja normalise ({@code process_start_time_seconds}
     * devient {@code process_start_time}), donc la liste de plateforme doit
     * l'etre aussi — sinon une metrique parfaitement exposee passe pour un
     * fantome a cause d'un suffixe. Mesure : c'est exactement ce qui est
     * arrive a {@code process_start_time_seconds}.
     */
    private static boolean estConnue(String metrique, Set<String> emises) {
        if (emises.contains(metrique)) {
            return true;
        }
        for (String connue : METRIQUES_DE_PLATEFORME) {
            String racine = racineDeLaMetrique(connue);
            if (metrique.equals(racine) || metrique.startsWith(racine + "_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Metriques emises par le depot, sous leur forme Prometheus.
     *
     * <p>Micrometer traduit les points en soulignes :
     * {@code Counter.builder("auth.login.failure")} devient
     * {@code auth_login_failure_total}. Les suffixes ({@code _total},
     * {@code _seconds_count}, ...) sont retires cote interrogation, de sorte
     * que la comparaison porte sur la racine.
     */
    private static Set<String> metriquesEmisesParLeDepot() throws IOException {
        Path racine = racineDuDepot();
        Set<String> emises = new LinkedHashSet<>();

        Pattern builder = Pattern.compile(
                "(?:Counter|Timer|Gauge|DistributionSummary)\\s*\\.builder\\(\\s*\"([^\"]+)\"");
        // `meterRegistry.gauge("nom", ...)` : forme directe, sans builder.
        // Son absence faisait passer geo_blocked_countries_count pour un
        // fantome alors qu'elle est bien exposee — verifie sur l'endpoint du
        // Gateway.
        //
        // `registre` a ete ajoute a la liste : ce depot nomme son code en
        // francais, et MetriquesDeSecurite (security-service) appelle
        // `registre.gauge(...)`. Sans ce nom, le garde-fou declarait
        // geo_blocked_countries_count fantome alors qu'elle venait d'etre
        // DEPLACEE la — un faux positif qui aurait pousse a « corriger » une
        // metrique correcte, voire a supprimer l'alerte qui l'interroge.
        //
        // Le vocabulaire de ce test doit suivre la convention de nommage du
        // depot, sinon il devient un garde-fou qui se trompe a mesure que le
        // code se francise.
        Pattern jaugeDirecte = Pattern.compile(
                "(?:meterRegistry|registry|registre)\\s*\\.gauge\\(\\s*\"([^\"]+)\"");
        Pattern constante = Pattern.compile(
                "String\\s+METRIQUE[A-Z_]*\\s*=\\s*\"([^\"]+)\"");

        try (Stream<Path> fichiers = Files.walk(racine)) {
            List<Path> sources = fichiers
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .toList();

            for (Path source : sources) {
                String contenu = Files.readString(source);
                for (Pattern motif : List.of(builder, jaugeDirecte, constante)) {
                    Matcher m = motif.matcher(contenu);
                    while (m.find()) {
                        // Normalise des DEUX cotes : le depot ecrit
                        // « auth.login.failure » ici et
                        // « geo_blocked_requests_total » la. Ne normaliser que
                        // le cote interroge faisait passer pour fantomes les
                        // cinq metriques de geolocalisation, qui existent.
                        emises.add(racineDeLaMetrique(m.group(1).replace('.', '_')));
                    }
                }
            }
        }

        // DANS une classe de metriques, tout litteral en forme de nom de
        // metrique en est un.
        //
        // Les trois motifs ci-dessus exigent que le nom soit ecrit LITTERALEMENT
        // dans l'appel : `Counter.builder("auth.login.failure")`. Des qu'une
        // classe factorise ses onze compteurs derriere un
        // `compteur(nom, description)`, plus aucun nom n'est visible a cet
        // endroit — et le garde-fou declare fantomes les onze metriques, qui
        // existent pourtant.
        //
        // C'est arrive exactement ainsi : la reecriture d'UserMetrics en une
        // classe unique a fait passer ses dix series pour inexistantes. Un
        // garde-fou qui punit la factorisation du code qu'il surveille finit
        // contourne.
        for (Path classeDeMetriques : classesDeMetriques()) {
            Matcher m = LITTERAL_DE_METRIQUE.matcher(Files.readString(classeDeMetriques));
            while (m.find()) {
                emises.add(racineDeLaMetrique(m.group(1).replace('.', '_')));
            }
        }
        return emises;
    }

    /**
     * Metrique -> alertes qui l'interrogent.
     *
     * <h3>Pourquoi un vrai parseur YAML</h3>
     *
     * La premiere version lisait les fichiers ligne a ligne. Elle ramassait les
     * noms de GROUPES ({@code - name: microservices_alerts}) et des mots de la
     * prose des annotations ({@code table erasure_requests}) comme s'il
     * s'agissait de metriques : six faux positifs sur dix-sept. Un test qui
     * crie au loup est un test qu'on finit par desactiver.
     *
     * <p>Avec snakeyaml, seule la valeur de {@code expr} est examinee — ni les
     * noms, ni les etiquettes, ni les descriptions.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> metriquesDesRegles() throws IOException {
        Map<String, List<String>> resultat = new LinkedHashMap<>();

        for (Path fichier : fichiersDeRegles()) {
            Object racine = new org.yaml.snakeyaml.Yaml()
                    .load(Files.readString(fichier));
            if (!(racine instanceof Map<?, ?> document)) {
                continue;
            }
            Object groupes = document.get("groups");
            if (!(groupes instanceof List<?> listeDeGroupes)) {
                continue;
            }
            for (Object groupe : listeDeGroupes) {
                if (!(groupe instanceof Map<?, ?> g)) {
                    continue;
                }
                Object regles = g.get("rules");
                if (!(regles instanceof List<?> listeDeRegles)) {
                    continue;
                }
                for (Object regle : listeDeRegles) {
                    if (!(regle instanceof Map<?, ?> r)) {
                        continue;
                    }
                    Object expr = r.get("expr");
                    if (expr == null) {
                        continue;
                    }
                    String nomAlerte = String.valueOf(r.get("alert"));
                    for (String metrique : metriquesDe(String.valueOf(expr))) {
                        resultat.computeIfAbsent(metrique, c -> new ArrayList<>())
                                .add(nomAlerte + " (" + fichier.getFileName() + ")");
                    }
                }
            }
        }
        return resultat;
    }

    /**
     * Metrique -> panneaux qui l'interrogent.
     *
     * <p>Les expressions vivent dans {@code panels[].targets[].expr}, y compris
     * dans les panneaux imbriques sous une rangee ({@code row}).
     */
    private static Map<String, List<String>> metriquesDesTableauxDeBord() throws IOException {
        Map<String, List<String>> resultat = new LinkedHashMap<>();
        Path repertoire = racineDuDepot().resolve("observability/grafana/dashboards");

        try (Stream<Path> fichiers = Files.list(repertoire)) {
            for (Path fichier : fichiers.filter(p -> p.toString().endsWith(".json")).toList()) {
                // Lecture via snakeyaml : le JSON est un sous-ensemble de YAML,
                // ce qui evite une dependance de plus pour une seule lecture.
                Object racine = new org.yaml.snakeyaml.Yaml().load(Files.readString(fichier));
                if (racine instanceof Map<?, ?> document) {
                    collecterPanneaux(panneauxDe(document),
                            fichier.getFileName().toString(), resultat);
                }
            }
        }
        return resultat;
    }

    /**
     * Les panneaux, que le document soit a plat ou enveloppe.
     *
     * <p>Grafana exporte sous deux formes : les panneaux a la racine, ou
     * enveloppes dans une clef {@code dashboard} — selon qu'on exporte depuis
     * l'interface ou qu'on recupere le document par l'API.
     *
     * <p>Ce garde-fou ne lisait que la premiere. Consequence : les seize
     * requetes de {@code user-service-dashboard.json}, qui est enveloppe,
     * etaient <b>invisibles</b> — et il interrogeait des metriques que rien
     * n'emet. Le garde-fou portait donc exactement le defaut qu'il surveille :
     * il lisait une structure qui n'est pas toujours celle-la, et ne trouvait
     * rien sans le dire.
     *
     * <p>C'est la raison de l'assertion « des panneaux ont ete lus » plus bas :
     * un lecteur qui ne trouve rien doit echouer, pas se taire.
     */
    private static Object panneauxDe(Map<?, ?> document) {
        Object panneaux = document.get("panels");
        if (panneaux != null) {
            return panneaux;
        }
        return document.get("dashboard") instanceof Map<?, ?> enveloppe
                ? enveloppe.get("panels")
                : null;
    }

    private static void collecterPanneaux(
            Object panneaux, String fichier, Map<String, List<String>> resultat) {
        if (!(panneaux instanceof List<?> liste)) {
            return;
        }
        for (Object panneau : liste) {
            if (!(panneau instanceof Map<?, ?> p)) {
                continue;
            }
            collecterPanneaux(p.get("panels"), fichier, resultat);   // rangees imbriquees
            Object cibles = p.get("targets");
            if (!(cibles instanceof List<?> listeDeCibles)) {
                continue;
            }
            String titre = String.valueOf(p.get("title"));
            for (Object cible : listeDeCibles) {
                if (!(cible instanceof Map<?, ?> c)) {
                    continue;
                }
                Object expr = c.get("expr");
                if (expr == null) {
                    continue;
                }
                for (String metrique : metriquesDe(String.valueOf(expr))) {
                    resultat.computeIfAbsent(metrique, k -> new ArrayList<>())
                            .add(titre + " (" + fichier + ")");
                }
            }
        }
    }

    /** Noms de metriques dans une expression PromQL, hors fonctions et etiquettes. */
    private static Set<String> metriquesDe(String expression) {
        Set<String> trouvees = new LinkedHashSet<>();
        // Les selecteurs d'etiquettes sont retires d'abord : ils contiennent des
        // noms d'etiquettes (`application`, `uri`, `status`) qui ne sont pas des
        // metriques.
        // Les selecteurs d'etiquettes partent d'abord : ils contiennent des
        // noms d'etiquettes (`application`, `uri`, `status`) qui ne sont pas
        // des metriques.
        String sansEtiquettes = expression.replaceAll("\\{[^}]*\\}", " ");
        // Puis les groupements : `by (country_name)` designe une ETIQUETTE.
        // Sans cette ligne, `country_name` etait signale comme metrique
        // fantome dans quatre panneaux de geolocalisation — faux positif, et
        // un test qui crie au loup finit desactive.
        sansEtiquettes = sansEtiquettes
                .replaceAll("\\b(?:by|without|on|ignoring|group_left|group_right)\\s*\\([^)]*\\)", " ");
        Matcher m = METRIQUE.matcher(sansEtiquettes);
        while (m.find()) {
            String nom = m.group(1);
            if (MOTS_PROMQL.contains(nom) || !nom.contains("_")) {
                continue;
            }
            trouvees.add(racineDeLaMetrique(nom));
        }
        return trouvees;
    }

    /** Retire les suffixes que Micrometer ajoute a l'export. */
    private static String racineDeLaMetrique(String nom) {
        for (String suffixe : List.of("_seconds_bucket", "_seconds_count", "_seconds_sum",
                "_seconds_max", "_seconds", "_bucket", "_total", "_count", "_sum", "_max")) {
            if (nom.endsWith(suffixe)) {
                return nom.substring(0, nom.length() - suffixe.length());
            }
        }
        return nom;
    }

    /**
     * Les classes dont le metier est de porter des metriques.
     *
     * <p>Reconnues par leur nom, dans les deux langues du depot : {@code Metrics}
     * et {@code Metriques}. Les classes de <b>configuration</b> ({@code
     * MetricsConfig}) et de <b>service</b> ({@code DocumentMetricsService}) sont
     * exclues : la premiere declare des beans, la seconde est un service metier
     * dont le nom contient le mot, et ni l'une ni l'autre ne correspond au
     * defaut surveille.
     */
    private static List<Path> classesDeMetriques() throws IOException {
        try (Stream<Path> fichiers = Files.walk(racineDuDepot())) {
            return fichiers
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> {
                        String nom = p.getFileName().toString();
                        if (!nom.endsWith(".java")) {
                            return false;
                        }
                        String classe = nom.substring(0, nom.length() - ".java".length());
                        return (classe.endsWith("Metrics") || classe.startsWith("Metriques"))
                                && !classe.endsWith("Config")
                                && !classe.endsWith("Service")
                                && !classe.endsWith("ServiceImpl");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> fichiersDeRegles() throws IOException {
        Path repertoire = racineDuDepot().resolve("observability/prometheus");
        try (Stream<Path> fichiers = Files.list(repertoire)) {
            return fichiers
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .filter(p -> !p.getFileName().toString().equals("prometheus.yml"))
                    .sorted()
                    .toList();
        }
    }

    private static String concatenerSources(Path racine) throws IOException {
        StringBuilder tout = new StringBuilder();
        try (Stream<Path> fichiers = Files.walk(racine)) {
            for (Path p : fichiers.filter(x -> x.toString().endsWith(".java")).toList()) {
                tout.append(Files.readString(p));
            }
        }
        return tout.toString();
    }

    private static String rendreLisible(Map<String, List<String>> fantomes) {
        StringBuilder sb = new StringBuilder();
        fantomes.forEach((metrique, alertes) ->
                sb.append("  - ").append(metrique).append("  <- ").append(alertes).append('\n'));
        return sb.toString();
    }

    /**
     * Racine du depot.
     *
     * <p>Le repertoire de travail d'un test Maven est celui du module : remonter
     * jusqu'au marqueur de racine est la seule façon fiable d'atteindre
     * {@code observability/} et les sources des autres modules.
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
