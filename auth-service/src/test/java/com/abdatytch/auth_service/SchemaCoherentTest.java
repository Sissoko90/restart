package com.abdatytch.auth_service;

import org.junit.jupiter.api.DisplayName;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toute colonne mappee par une entite doit exister dans les migrations.
 *
 * <h2>Le defaut que ce garde-fou attrape</h2>
 *
 * R2DBC est desactive dans le profil de test, et les depots sont remplaces par
 * des {@code @MockitoBean}. Consequence : <b>aucun test ne touche le schema</b>.
 * Une entite peut declarer une colonne absente de la base sans qu'une seule
 * assertion s'en apercoive, et l'echec n'apparait qu'au premier demarrage contre
 * un vrai PostgreSQL — sous la forme d'un {@code save()} qui echoue sur
 * « column ... does not exist ».
 *
 * <p>C'est arrive deux fois de suite, pour la meme raison : les entites
 * {@code AppareilDeConfiance} et {@code CodeDeSecours} heritent de
 * {@link com.abdatytch.auth_service.entities.BaseEntity}, Spring Data R2DBC
 * inclut les proprietes <b>heritees</b> dans l'{@code INSERT}, et les deux
 * tables que j'avais ecrites n'avaient ni {@code created_at} ni
 * {@code updated_at}. Les huit tables anterieures les avaient toutes : la
 * convention etait respectee partout sauf la ou personne ne regardait.
 *
 * <h2>Pourquoi un test de source, et non un Testcontainer</h2>
 *
 * Un Testcontainer repondrait a la question, mais seulement quand Docker tourne
 * — et le seul test de ce depot qui en demande un est deja marque « skipped »
 * dans la suite. Un garde-fou qu'on saute n'en est pas un.
 *
 * <p>La question posee ici est de toute facon structurelle : l'ensemble des
 * colonnes mappees est-il inclus dans l'ensemble des colonnes creees ? Les deux
 * ensembles sont lisibles dans les sources.
 */
@DisplayName("Le schema couvre toutes les colonnes mappees")
class SchemaCoherentTest {

    private static final Path PAQUET_ENTITES =
            Path.of("src/main/java/com/abdatytch/auth_service/entities");

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /**
     * Colonnes du socle commun, mappees par {@code BaseEntity}.
     *
     * <p>Elles n'apparaissent dans aucune entite concrete, et c'est precisement
     * ce qui les rend faciles a oublier en ecrivant une table.
     */
    private static final Set<String> COLONNES_HERITEES =
            Set.of("id", "created_at", "updated_at");

    private static final Pattern DECLARATION_DE_TABLE =
            Pattern.compile("@Table\\(\\s*\"([a-z0-9_]+)\"\\s*\\)");

    private static final Pattern DECLARATION_DE_COLONNE =
            Pattern.compile("@Column\\(\\s*\"([a-z0-9_]+)\"\\s*\\)");

    private static final Pattern CREATION_DE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z0-9_]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AJOUT_DE_COLONNE = Pattern.compile(
            "ALTER\\s+TABLE\\s+([a-z0-9_]+)\\s+ADD\\s+COLUMN\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z0-9_]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Renommage de colonne.
     *
     * <p>Cette garde ne le connaissait pas, et c'est elle qui l'a signale : la
     * migration {@code V8} renomme {@code refresh_tokens.token} en
     * {@code empreinte}, et le test a declare la colonne mappee « absente du
     * schema » — alors qu'elle existait. Un modele de schema qui ignore le
     * renommage se trompe dans les <b>deux</b> sens : il manque la colonne
     * nouvelle, et il continue de croire a l'ancienne. La seconde erreur est la
     * plus grave : une entite laissee sur l'ancien nom aurait passe la garde et
     * echoue au premier demarrage.
     */
    private static final Pattern RENOMMAGE_DE_COLONNE = Pattern.compile(
            "ALTER\\s+TABLE\\s+([a-z0-9_]+)\\s+RENAME\\s+COLUMN\\s+([a-z0-9_]+)"
                    + "\\s+TO\\s+([a-z0-9_]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Suppression de colonne.
     *
     * <p>Ajoutee avec le renommage, pour la meme raison : sans elle, une colonne
     * supprimee resterait dans le modele, et une entite qui la mappe encore
     * passerait la garde pour echouer au demarrage.
     */
    private static final Pattern SUPPRESSION_DE_COLONNE = Pattern.compile(
            "ALTER\\s+TABLE\\s+([a-z0-9_]+)\\s+DROP\\s+COLUMN\\s+"
                    + "(?:IF\\s+EXISTS\\s+)?([a-z0-9_]+)",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("chaque colonne mappee existe dans une migration")
    void chaqueColonneMappeeExiste() throws IOException {
        Map<String, Set<String>> schema = colonnesDuSchema();
        Map<String, Set<String>> mappees = colonnesMappees();

        // Le test doit echouer s'il ne lit rien : un chemin casse rendrait deux
        // ensembles vides, et « tout est inclus » serait vrai sans rien
        // verifier. Le defaut exact qu'un garde-fou doit s'interdire.
        assertThat(schema)
                .as("tables lues dans les migrations")
                .isNotEmpty();
        assertThat(mappees)
                .as("entites lues dans le paquet entities")
                .hasSizeGreaterThan(5);

        List<String> manquantes = new ArrayList<>();
        mappees.forEach((table, colonnes) -> {
            Set<String> creees = schema.get(table);
            if (creees == null) {
                manquantes.add("table absente des migrations : " + table);
                return;
            }
            colonnes.stream()
                    .filter(colonne -> !creees.contains(colonne))
                    .map(colonne -> table + "." + colonne)
                    .forEach(manquantes::add);
        });

        assertThat(manquantes)
                .as("colonnes mappees par une entite et absentes du schema. "
                        + "Spring Data R2DBC les inclut dans l'INSERT : l'ecriture "
                        + "echouera sur « column does not exist » au premier "
                        + "demarrage contre une vraie base")
                .isEmpty();
    }

    @Test
    @DisplayName("les colonnes du socle commun sont dans chaque table d'entite")
    void lesColonnesHeriteesSontPartout() throws IOException {
        Map<String, Set<String>> schema = colonnesDuSchema();
        Map<String, Set<String>> mappees = colonnesMappees();

        assertThat(mappees).as("entites lues").isNotEmpty();

        List<String> incompletes = new ArrayList<>();
        mappees.keySet().forEach(table -> {
            Set<String> creees = schema.getOrDefault(table, Set.of());
            COLONNES_HERITEES.stream()
                    .filter(colonne -> !creees.contains(colonne))
                    .map(colonne -> table + " n'a pas " + colonne)
                    .forEach(incompletes::add);
        });

        assertThat(incompletes)
                .as("toute entite heritant de BaseEntity ecrit id, created_at et "
                        + "updated_at, meme si aucune de ses annotations ne les "
                        + "nomme : elles sont declarees dans la classe mere")
                .isEmpty();
    }

    /** Colonnes creees par les migrations, par table. */
    private static Map<String, Set<String>> colonnesDuSchema() throws IOException {
        Map<String, Set<String>> parTable = new LinkedHashMap<>();
        try (Stream<Path> fichiers = Files.list(MIGRATIONS)) {
            List<Path> sql = fichiers
                    .filter(f -> f.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
            for (Path fichier : sql) {
                String contenu = sansCommentaires(Files.readString(fichier));
                // L'ordre reproduit celui dans lequel Flyway applique les
                // fichiers — ils sont tries plus haut. Une colonne renommee par
                // V8 doit donc disparaitre du modele apres avoir ete creee par
                // V1, et non l'inverse.
                //
                // Limite assumee : a l'INTERIEUR d'un meme fichier, les quatre
                // formes sont lues l'une apres l'autre et non dans leur ordre
                // d'ecriture. Un fichier qui renommerait une colonne puis en
                // creerait une du meme nom serait mal lu. Aucune migration ne
                // fait cela, et l'alternative — un analyseur SQL sequentiel —
                // couterait bien plus que ce qu'elle protege.
                lireLesCreations(contenu, parTable);
                lireLesAjouts(contenu, parTable);
                lireLesRenommages(contenu, parTable);
                lireLesSuppressions(contenu, parTable);
            }
        }
        return parTable;
    }

    private static void lireLesCreations(String sql, Map<String, Set<String>> parTable) {
        Matcher creation = CREATION_DE_TABLE.matcher(sql);
        while (creation.find()) {
            String table = creation.group(1).toLowerCase();
            Set<String> colonnes =
                    parTable.computeIfAbsent(table, cle -> new LinkedHashSet<>());
            corpsDeLaTable(sql, creation.end() - 1).lines()
                    .map(SchemaCoherentTest::nomDeColonne)
                    .filter(nom -> nom != null)
                    .forEach(colonnes::add);
        }
    }

    private static void lireLesAjouts(String sql, Map<String, Set<String>> parTable) {
        Matcher ajout = AJOUT_DE_COLONNE.matcher(sql);
        while (ajout.find()) {
            parTable.computeIfAbsent(ajout.group(1).toLowerCase(),
                            cle -> new LinkedHashSet<>())
                    .add(ajout.group(2).toLowerCase());
        }
    }

    /**
     * Applique les renommages : l'ancien nom sort du modele, le nouveau y entre.
     *
     * <p>Retirer l'ancien est la moitie qui compte. Sans elle, une entite restee
     * sur {@code token} apres le renommage en {@code empreinte} passerait cette
     * garde, et l'ecriture echouerait sur « column does not exist » au premier
     * demarrage contre une vraie base — exactement ce que ce test existe pour
     * eviter.
     */
    private static void lireLesRenommages(String sql, Map<String, Set<String>> parTable) {
        Matcher renommage = RENOMMAGE_DE_COLONNE.matcher(sql);
        while (renommage.find()) {
            Set<String> colonnes = parTable.computeIfAbsent(
                    renommage.group(1).toLowerCase(), cle -> new LinkedHashSet<>());
            colonnes.remove(renommage.group(2).toLowerCase());
            colonnes.add(renommage.group(3).toLowerCase());
        }
    }

    private static void lireLesSuppressions(String sql, Map<String, Set<String>> parTable) {
        Matcher suppression = SUPPRESSION_DE_COLONNE.matcher(sql);
        while (suppression.find()) {
            Set<String> colonnes = parTable.get(suppression.group(1).toLowerCase());
            if (colonnes != null) {
                colonnes.remove(suppression.group(2).toLowerCase());
            }
        }
    }

    /**
     * Extrait le corps parenthese d'un {@code CREATE TABLE}.
     *
     * <p>Les parentheses sont comptees, et non cherchees par la premiere
     * fermante : une definition contient {@code VARCHAR(36)} et des contraintes
     * {@code FOREIGN KEY (user_id)}, qui arreteraient une lecture naive au
     * milieu de la table.
     */
    private static String corpsDeLaTable(String sql, int indexOuvrante) {
        int profondeur = 0;
        for (int i = indexOuvrante; i < sql.length(); i++) {
            char caractere = sql.charAt(i);
            if (caractere == '(') {
                profondeur++;
            } else if (caractere == ')') {
                profondeur--;
                if (profondeur == 0) {
                    return sql.substring(indexOuvrante + 1, i);
                }
            }
        }
        return "";
    }

    /** Premier mot d'une ligne de definition, ou {@code null} si ce n'en est pas une. */
    private static String nomDeColonne(String ligne) {
        String propre = ligne.strip();
        if (propre.isEmpty()) {
            return null;
        }
        Matcher mot = Pattern.compile("^([a-z0-9_]+)\\s+\\S").matcher(propre);
        if (!mot.find()) {
            return null;
        }
        String nom = mot.group(1).toLowerCase();
        // CONSTRAINT, PRIMARY, FOREIGN, UNIQUE : des clauses, pas des colonnes.
        return Set.of("constraint", "primary", "foreign", "unique", "check")
                .contains(nom) ? null : nom;
    }

    /**
     * Colonnes mappees par les entites, par table.
     *
     * <p>Les colonnes heritees de {@code BaseEntity} sont ajoutees a chaque
     * entite : aucune annotation ne les nomme dans la classe concrete, et c'est
     * exactement ce qui les rend invisibles a la relecture.
     */
    private static Map<String, Set<String>> colonnesMappees() throws IOException {
        Map<String, Set<String>> parTable = new LinkedHashMap<>();
        try (Stream<Path> fichiers = Files.list(PAQUET_ENTITES)) {
            List<Path> sources = fichiers
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path source : sources) {
                String contenu = Files.readString(source);
                Matcher table = DECLARATION_DE_TABLE.matcher(contenu);
                if (!table.find()) {
                    // BaseEntity n'a pas de @Table : elle n'est adossee a aucune
                    // table, ses colonnes sont ajoutees a celles des filles.
                    continue;
                }
                Set<String> colonnes = new LinkedHashSet<>(COLONNES_HERITEES);
                Matcher colonne = DECLARATION_DE_COLONNE.matcher(contenu);
                while (colonne.find()) {
                    colonnes.add(colonne.group(1).toLowerCase());
                }
                parTable.put(table.group(1).toLowerCase(), colonnes);
            }
        }
        return parTable;
    }

    /**
     * Retire les commentaires SQL, en respectant les chaines.
     *
     * <p>Un retrait naif de {@code --} couperait au milieu d'un
     * {@code COMMENT ON ... IS 'texte -- avec tirets'}. Le piege a deja ete paye
     * une fois, sur un decoupeur de commentaires Java qui prenait un
     * {@code /**} present dans une chaine pour une ouverture de bloc.
     */
    private static String sansCommentaires(String sql) {
        StringBuilder propre = new StringBuilder(sql.length());
        boolean dansUneChaine = false;
        boolean dansUnCommentaire = false;
        for (int i = 0; i < sql.length(); i++) {
            char caractere = sql.charAt(i);
            if (dansUnCommentaire) {
                if (caractere == '\n') {
                    dansUnCommentaire = false;
                    propre.append(caractere);
                }
                continue;
            }
            if (caractere == '\'') {
                dansUneChaine = !dansUneChaine;
            } else if (!dansUneChaine && caractere == '-'
                    && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                dansUnCommentaire = true;
                continue;
            }
            propre.append(caractere);
        }
        return propre.toString();
    }
}
