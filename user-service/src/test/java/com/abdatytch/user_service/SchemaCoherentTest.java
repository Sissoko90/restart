package com.abdatytch.user_service;

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
 * <p>Dans user-service, l'enonce etait plus brutal : il n'y avait <b>aucune
 * migration</b>. Quatre entites, quatre depots, vingt-sept endpoints, et pas une
 * table. `R2dbcConfig` declarait bien un `ConnectionFactoryInitializer`, mais
 * son populateur etait commente — un bean qui existait pour ne rien faire.
 *
 * <p>Ce garde-fou verifie desormais que V1__init_user_schema.sql couvre
 * exactement ce que les entites declarent, colonnes heritees de
 * {@link com.abdatytch.user_service.entities.BaseEntity} comprises : Spring Data
 * R2DBC les inclut dans l'{@code INSERT}, et c'est la variante du defaut qui a
 * coute deux migrations a auth-service.
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
            Path.of("src/main/java/com/abdatytch/user_service/entities");

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
        // Les tables ATTENDUES, et non un simple comptage.
        //
        // Un seuil numerique se contente d'un chemin a demi casse : trois
        // entites lues sur quatre passeraient « plus de deux ». Nommer les
        // tables fait echouer le garde-fou des qu'une entite cesse d'etre lue.
        // `user_roles` ne figure plus ici : son entite a ete supprimee, et
        // `V2__retrait_de_user_roles.sql` supprime la table. C'etait une
        // replique vide d'un etat detenu par auth-service, que rien
        // n'alimentait ni ne lisait.
        assertThat(mappees.keySet())
                .as("tables lues dans le paquet entities")
                .contains("users", "user_profiles", "audit_logs");

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
                lireLesCreations(contenu, parTable);
                lireLesAjouts(contenu, parTable);
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
