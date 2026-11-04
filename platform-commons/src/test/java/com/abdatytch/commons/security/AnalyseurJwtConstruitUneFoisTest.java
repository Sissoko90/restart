package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
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
 * Aucun analyseur JWT n'est construit sur le chemin d'une requête.
 *
 * <h2>Le défaut gardé ici</h2>
 *
 * {@code Jwts.parser()...build()} de jjwt fait une recherche
 * {@link java.util.ServiceLoader}, qui <b>lit des JAR sur le disque</b> :
 *
 * <pre>
 * BlockingOperationError: Blocking call! java.io.RandomAccessFile#readBytes
 *   at java.util.ServiceLoader$LazyClassPathLookupIterator.parse
 *   at io.jsonwebtoken.impl.lang.Services.loadFirst
 *   at io.jsonwebtoken.impl.DefaultJwtParserBuilder.build
 * </pre>
 *
 * Le construire par requête met donc un appel bloquant sur le chemin de chaque
 * requête authentifiée. Il l'était dans <b>huit</b> composants : la passerelle,
 * auth (deux variantes), interface, user, media, security, setting et quotas.
 *
 * <h2>Pourquoi une garde STRUCTURELLE, et non un test BlockHound</h2>
 *
 * Parce qu'un test BlockHound <b>ne voit pas ce défaut</b>. Mesuré, pas supposé :
 * un {@code NoBlockingCallsTest} écrit pour ce chemin, avec un contrôle négatif
 * fonctionnel, passe aussi bien avec la version fautive qu'avec la version
 * corrigée.
 *
 * <p>La raison tient au cache de jjwt : {@code Services} retient le résultat de
 * sa recherche pour toute la <b>JVM</b>. Le premier appel paie la lecture
 * disque, les suivants non. Or n'importe quel {@code Jwts.builder()} — y compris
 * celui qui fabrique le jeton d'essai, sur le fil principal — réchauffe ce cache
 * avant que la requête ne touche un fil réactif.
 *
 * <p>C'est exactement ce qui a rendu le défaut invisible pendant des mois : il
 * n'apparaissait que si l'ordre des classes de test laissait le cache froid
 * jusqu'au premier appel réactif. Il a fallu l'intégration continue, sur un
 * autre système de fichiers donc un autre ordre, pour qu'il se montre.
 *
 * <p>Un invariant qu'on ne peut pas observer de façon fiable à l'exécution se
 * garde donc sur la <b>forme du code</b>. C'est le même choix que
 * {@code NomsDeBeansTest}, qui lit les octets des classes compilées plutôt que
 * de démarrer un contexte.
 */
@DisplayName("Aucun analyseur JWT construit par requete")
class AnalyseurJwtConstruitUneFoisTest {

    /** L'appel surveillé. */
    private static final Pattern CONSTRUCTION = Pattern.compile("Jwts\\s*\\.\\s*parser\\s*\\(");

    /**
     * Déclaration de méthode ou de constructeur.
     *
     * <p>Le nom est capturé pour décider si l'occurrence est légitime. Le motif
     * est volontairement large — modificateurs libres, type de retour libre —
     * parce qu'un motif trop strict manquerait une déclaration et ferait
     * remonter l'occurrence à la méthode précédente, donc produirait un verdict
     * faux.
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s{1,8}(?:(?:public|protected|private|static|final|synchronized|abstract|default)\\s+)*"
                    + "(?:[\\w.<>\\[\\],?\\s]+\\s+)?([A-Za-z_]\\w*)\\s*\\([^;]*$");

    /**
     * Méthodes où construire un analyseur est légitime.
     *
     * <p>Les initialisations s'exécutent une fois, au démarrage, sur le fil qui
     * monte le contexte — jamais sur une EventLoop. {@code JwtWebFilter} y
     * figure parce que la passerelle construit son analyseur dans son
     * <b>constructeur</b> : l'émetteur y arrive en argument, rien n'oblige à
     * attendre le conteneur, et {@code SecurityConfig} comme son test
     * l'instancient par {@code new}.
     */
    private static final Set<String> INITIALISATIONS = Set.of(
            "construireLAnalyseur",
            "construireLesAnalyseurs",
            "JwtWebFilter");

    /**
     * Occurrences tolérées, avec leur raison.
     *
     * <p>Une tolérance porte un nom de fichier ET une justification écrite. Sans
     * la justification, une tolérance devient un trou qu'on n'ose plus refermer
     * parce que personne ne sait pourquoi il est là.
     */
    private static final Set<String> TOLERES = Set.of(
            // La clé est résolue PAR APPEL, reactivement, car le JWKS d'Apple
            // peut devoir être rechargé. Un analyseur unique avec verifyWith
            // figerait une clé. Et la lecture disque n'a plus lieu : le cache
            // ServiceLoader de jjwt est déjà chaud, JwtServiceImpl construisant
            // ses analyseurs en @PostConstruct. Le javadoc de la méthode
            // l'explique en détail, dépendance indirecte comprise.
            "ClesPubliquesApple.java");

    /**
     * Plancher du nombre de fichiers examinés.
     *
     * <p>Sans lui, un chemin cassé rendrait ce test vert en n'examinant rien —
     * la forme la plus trompeuse d'un succès. Même garde que dans
     * {@code AucunJustificatifDansLesJournauxTest}.
     */
    private static final int PLANCHER_FICHIERS = 400;

    @Test
    @DisplayName("Jwts.parser() n'apparait que dans une initialisation")
    void analyseurConstruitUneSeuleFois() throws IOException {
        Path racine = racineDuDepot();
        List<String> fautes = new ArrayList<>();
        int examines = 0;

        try (Stream<Path> fichiers = Files.walk(racine)) {
            List<Path> sources = fichiers
                    .filter(Files::isRegularFile)
                    .filter(chemin -> chemin.toString().endsWith(".java"))
                    .filter(chemin -> chemin.toString().contains("/src/main/java/"))
                    .filter(chemin -> !chemin.toString().contains("/target/"))
                    .sorted()
                    .toList();

            for (Path source : sources) {
                examines++;
                fautes.addAll(fautesDans(source));
            }
        }

        assertThat(examines)
                .as("aucune source examinee : le chemin de balayage est casse, "
                        + "et ce test serait vert sans rien verifier")
                .isGreaterThan(PLANCHER_FICHIERS);

        assertThat(fautes)
                .as("""
                        Un analyseur JWT est construit hors d'une initialisation.

                        Jwts.parser().build() fait une recherche ServiceLoader, donc une
                        LECTURE DISQUE. Sur le chemin d'une requete, c'est un appel
                        bloquant sur une EventLoop — et BlockHound ne le verra pas, le
                        cache de jjwt etant global a la JVM et souvent deja chaud.

                        Construire l'analyseur UNE fois :
                          - en @PostConstruct nomme construireLAnalyseur() ou
                            construireLesAnalyseurs(), si les valeurs viennent de @Value ;
                          - dans le constructeur, si elles arrivent en arguments.

                        Passer le localisateur par INDIRECTION — keyLocator(entete ->
                        fournisseur.keyLocator().locate(entete)) — et non resolu a la
                        construction : sinon le contexte echoue quand le fournisseur
                        n'a pas encore ses cles.

                        Si l'occurrence est legitime, l'inscrire dans TOLERES avec sa
                        raison ecrite.""")
                .isEmpty();
    }

    /**
     * @return une faute par occurrence illégitime, décrite avec son emplacement
     */
    private static List<String> fautesDans(Path source) throws IOException {
        String nom = source.getFileName().toString();
        if (TOLERES.contains(nom)) {
            return List.of();
        }

        List<String> lignes = Files.readAllLines(source);
        List<String> fautes = new ArrayList<>();

        for (int rang = 0; rang < lignes.size(); rang++) {
            String ligne = lignes.get(rang);
            // Les commentaires et le javadoc citent abondamment l'appel —
            // plusieurs des corrections s'expliquent en le nommant. Les
            // compter ferait de ce test un refus de documenter.
            if (estDuCommentaire(ligne) || !CONSTRUCTION.matcher(ligne).find()) {
                continue;
            }
            String englobante = methodeEnglobante(lignes, rang);
            if (!INITIALISATIONS.contains(englobante)) {
                fautes.add("%s:%d — dans %s()".formatted(nom, rang + 1, englobante));
            }
        }
        return fautes;
    }

    /**
     * Nom de la méthode ou du constructeur qui contient cette ligne.
     *
     * <p>Remonte jusqu'à la première déclaration rencontrée.
     *
     * @return le nom trouvé, ou {@code "(hors methode)"} si aucune déclaration
     *         ne précède — ce qui signale un motif à corriger plutôt qu'un code
     *         fautif
     */
    private static String methodeEnglobante(List<String> lignes, int rang) {
        for (int remonte = rang; remonte >= 0; remonte--) {
            var trouvee = DECLARATION.matcher(lignes.get(remonte));
            if (trouvee.find()) {
                return trouvee.group(1);
            }
        }
        return "(hors methode)";
    }

    private static boolean estDuCommentaire(String ligne) {
        String propre = ligne.strip();
        return propre.startsWith("//") || propre.startsWith("*") || propre.startsWith("/*");
    }

    private static Path racineDuDepot() {
        Path courant = Path.of("").toAbsolutePath();
        while (courant != null) {
            if (Files.isDirectory(courant.resolve("platform-commons"))
                    && Files.isRegularFile(courant.resolve("pom.xml"))) {
                return courant;
            }
            courant = courant.getParent();
        }
        throw new IllegalStateException(
                "Racine du depot introuvable depuis " + Path.of("").toAbsolutePath());
    }
}
