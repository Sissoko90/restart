package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tout module qui expose {@code /internal/**} l'exempte de la signature de
 * passerelle.
 *
 * <h2>Le defaut que ce test ferme</h2>
 *
 * Les filtres de passerelle exigent la signature HMAC pour tout chemin hors
 * sondes. Un module qui sert {@code /internal/**} sans exempter ce prefixe rend
 * donc <b>403 sur chacun de ses appels inter-services</b>.
 *
 * <p>Deux modules sur quatre avaient le defaut :
 *
 * <ul>
 *   <li>interface-service l'a paye cher — {@code POST /internal/jwt/sign}
 *       repondait 403, auth-service n'obtenait aucun jeton, et <b>aucune
 *       connexion n'aboutissait</b>. Le symptome visible etait un 500 a la
 *       connexion, qui ne designait pas la cause ;</li>
 *   <li>user-service l'avait aussi, et personne ne s'en est apercu :
 *       {@code InternalUserReactiveController} etait complet et teste, derriere
 *       une porte fermee. Son unique appelant avait ete retire, et la passerelle
 *       n'expose aucune route vers {@code /internal/**} — donc aucun trafic ne
 *       venait buter dessus.</li>
 * </ul>
 *
 * <p>C'est pourquoi ce test regarde les SOURCES et non le comportement :
 * l'absence d'exemption ne se manifeste que le jour ou quelqu'un appelle, et a
 * ce moment-la elle ressemble a un probleme de secret.
 *
 * <h2>Ce qu'il verifie exactement</h2>
 *
 * Pour chaque module qui porte un controleur sur {@code /internal}, que son
 * filtre de passerelle cite {@link CheminInterne}. Citer le detenteur unique, et
 * non une comparaison recrite : c'est elle qui portait la subtilite —
 * {@code startsWith("/internal")} exempterait {@code /internalement}.
 */
@DisplayName("Exemption des appels internes dans les filtres de passerelle")
class ExemptionInterneDesFiltresTest {

    private static Path racineDuDepot() {
        Path courant = Path.of("").toAbsolutePath();
        for (int niveau = 0; niveau < 5 && courant != null; niveau++) {
            if (Files.isDirectory(courant.resolve("config-repo"))) {
                return courant;
            }
            courant = courant.getParent();
        }
        throw new IllegalStateException("Racine du depot introuvable");
    }

    private static List<Path> modules() throws IOException {
        Path racine = racineDuDepot();
        try (Stream<Path> entrees = Files.list(racine)) {
            return entrees
                    .filter(Files::isDirectory)
                    .filter(dossier -> Files.isRegularFile(dossier.resolve("pom.xml")))
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> sourcesJava(Path module) throws IOException {
        Path source = module.resolve("src/main/java");
        if (!Files.isDirectory(source)) {
            return List.of();
        }
        try (Stream<Path> fichiers = Files.walk(source)) {
            return fichiers.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    /**
     * Retire les commentaires avant d'analyser.
     *
     * <p>Sans cela, ce test tomberait sur les javadocs qui EXPLIQUENT pourquoi
     * un {@code startsWith} nu serait faux — l'erreur commise trois fois dans ce
     * depot : un garde-fou declenche par sa propre explication.
     */
    private static String sansCommentaires(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    @Nested
    @DisplayName("Chaque surface interne est joignable")
    class SurfaceJoignable {

        @Test
        @DisplayName("tout module servant /internal exempte ce préfixe de la signature")
        void toutModuleQuiSertInternelExempte() throws IOException {
            List<String> manquants = new ArrayList<>();

            for (Path module : modules()) {
                List<Path> sources = sourcesJava(module);
                if (sources.isEmpty()) {
                    continue;
                }

                boolean sertInterne = false;
                List<Path> filtres = new ArrayList<>();

                for (Path fichier : sources) {
                    String contenu = Files.readString(fichier);
                    String nom = fichier.getFileName().toString();

                    // La chaine complete avec le guillemet ouvrant, et non le
                    // mot « internal » n'importe ou : les javadocs de ce depot
                    // en parlent abondamment, et un test vert sur ses propres
                    // explications ne verifie rien.
                    if (contenu.contains("@RequestMapping(\"/internal")
                            || contenu.contains("@GetMapping(\"/internal")
                            || contenu.contains("@PostMapping(\"/internal")) {
                        sertInterne = true;
                    }

                    if (nom.contains("GatewayAccess")) {
                        filtres.add(fichier);
                    }
                }

                if (!sertInterne || filtres.isEmpty()) {
                    continue;
                }

                boolean exempte = false;
                for (Path filtre : filtres) {
                    if (Files.readString(filtre).contains("CheminInterne.correspond")) {
                        exempte = true;
                    }
                }

                if (!exempte) {
                    manquants.add(module.getFileName().toString());
                }
            }

            assertThat(manquants)
                    .withFailMessage("""
                            Ces modules servent /internal/** et leur filtre de \
                            passerelle N'EXEMPTE PAS ce prefixe : %s

                            Chaque appel inter-services vers eux repondra 403 \
                            « Missing required security headers », et le \
                            symptome ressemblera a un probleme de secret \
                            partage. interface-service a paye ce defaut par une \
                            panne totale de connexion.

                            Appeler CheminInterne.correspond(cheminDeDecision) \
                            APRES le controle d'adresse privee.""", manquants)
                    .isEmpty();
        }

        /**
         * Le pendant : l'exemption ne doit pas etre recrite a la main.
         *
         * <p>La comparaison par segment entier est la subtilite de cette regle.
         * Un {@code startsWith("/internal")} exempterait {@code /internal-x} et
         * {@code /internalement} — des chemins que personne n'a declares, et
         * qu'un controleur ajoute plus tard occuperait sans aucun controle. Ce
         * depot a corrige trois fois cette famille de defaut dans ses filtres.
         */
        @Test
        @DisplayName("aucun filtre ne réécrit la comparaison à la main")
        void aucuneComparaisonRecrite() throws IOException {
            List<String> recrites = new ArrayList<>();

            for (Path module : modules()) {
                for (Path fichier : sourcesJava(module)) {
                    if (!fichier.getFileName().toString().contains("GatewayAccess")) {
                        continue;
                    }
                    String contenu = sansCommentaires(Files.readString(fichier));
                    if (contenu.contains("startsWith(\"/internal")
                            || contenu.contains("equals(\"/internal\")")) {
                        recrites.add(module.getFileName() + "/" + fichier.getFileName());
                    }
                }
            }

            assertThat(recrites)
                    .withFailMessage("""
                            Ces filtres comparent /internal a la main : %s

                            Utiliser CheminInterne.correspond : la comparaison \
                            par segment entier est la subtilite de cette regle, \
                            et recrite elle finit par devenir un startsWith nu.""",
                            recrites)
                    .isEmpty();
        }
    }
}
