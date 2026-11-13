package com.abdatytch.commons.observability;

import com.abdatytch.commons.plateforme.SourcesDuDepot;
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
import java.util.TreeMap;
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

    /**
     * Les annotations qui font appeler une methode par le cadre d'execution.
     *
     * <p>Exiger un appelant dans le depot pour une methode annotee ainsi est un
     * faux positif : c'est Spring qui l'appelle.
     */
    private static final List<String> ANNOTATIONS_DE_CYCLE_DE_VIE = List.of(
            "@PostConstruct", "@PreDestroy", "@Scheduled", "@EventListener",
            "@Bean", "@Override");

    /** La methode qui commence a {@code debut} porte-t-elle une telle annotation. */
    private static boolean appeleeParLeCadre(String code, int debut) {
        // Les deux cents caracteres qui precedent : assez pour couvrir les
        // annotations et la javadoc courte, pas assez pour attraper celles de
        // la methode d'avant.
        String avant = code.substring(Math.max(0, debut - 200), debut);
        return ANNOTATIONS_DE_CYCLE_DE_VIE.stream().anyMatch(avant::contains);
    }

    /** Le garde-fou se garde : sans methodes extraites, il ne verifie rien. */
    private static final int PLANCHER_METHODES_DE_METRIQUES = 20;

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
         * Une metrique definie et jamais alimentee n'existe pas a l'execution.
         *
         * <h3>Ce que ce controle regardait, et pourquoi c'etait trop etroit</h3>
         *
         * Il ne lisait qu'un fichier — {@code AuthMetrics} — et ne cherchait
         * que les methodes nommees {@code recordXxx}. La javadoc du controle de
         * CLASSE le disait deja : « un garde-fou pose sur un seul module ne dit
         * rien des dix autres ».
         *
         * <p>Il a fallu un onzieme module pour le payer. {@code MarketMetrics}
         * est bien injectee — donc le controle de classe passait — mais TROIS
         * de ses cinq methodes n'avaient aucun appelant : {@code horsPortee},
         * {@code photosNonSignees} et {@code dureeDuCatalogue}. Toutes trois
         * etaient visees par une alerte ou un panneau.
         *
         * <h3>Enregistrer a l'avance aggrave le probleme</h3>
         *
         * Micrometer ne cree la serie qu'au premier appel. Une classe qui
         * enregistre ses compteurs dans son constructeur — ce que fait
         * {@code MarketMetrics}, pour pouvoir alerter sur zero — rend la serie
         * VISIBLE a zero. Le panneau affiche alors une ligne plate que rien ne
         * distingue d'une absence d'incident.
         *
         * <h3>Toutes les methodes publiques, et pas seulement recordXxx</h3>
         *
         * Une classe de metriques n'a pas d'autre raison d'exposer une methode
         * publique que d'alimenter une serie. Imposer un prefixe de nom ferait
         * dependre le garde-fou d'une convention, et c'est precisement la
         * convention qui a change d'un module a l'autre.
         */
        @Test
        @DisplayName("aucune methode d'aucune classe de metriques n'est orpheline")
        void aucuneMetriqueDefinieNEstOrpheline() throws IOException {
            List<Path> classes = classesDeMetriques();
            assertThat(classes)
                    .withFailMessage("aucune classe de metriques trouvee : ce garde-fou "
                            + "serait vert sans rien verifier")
                    .hasSizeGreaterThanOrEqualTo(PLANCHER_CLASSES_DE_METRIQUES);

            // Les methodes publiques, hors constructeur. Une methode publique
            // d'une classe de metriques n'a qu'un usage : alimenter une serie.
            Pattern methode = Pattern.compile(
                    "public\\s+(?!class|record|enum|static\\s+final)"
                    + "[\\w<>,.\\[\\]\\s]+?\\s(\\w+)\\s*\\(");

            Map<String, Set<String>> orphelinesParClasse = new TreeMap<>();
            int examinees = 0;

            for (Path source : classes) {
                String nomDeClasse = source.getFileName().toString()
                        .replace(".java", "");
                String code = Files.readString(source);

                Set<String> definies = new TreeSet<>();
                Matcher m = methode.matcher(code);
                while (m.find()) {
                    String nom = m.group(1);
                    // Le constructeur porte le nom de la classe.
                    if (nom.equals(nomDeClasse)) {
                        continue;
                    }
                    // Une methode de CYCLE DE VIE est appelee par le cadre
                    // d'execution, pas par du code du depot. L'exiger appelee
                    // ferait un faux positif sur @PostConstruct — mesure sur
                    // MetriquesDeSecurite.init, qui enregistre ses jauges au
                    // demarrage et que personne n'appelle a la main.
                    if (appeleeParLeCadre(code, m.start())) {
                        continue;
                    }
                    definies.add(nom);
                }
                examinees += definies.size();

                // Le module qui porte cette classe, et lui seul : une metrique
                // s'alimente depuis le detenteur de la regle metier.
                Path module = source;
                while (module != null && !Files.exists(module.resolve("pom.xml"))) {
                    module = module.getParent();
                }
                if (module == null) {
                    continue;
                }
                String sourcesDuModule = concatenerSources(module.resolve("src/main/java"));

                Set<String> orphelines = new TreeSet<>();
                for (String nom : definies) {
                    // On compte les appels VENUS D'AILLEURS : la definition
                    // elle-meme ne compte pas, d'ou le retrait du fichier.
                    String ailleurs = sourcesDuModule.replace(code, "");
                    if (!ailleurs.contains("." + nom + "(")) {
                        orphelines.add(nom);
                    }
                }
                if (!orphelines.isEmpty()) {
                    orphelinesParClasse.put(nomDeClasse, orphelines);
                }
            }

            assertThat(examinees)
                    .withFailMessage("%d methodes extraites : l'analyse est cassee",
                            examinees)
                    .isGreaterThanOrEqualTo(PLANCHER_METHODES_DE_METRIQUES);

            assertThat(orphelinesParClasse)
                    .withFailMessage("""
                            Ces metriques sont DEFINIES et jamais alimentees :

                            %s

                            Micrometer ne cree la serie qu'au premier appel — et quand \
                            la classe l'enregistre a l'avance, la serie existe a ZERO, \
                            ce qui est pire : le panneau affiche une ligne plate que \
                            rien ne distingue d'une absence d'incident, et l'alerte ne \
                            se declenche jamais.

                            Mesure du 2026-11-13 : trois des cinq metriques de \
                            MarketMetrics etaient dans ce cas — horsPortee, \
                            photosNonSignees et dureeDuCatalogue — toutes trois visees \
                            par une alerte ou un panneau.

                            Soit l'appeler depuis le detenteur de la regle, soit la \
                            retirer, soit retirer le panneau et l'alerte qui la visent.""",
                            orphelinesParClasse)
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

    @Nested
    @DisplayName("Chaque centile interroge a bien des buckets")
    class CentilesEtBuckets {

        /**
         * <h2>Un nom qui existe ne dit pas que la serie existe</h2>
         *
         * Les deux tests ci-dessus verifient qu'une metrique INTERROGEE est
         * emise quelque part. Pour y arriver ils normalisent le suffixe :
         * {@code market_catalogue_duree_seconds_bucket} devient
         * {@code market_catalogue_duree}, qui est bien declare. Le fantome
         * n'en est donc pas un — et pourtant la serie interrogee n'existe pas.
         *
         * <p>Un {@code Timer} Micrometer publie {@code _count}, {@code _sum} et
         * {@code _max}. Il ne publie de {@code _bucket} que si on le lui
         * demande, par {@code publishPercentileHistogram()} dans le code ou par
         * {@code management.metrics.distribution.percentiles-histogram} dans la
         * configuration. Sans cela, {@code histogram_quantile} travaille sur un
         * ensemble vide.
         *
         * <h2>Le panneau se voit, l'alerte ne se voit pas</h2>
         *
         * Un panneau sans buckets affiche « No data » : quelqu'un finira par le
         * remarquer. Une ALERTE sans buckets ne passe jamais a {@code firing},
         * parce qu'une expression qui ne rend aucune serie ne declenche rien.
         * Elle reste verte, pour toujours, et sa verdeur se lit comme une
         * absence d'incident.
         *
         * <p>Mesure du 2026-11-13 : le panneau « Rendu d'une page de catalogue
         * (centiles) » et l'alerte {@code CatalogueRalenti} portaient tous deux
         * sur {@code market_catalogue_duree_seconds_bucket}. Le chronometre
         * etait enregistre sans histogramme, a deux endroits de la meme classe
         * — et Micrometer, qui rend le meter deja enregistre, ignorait la
         * configuration du second.
         */
        @Test
        @DisplayName("toute metrique interrogee en _bucket est declaree avec un histogramme")
        void toutBucketInterrogeExiste() throws IOException {
            // Lecture du texte BRUT, et non du resultat de metriquesDe(...) :
            // cette extraction-la normalise les suffixes, donc elle a deja
            // transforme `..._seconds_bucket` en `...` et perdu precisement ce
            // que ce test cherche.
            Map<String, List<String>> interrogees = bucketsInterroges();

            assertThat(interrogees)
                    .withFailMessage("""
                            Aucune expression du depot n'interroge de _bucket.

                            Ce test ne prouverait plus rien. Soit les centiles ont                             disparu des tableaux de bord et des alertes, soit                             l'extraction des metriques ne les reconnait plus.""")
                    .isNotEmpty();

            String sourcesDuDepot = sourcesDeMetriques();
            String configurationDuDepot = configurationDObservabilite();

            Map<String, List<String>> sansHistogramme = new TreeMap<>();
            for (Map.Entry<String, List<String>> entree : interrogees.entrySet()) {
                if (!publieUnHistogramme(entree.getKey(), sourcesDuDepot, configurationDuDepot)) {
                    sansHistogramme.put(entree.getKey(), entree.getValue());
                }
            }

            assertThat(sansHistogramme)
                    .withFailMessage("""
                            Ces metriques sont interrogees en _bucket et n'en publient AUCUN :

                            %s

                            Un Timer Micrometer publie _count, _sum et _max. Les buckets                             demandent publishPercentileHistogram() a la declaration, ou la                             clef management.metrics.distribution.percentiles-histogram en                             configuration.

                            Le panneau affichera « No data ». L'alerte, elle, ne se                             declenchera JAMAIS : une expression qui ne rend aucune serie ne                             passe pas a firing, et son vert permanent se lit comme une                             absence d'incident.

                            Soit declarer l'histogramme, soit remplacer le centile par une                             expression que la metrique peut rendre — par exemple                             _sum / _count pour une moyenne.""".formatted(
                            rendreLisible(sansHistogramme)))
                    .isEmpty();
        }

        /**
         * Cette metrique Prometheus est-elle declaree avec un histogramme ?
         *
         * <h3>La normalisation va dans UN SEUL sens</h3>
         *
         * Micrometer convertit le nom Java en nom Prometheus en remplacant les
         * points par des soulignes. L'operation n'est pas reversible : un nom
         * Java peut contenir des soulignes. Reconstruire le litteral en
         * remplacant les soulignes par des points donne
         * {@code user.service.users.creation.duration} la ou le code ecrit
         * {@code user_service.users.creation.duration} — et le garde-fou
         * accuse une metrique parfaitement declaree.
         *
         * <p>C'est l'erreur qu'a faite la premiere version de ce test, le jour
         * meme ou il a ete ecrit. On normalise donc dans le sens qui ne perd
         * rien : chaque litteral des sources est converti en nom Prometheus,
         * puis compare.
         */
        private boolean publieUnHistogramme(String racine, String sources, String configuration) {
            // La CLEF, et non une sous-chaine : « r2dbc.query » apparait dans
            // une phrase sans y etre un reglage. On exige donc le nom suivi de
            // deux-points et d'une valeur vraie.
            for (String forme : List.of(racine, racine.replace('_', '.'))) {
                if (Pattern.compile("(?m)^\\s*\"?\\[?" + Pattern.quote(forme)
                        + "\\]?\"?\\s*:\\s*(?:true|yes)\\s*$")
                        .matcher(configuration).find()) {
                    return true;
                }
            }

            Matcher litteraux = Pattern.compile("\"([a-z][a-zA-Z0-9_.]{3,})\"").matcher(sources);
            while (litteraux.find()) {
                if (!enNomPrometheus(litteraux.group(1)).equals(racine)) {
                    continue;
                }
                if (histogrammeDansLInstruction(sources, litteraux.start())) {
                    return true;
                }
                // Le litteral peut n'etre que la valeur d'une constante, et la
                // declaration vivre ailleurs : on suit la constante.
                for (String constante : constantesPortant(litteraux.group(1), sources)) {
                    int usage = sources.indexOf(constante);
                    while (usage >= 0) {
                        if (histogrammeDansLInstruction(sources, usage)) {
                            return true;
                        }
                        usage = sources.indexOf(constante, usage + 1);
                    }
                }
            }
            return false;
        }

        /** La conversion que fait Micrometer : les points deviennent des soulignes. */
        private String enNomPrometheus(String litteralJava) {
            return litteralJava.replace('.', '_');
        }

        /** Les constantes Java dont la valeur est ce litteral. */
        private Set<String> constantesPortant(String litteral, String sources) {
            Set<String> noms = new LinkedHashSet<>();
            Matcher m = Pattern.compile(
                    "\\b([A-Z][A-Z0-9_]{2,})\\s*=\\s*\"" + Pattern.quote(litteral) + "\"")
                    .matcher(sources);
            while (m.find()) {
                noms.add(m.group(1));
            }
            return noms;
        }

        /**
         * L'instruction qui contient cette position demande-t-elle un
         * histogramme ?
         *
         * <p>Bornee a l'instruction — d'un point-virgule au suivant — et non au
         * fichier : une classe qui declare dix metriques dont une seule porte
         * un histogramme ne doit pas les faire toutes passer.
         */
        private boolean histogrammeDansLInstruction(String sources, int position) {
            int fin = sources.indexOf(';', position);
            if (fin < 0) {
                fin = sources.length();
            }
            int debut = sources.lastIndexOf(';', position);
            String instruction = sources.substring(Math.max(debut, 0), fin);
            return instruction.contains("publishPercentileHistogram")
                    || instruction.contains("serviceLevelObjectives")
                    || instruction.contains("percentilesHistogram");
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

    /**
     * Le YAML sans ses commentaires.
     *
     * <p>Le {@code #} n'est reconnu qu'en debut de ligne ou precede d'une
     * espace : {@code #} colle a un caractere fait partie d'une valeur, par
     * exemple une couleur hexadecimale dans un tableau de bord.
     */
    private static String sansCommentairesYaml(String yaml) {
        StringBuilder sortie = new StringBuilder(yaml.length());
        for (String ligne : yaml.split("\n", -1)) {
            int diese = -1;
            for (int i = 0; i < ligne.length(); i++) {
                if (ligne.charAt(i) == '#' && (i == 0 || Character.isWhitespace(ligne.charAt(i - 1)))) {
                    diese = i;
                    break;
                }
            }
            sortie.append(diese < 0 ? ligne : ligne.substring(0, diese)).append('\n');
        }
        return sortie.toString();
    }

    /**
     * Les metriques interrogees en {@code _bucket}, par fichier.
     *
     * <p>Lit le TEXTE des regles et des tableaux de bord, sans passer par
     * {@code metriquesDe(...)} : celle-ci normalise les suffixes pour comparer
     * des racines, ce qui est juste pour son objet et detruit le signal ici.
     *
     * @return racine de la metrique vers les fichiers qui l'interrogent
     */
    private static Map<String, List<String>> bucketsInterroges() throws IOException {
        Pattern bucket = Pattern.compile("\\b([a-z][a-z0-9_]*)_bucket\\b");
        Map<String, List<String>> trouvees = new TreeMap<>();
        Path racine = racineDuDepot();
        for (Path depart : List.of(racine.resolve("observability"))) {
            if (!Files.isDirectory(depart)) {
                continue;
            }
            try (Stream<Path> fichiers = Files.walk(depart)) {
                for (Path p : fichiers
                        .filter(x -> x.toString().endsWith(".yml")
                                || x.toString().endsWith(".yaml")
                                || x.toString().endsWith(".json"))
                        .toList()) {
                    Matcher m = bucket.matcher(Files.readString(p));
                    while (m.find()) {
                        trouvees.computeIfAbsent(racineDeLaMetrique(m.group(1) + "_bucket"),
                                cle -> new ArrayList<>())
                                .add(racine.relativize(p).toString());
                    }
                }
            }
        }
        trouvees.values().forEach(liste -> {
            List<String> uniques = liste.stream().distinct().toList();
            liste.clear();
            liste.addAll(uniques);
        });
        return trouvees;
    }

    /**
     * Toutes les sources de production du depot, concatenees.
     *
     * <p>Et non les seules classes {@code *Metrics} : un chronometre peut etre
     * declare dans le service qui le mesure, et le restreindre aux classes de
     * metriques creerait un angle mort exactement la ou le defaut est le plus
     * probable — une metrique posee a la hate, hors du detenteur habituel.
     */
    private static String sourcesDeMetriques() throws IOException {
        StringBuilder tout = new StringBuilder();
        try (Stream<Path> fichiers = Files.walk(racineDuDepot())) {
            for (Path p : fichiers
                    .filter(x -> x.toString().contains("/src/main/java/"))
                    .filter(x -> x.toString().endsWith(".java"))
                    .toList()) {
                // Les COMMENTAIRES sont retires. Sans cela, la javadoc qui
                // explique pourquoi une metrique porte un histogramme suffit a
                // faire croire qu'elle en porte un : l'instruction decoupee
                // d'un point-virgule au suivant englobe le bloc de
                // documentation qui la precede.
                //
                // Mesure du 2026-11-13 : la mutation qui retirait
                // publishPercentileHistogram() du code de MarketMetrics
                // laissait ce test VERT, parce que la javadoc juste au-dessus
                // nommait la methode. C'est le defaut que decrit la javadoc de
                // SourcesDuDepot — « un autre qui trouvait sa correspondance
                // dans sa propre javadoc ».
                tout.append(SourcesDuDepot.sansCommentairesJava(Files.readString(p)))
                        .append('\n');
            }
        }
        return tout.toString();
    }

    /**
     * Toute la configuration d'observabilite du depot, concatenee.
     *
     * <p>Un histogramme peut etre demande sans toucher au code, par
     * {@code management.metrics.distribution.percentiles-histogram.<nom>}.
     * Ignorer cette voie ferait echouer le garde-fou sur une metrique
     * parfaitement configuree.
     */
    private static String configurationDObservabilite() throws IOException {
        StringBuilder tout = new StringBuilder();
        Path racine = racineDuDepot();
        for (Path depart : List.of(racine.resolve("config-repo"), racine)) {
            if (!Files.isDirectory(depart)) {
                continue;
            }
            try (Stream<Path> fichiers = Files.walk(depart, depart.equals(racine) ? 3 : 10)) {
                for (Path p : fichiers
                        .filter(x -> !x.toString().contains("/target/"))
                        .filter(x -> x.toString().endsWith(".yml")
                                || x.toString().endsWith(".yaml")
                                || x.toString().endsWith(".properties"))
                        .toList()) {
                    // Les commentaires YAML partent, pour la meme raison que
                    // ceux du Java : la mutation qui retirait
                    // « r2dbc.query: true » laissait ce test vert, parce que le
                    // commentaire juste au-dessus EXPLIQUAIT pourquoi la clef
                    // etait la, et nommait donc la metrique.
                    String contenu = sansCommentairesYaml(Files.readString(p));
                    if (contenu.contains("percentiles-histogram")
                            || contenu.contains("percentilesHistogram")) {
                        tout.append(contenu).append('\n');
                    }
                }
            }
        }
        return tout.toString();
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
