package com.abdatytch.commons.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aucune annotation Resilience4j ne doit exister sans l'infrastructure qui la
 * rend active.
 *
 * <h2>Le defaut que ce garde-fou ferme</h2>
 *
 * Trente annotations {@code @CircuitBreaker}, {@code @RateLimiter} et
 * {@code @Retry} vivaient dans auth-service, et <b>aucune ne faisait quoi que
 * ce soit</b>. {@code spring-boot-starter-aop} n'est declare dans aucun
 * {@code pom.xml} du depot et {@code aspectjweaver} est absent de tous les
 * classpaths : sans lui, Spring n'active pas le traitement des beans
 * {@code @Aspect}, donc les aspects de Resilience4j ne sont jamais appliques.
 *
 * <h2>La preuve, par mesure</h2>
 *
 * Relevee sur auth-service en marche, apres deux connexions reussies :
 *
 * <pre>
 * declare par annotation : authService, emailService, smsService,
 *                          verificationCodeService
 * present a l'execution  : externalService, internalService  (declares en YAML)
 * metriques ratelimiter  : AUCUNE
 * </pre>
 *
 * Resilience4j cree les instances <b>configurees</b> au demarrage et les
 * instances <b>annotees</b> a la premiere invocation de l'aspect. Aucune des
 * quatre annotees n'existait : l'aspect n'avait jamais tourne.
 *
 * <h2>Pourquoi les retirer plutot que les activer</h2>
 *
 * Trois defauts independants se cumulaient, chacun silencieux. Les activer
 * n'aurait pas donne de la resilience, mais une <b>illusion de resilience avec
 * les mauvais chiffres</b> — ce qui est pire que rien, parce qu'on cesse de
 * chercher :
 *
 * <ol>
 *   <li><b>les aspects ne tournent pas</b> — {@code aspectjweaver} absent ;</li>
 *   <li><b>le disjoncteur ne pourrait pas s'ouvrir.</b> {@code recordExceptions}
 *       est une liste blanche contenant {@code ResourceAccessException} et
 *       {@code HttpServerErrorException} — des exceptions de
 *       <b>RestTemplate</b>, client bloquant. Cette plateforme est en WebClient,
 *       qui leve {@code WebClientRequestException} et
 *       {@code WebClientResponseException}, absentes de la liste. Avec
 *       {@code recordExceptions} renseigne, tout ce qui n'y figure pas est
 *       compte comme un <b>succes</b> ;</li>
 *   <li><b>les noms ne correspondent pas.</b> Le code annotait
 *       {@code authRateLimiter}, {@code otpRateLimiter},
 *       {@code passwordResetRateLimiter} ; la configuration declare
 *       {@code api}, {@code auth}, {@code otp}. Un nom inconnu retombe
 *       silencieusement sur le defaut, soit 100 requetes par seconde au lieu
 *       des 5 OTP par minute voulus — mille deux cents fois plus permissif que
 *       l'intention ecrite juste a cote.</li>
 * </ol>
 *
 * <p>S'y ajoute que l'annotation est le mauvais vehicule dans une pile
 * reactive : {@code resilience4j-reactor} est absent d'auth-service, de
 * media-service et de user-service, et sans lui un aspect ne peut pas decorer
 * un {@code Mono} — il protegerait l'<b>assemblage</b> du flux, jamais son
 * execution.
 *
 * <h2>Ce qui remplace</h2>
 *
 * <ul>
 *   <li><b>limitation de debit</b> : celle du Gateway, adossee a Redis donc
 *       partagee entre instances, active et testee. Un limiteur en processus
 *       serait un second magasin pour une meme decision ;</li>
 *   <li><b>reessai</b> : <b>rien</b>, et c'est volontaire. Aucun de ces chemins
 *       n'est idempotent — renvoyer un SMS coute de l'argent, un code OAuth2 est
 *       a usage unique — et sur une authentification un reessai gonfle le
 *       compteur de tentatives, donc verrouille des comptes a tort ;</li>
 *   <li><b>disjoncteur</b> : a poser <b>explicitement</b> sur les appels
 *       sortants tiers, par les operateurs Reactor, visibles au point d'appel
 *       et immunises contre « l'aspect ne s'applique pas » ;</li>
 *   <li><b>delai maximal</b> : un {@code .timeout(...)} sur chaque appel
 *       sortant. C'etait le vrai trou — {@code InterfaceServiceClient}, qui
 *       parle a l'autorite JWT, avait trois appels et zero delai.</li>
 * </ul>
 */
@DisplayName("Resilience4j — aucune annotation sans l'infrastructure qui l'active")
class AnnotationsResilienceInertesTest {

    /**
     * Le prefixe qualifie est accepte, et ce n'est pas un detail.
     *
     * <p>Verifie par mutation : la premiere version exigeait le nom juste apres
     * l'arobase. Reintroduire l'annotation sous sa forme pleinement qualifiee —
     * {@code @io.github.resilience4j.retry.annotation.Retry(name = "...")} —
     * <b>survivait</b> au garde-fou, et c'est une forme que les outils de
     * completion produisent naturellement quand l'import manque.
     *
     * <p>Meme leçon que pour {@code HachageHorsEventLoopTest}, ou la detection
     * par nom de champ se contournait en n'utilisant pas le champ : un
     * garde-fou qui ne reconnait qu'une seule ecriture de la chose qu'il
     * interdit n'interdit rien.
     */
    private static final Pattern ANNOTATION = Pattern.compile(
            "@(?:[A-Za-z_][\\w.]*\\.)?(?:CircuitBreaker|RateLimiter|Retry|Bulkhead|TimeLimiter)"
                    + "\\s*\\(");

    /**
     * Dette CONNUE et chiffree : deux fichiers, deux DECISIONS en attente.
     *
     * <p>Les huit autres fichiers annotes ont ete nettoyes. Ces deux-la ne le
     * sont pas, parce que leur repli n'est <b>pas</b> du code mort : c'est un
     * mode degrade delibere, et l'activer ou le retirer change le comportement
     * du produit. Ce n'est pas a un nettoyage de trancher.
     *
     * <h3>media-service / StorageServiceImpl</h3>
     *
     * Ses replis ecrivent le fichier dans un <b>stockage local chiffre</b> quand
     * MinIO est indisponible, et le relisent de la. C'est un mode degrade
     * complet et fonctionnel — qui ne tourne jamais. Aujourd'hui, une panne de
     * MinIO fait donc <b>echouer</b> les televersements, alors que le code
     * contient de quoi continuer a servir.
     *
     * <p>Mais l'activer a une consequence a peser : un fichier ecrit localement
     * sur une instance est <b>invisible des autres</b>. En deploiement
     * horizontal, un televersement reussi sur l'instance A serait introuvable
     * depuis l'instance B — une incoherence silencieuse, pire qu'un echec
     * franc.
     *
     * <h3>interface-service / QuotasServiceClient</h3>
     *
     * Son repli {@code allowOnFailure} rend {@code Mono.just(true)} : il
     * <b>autorise</b> la creation d'un projet quand quotas-service est
     * injoignable, et la javadoc l'annonce comme une garantie
     * (« true egalement si le service est injoignable »).
     *
     * <p>L'annotation etant inerte, le comportement reel est l'<b>inverse</b> :
     * fermeture. Le contrat documente et le comportement effectif se
     * contredisent donc, et la contradiction est invisible a la lecture.
     *
     * <p>Choisir entre les deux est une decision commerciale autant que
     * technique : ouvert, une panne de quotas n'empeche pas les clients de
     * travailler, mais rien ne borne les creations pendant la panne ; ferme, un
     * incident chez quotas bloque des clients payants.
     *
     * <p>Liste <b>fermee</b> : toute annotation nouvelle fait echouer ce test.
     * Elle ne doit que diminuer.
     */
    private static final Set<String> DETTE_CONNUE = Set.of(
            "media-service/StorageServiceImpl.java",
            "interface-service/QuotasServiceClient.java");

    /**
     * Plancher de detection.
     *
     * <p>Si le balayage cesse de trouver des sources, il rendrait zero fichier
     * et le test passerait sans rien verifier. Ce depot s'est deja fait prendre
     * plusieurs fois par un harnais vert qui ne mesurait plus rien.
     */
    private static final int PLANCHER_FICHIERS_EXAMINES = 300;

    // ==================================================================
    @Nested
    @DisplayName("Aucune annotation nouvelle")
    class AucuneAnnotationNouvelle {

        @Test
        @DisplayName("aucune annotation Resilience4j hors de la dette connue")
        void aucuneAnnotationHorsDette() throws IOException {
            List<Path> sources = sourcesDeProduction();

            assertThat(sources)
                    .withFailMessage("aucune source balayee : ce garde-fou serait vert "
                            + "sans rien verifier")
                    .hasSizeGreaterThanOrEqualTo(PLANCHER_FICHIERS_EXAMINES);

            List<String> trouvees = new ArrayList<>();
            for (Path source : sources) {
                if (!ANNOTATION.matcher(Files.readString(source)).find()) {
                    continue;
                }
                String cle = cle(source);
                if (!DETTE_CONNUE.contains(cle)) {
                    trouvees.add(cle);
                }
            }

            assertThat(trouvees)
                    .withFailMessage("""
                            Ces fichiers portent une annotation Resilience4j : %s

                            Elle NE FERA RIEN. spring-boot-starter-aop n'est declare dans \
                            aucun pom.xml du depot, donc aspectjweaver est absent, donc \
                            Spring n'applique pas les beans @Aspect.

                            Mesure sur auth-service en marche : les quatre disjoncteurs \
                            nommes par annotation n'existaient pas a l'execution, et \
                            aucune metrique de limiteur de debit n'etait exposee.

                            Pour de la resilience qui fonctionne :
                              - limitation de debit -> celle du Gateway, adossee a Redis ;
                              - reessai -> aucun, ces chemins ne sont pas idempotents ;
                              - disjoncteur -> operateurs Reactor explicites sur les \
                            appels sortants ;
                              - delai maximal -> .timeout(...) sur chaque appel sortant.

                            Voir la javadoc de cette classe pour les trois defauts qui \
                            se cumulaient.""", trouvees)
                    .isEmpty();
        }

        @Test
        @DisplayName("la dette connue ne contient que des fichiers qui existent encore")
        void detteSansEntreeMorte() throws IOException {
            // Une entree qui ne correspond plus a rien laisserait croire que la
            // dette est plus lourde qu'elle ne l'est — et masquerait une
            // reapparition sous le meme nom.
            List<String> presentes = new ArrayList<>();
            for (Path source : sourcesDeProduction()) {
                if (ANNOTATION.matcher(Files.readString(source)).find()) {
                    presentes.add(cle(source));
                }
            }

            List<String> mortes = DETTE_CONNUE.stream()
                    .filter(entree -> !presentes.contains(entree))
                    .sorted()
                    .toList();

            assertThat(mortes)
                    .withFailMessage("""
                            Ces entrees de DETTE_CONNUE ne portent plus d'annotation : %s

                            Les retirer de la liste. Elle ne doit que diminuer, et une \
                            entree morte masquerait une reapparition sous le meme nom.""",
                            mortes)
                    .isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'infrastructure reste absente")
    class InfrastructureAbsente {

        /**
         * Si quelqu'un ajoute l'AOP, ce test echoue — et c'est voulu.
         *
         * <p>Activer l'AOP rendrait d'un coup actives les dix annotations de la
         * dette connue, avec leurs noms d'instance qui ne correspondent a rien
         * et leurs reessais sur des operations non idempotentes. L'echec force a
         * traiter la dette <b>avant</b>, et non a decouvrir le resultat en
         * production.
         */
        @Test
        @DisplayName("ajouter spring-boot-starter-aop exige d'avoir purge la dette d'abord")
        void aopAbsenteTantQueLaDetteExiste() throws IOException {
            Path racine = racineDuDepot();
            List<String> pomsAvecAop = new ArrayList<>();

            try (Stream<Path> poms = Files.walk(racine, 3)) {
                for (Path pom : poms.filter(p -> p.getFileName().toString().equals("pom.xml"))
                        .filter(p -> !p.toString().contains("/target/")).toList()) {
                    // Les COMMENTAIRES XML sont retires avant le controle.
                    //
                    // Sans cela, ce garde-fou se declenchait sur sa propre
                    // explication : le commentaire qui justifie le retrait de
                    // resilience4j-spring-boot3 dans quatre POM nomme
                    // forcement spring-boot-starter-aop et aspectjweaver, pour
                    // dire pourquoi les annotations restent inertes.
                    //
                    // Il signalait donc quatre modules « avec AOP » alors
                    // qu'aucune dependance n'avait ete ajoutee. Un garde-fou qui
                    // interdit d'ecrire ce qu'il garde finit par etre desactive,
                    // et il emporte avec lui la protection qu'il portait.
                    String contenu = sansCommentairesXml(Files.readString(pom));
                    if (contenu.contains("spring-boot-starter-aop")
                            || contenu.contains("aspectjweaver")) {
                        pomsAvecAop.add(racine.relativize(pom).toString());
                    }
                }
            }

            assertThat(pomsAvecAop)
                    .withFailMessage("""
                            L'AOP est desormais declaree ici : %s

                            Cela rend ACTIVES les %d annotations encore listees dans \
                            DETTE_CONNUE — avec des noms d'instance qui ne correspondent a \
                            aucune configuration (donc les limites par defaut) et des \
                            reessais sur des operations non idempotentes.

                            Purger DETTE_CONNUE d'abord, puis retirer ce test.""",
                            pomsAvecAop, DETTE_CONNUE.size())
                    .isEmpty();
        }

        /**
         * Le POM sans ses commentaires.
         *
         * <p>Le contrôle porte sur ce que Maven <b>lit</b>, pas sur ce que le
         * fichier raconte. Un commentaire qui explique pourquoi l'AOP est
         * absente ne déclare pas l'AOP.
         */
        private String sansCommentairesXml(String pom) {
            return pom.replaceAll("(?s)<!--.*?-->", "");
        }
    }

    // ==================================================================

    /** {@code module/NomDeFichier.java}, stable quelle que soit l'arborescence. */
    private static String cle(Path source) {
        Path racine = racineDuDepot();
        String module = racine.relativize(source).getName(0).toString();
        return module + "/" + source.getFileName();
    }

    private static List<Path> sourcesDeProduction() throws IOException {
        try (Stream<Path> fichiers = Files.walk(racineDuDepot())) {
            return fichiers
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
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
