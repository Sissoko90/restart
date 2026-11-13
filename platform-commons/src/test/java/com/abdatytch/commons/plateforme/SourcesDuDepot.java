package com.abdatytch.commons.plateforme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Le balayage du depot, pour les garde-fous qui lisent les sources.
 *
 * <h2>Pourquoi ces methodes sont ici et non recopiees</h2>
 *
 * Plusieurs garde-fous de ce module lisent les memes choses : la racine, la
 * liste des modules, et surtout le depouillement des commentaires. Recopier ce
 * dernier dans chaque test ferait diverger la partie la plus subtile du
 * dispositif — celle qui decide ce qu'un garde-fou voit.
 *
 * <h2>LIRE LA STRUCTURE, PAS LE TEXTE</h2>
 *
 * Ce depot a paye cinq fois la lecture naive d'un fichier source : un
 * commentaire de POM qui contenait l'artefact exige, un test qui comptait des
 * routes YAML sans voir leur indentation, un autre qui trouvait sa
 * correspondance dans sa propre javadoc. Un garde-fou qui cherche une chaine
 * est satisfait par une declaration inerte, par un commentaire, ou par sa
 * propre documentation.
 */
public final class SourcesDuDepot {

    private SourcesDuDepot() {
    }

    /** Les modules Maven du depot. */
    static List<Path> modules() throws IOException {
        try (Stream<Path> contenu = Files.list(racineDuDepot())) {
            return contenu.filter(Files::isDirectory)
                    .filter(dossier -> Files.exists(dossier.resolve("pom.xml")))
                    .sorted()
                    .toList();
        }
    }

    /** La racine, reconnue a un fichier qui n'existe qu'a cet endroit. */
    static Path racineDuDepot() {
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

    /** Les sources de production d'un module, ou rien s'il n'en a pas. */
    static List<Path> sourcesDeProduction(Path module) throws IOException {
        Path sources = module.resolve("src/main/java");
        if (!Files.isDirectory(sources)) {
            return List.of();
        }
        try (Stream<Path> fichiers = Files.walk(sources)) {
            return fichiers.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    /**
     * Les commentaires XML retires.
     *
     * <p>Sur par construction : ils ne s'imbriquent pas et ne peuvent pas
     * contenir {@code --}.
     */
    static String sansCommentairesXml(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * Les commentaires Java retires, les chaines CONSERVEES.
     *
     * <h3>Pourquoi les chaines restent</h3>
     *
     * Ce qu'on cherche vit souvent DANS un litteral — un corps HTML, un nom de
     * classe, un chemin. Les retirer rendrait ces garde-fous aveugles a ce
     * qu'ils protegent.
     *
     * <h3>Un automate et non une expression reguliere</h3>
     *
     * {@code /*} apparait dans les litteraux de motif de chemin — ce depot
     * ecrit {@code path("/api/v1/x/&#42;&#42;")} — et une regex avale alors
     * tout le code jusqu'au prochain {@code *}{@code /}. Ce defaut exact a
     * rendu {@code CouvertureDesCheminsAdminTest} rouge sur du code correct.
     */
    public static String sansCommentairesJava(String source) {
        StringBuilder sortie = new StringBuilder(source.length());
        boolean chaine = false;
        boolean ligne = false;
        boolean bloc = false;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            String deux = i + 1 < source.length() ? source.substring(i, i + 2) : "";
            if (ligne) {
                if (c == '\n') {
                    ligne = false;
                    sortie.append(c);
                }
            } else if (bloc) {
                if (deux.equals("*/")) {
                    bloc = false;
                    i += 2;
                    continue;
                }
            } else if (chaine) {
                sortie.append(c);
                if (c == '\\' && i + 1 < source.length()) {
                    sortie.append(source.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    chaine = false;
                }
            } else {
                if (deux.equals("//")) {
                    ligne = true;
                    i += 2;
                    continue;
                }
                if (deux.equals("/*")) {
                    bloc = true;
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    chaine = true;
                }
                sortie.append(c);
            }
            i++;
        }
        return sortie.toString();
    }
}
