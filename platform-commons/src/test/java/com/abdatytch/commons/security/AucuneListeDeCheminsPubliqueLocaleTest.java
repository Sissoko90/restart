package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aucun filtre d'accès ne réécrit sa propre liste de chemins publics.
 *
 * <h2>Pourquoi ce garde-fou</h2>
 *
 * Cinq services tenaient chacun leur liste, dans le corps de leur filtre. Les
 * cinq ont divergé, et <b>trois défauts de la même famille</b> en sont sortis —
 * un contrôle qui désigne plus large que son intention. Chacun a été trouvé et
 * corrigé <b>séparément</b>, dans une copie, en laissant les autres :
 *
 * <ul>
 *   <li>{@code startsWith("/actuator")}, qui exemptait {@code env},
 *       {@code loggers} et {@code httptrace} ;</li>
 *   <li>{@code contains("/api-docs")}, sous le commentaire même qui expliquait
 *       pourquoi il fallait {@code startsWith} ;</li>
 *   <li>{@code endsWith("/error")}, corrigé dans <b>une</b> copie sur quatre.</li>
 * </ul>
 *
 * <p>{@link CheminsPublics} met fin à la duplication. Mais factoriser ne suffit
 * pas : rien n'empêche le prochain filtre d'écrire sa propre condition, et
 * c'est précisément ce qui s'est produit cinq fois. Ce test fait échouer la
 * construction dans ce cas.
 *
 * <h2>Ce qu'il ne fait pas</h2>
 *
 * Il ne vérifie ni la vérification HMAC, ni le contrôle d'IP, ni la forme du
 * refus : ce sont des différences <b>intentionnelles</b> entre services. Il ne
 * porte que sur la couture d'où sont sortis les défauts.
 */
@DisplayName("Aucun filtre ne tient sa propre liste de chemins publics")
class AucuneListeDeCheminsPubliqueLocaleTest {

    /**
     * Les tournures qui désignent un chemin public à la main.
     *
     * <p>La valeur est la raison citée en cas d'échec : un détecteur qui dit
     * seulement « interdit » se fait contourner, un détecteur qui dit pourquoi
     * se fait corriger.
     */
    private static final Map<String, String> TOURNURES_INTERDITES = Map.of(
            "\"/actuator", "les sondes sont declarees par CheminsPublics.sondes()",
            "\"/v3/api-docs", "la documentation est declaree par documentationOpenApi(boolean)",
            "\"/swagger-ui", "la documentation est declaree par documentationOpenApi(boolean)",
            "\"/webjars", "la documentation est declaree par documentationOpenApi(boolean)",
            "\"/error", "la page d'erreur est declaree par pageDErreur()");

    /**
     * Les exemptions, chacune avec sa raison.
     *
     * <p>interface-service est l'autorité JWT : il ne doit exposer que sa sonde
     * de santé et ses deux chemins JWKS, et <b>rien</b> de ce que
     * {@code CheminsPublics} propose par ailleurs. Sa liste « ultra
     * restreinte » est un choix documenté, pas une divergence oubliée.
     *
     * <p>gateway-service est la porte d'entrée : c'est le seul service dont la
     * documentation est légitimement publique, puisque c'est la seule qui soit
     * censée être lue.
     */
    private static final Map<String, String> TOLERES = Map.of(
            "interface-service", "autorite JWT : liste ultra restreinte, sonde + JWKS",
            "gateway-service", "porte d'entree : sa propre documentation est publique");

    private record Site(String fichier, String tournure, String raison) {
    }

    /** Protège le test de lui-même. Voir {@link #aucuneListeLocale()}. */
    private int fichiersExamines;

    private Path racine() {
        // Le test s'execute depuis platform-commons ; le depot est au-dessus.
        Path ici = Paths.get("").toAbsolutePath();
        return "platform-commons".equals(ici.getFileName().toString())
                ? ici.getParent()
                : ici;
    }

    private List<Site> balayer() throws IOException {
        List<Site> sites = new ArrayList<>();
        fichiersExamines = 0;

        try (Stream<Path> fichiers = Files.walk(racine())) {
            List<Path> filtres = fichiers
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String nom = p.getFileName().toString();
                        return nom.startsWith("GatewayAccess") && nom.endsWith(".java");
                    })
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().contains("/src/test/"))
                    .toList();

            for (Path filtre : filtres) {
                String module = moduleDe(filtre);
                if (TOLERES.containsKey(module)) {
                    continue;
                }

                fichiersExamines++;
                String code = sansCommentaires(
                        Files.readString(filtre, StandardCharsets.UTF_8));

                TOURNURES_INTERDITES.forEach((tournure, raison) -> {
                    if (code.contains(tournure)) {
                        sites.add(new Site(module + "/" + filtre.getFileName(),
                                tournure, raison));
                    }
                });
            }
        }
        return sites;
    }

    private String moduleDe(Path fichier) {
        return racine().relativize(fichier).getName(0).toString();
    }

    /**
     * Neutralise les commentaires.
     *
     * <p>Les commentaires de ces filtres <b>citent</b> abondamment les
     * tournures fautives, pour expliquer pourquoi elles ont été retirées. Sans
     * cette étape, ce test échouerait sur sa propre documentation — c'est le
     * faux positif qui a déjà produit trois détections erronées sur quatre dans
     * {@code AucunJustificatifDansLesJournauxTest}, et un détecteur qui crie à
     * tort finit désactivé.
     *
     * <p>Les positions ne sont pas conservées : seule la présence compte ici.
     */
    private String sansCommentaires(String source) {
        StringBuilder net = new StringBuilder(source.length());
        boolean dansLigne = false;
        boolean dansBloc = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char suivant = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (dansLigne) {
                if (c == '\n') {
                    dansLigne = false;
                    net.append(c);
                }
                continue;
            }
            if (dansBloc) {
                if (c == '*' && suivant == '/') {
                    dansBloc = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && suivant == '/') {
                dansLigne = true;
                i++;
                continue;
            }
            if (c == '/' && suivant == '*') {
                dansBloc = true;
                i++;
                continue;
            }
            net.append(c);
        }
        return net.toString();
    }

    @Test
    @DisplayName("les filtres delegent a CheminsPublics plutot que de lister eux-memes")
    void aucuneListeLocale() throws IOException {
        List<Site> sites = balayer();

        // Ce controle protege le test de lui-meme. Si le balayage ne trouve
        // plus de filtre — chemin faux, renommage, racine mal calculee — il
        // deviendrait vert EN N'EXAMINANT RIEN. C'est la forme de succes la
        // plus trompeuse, et ce depot l'a deja rencontree deux fois.
        assertThat(fichiersExamines)
                .withFailMessage("seulement %d filtre(s) d'acces examine(s) : le balayage "
                        + "ne trouve plus les fichiers, et ce test serait vert sans rien "
                        + "verifier. Racine utilisee : %s", fichiersExamines, racine())
                .isGreaterThanOrEqualTo(4);

        assertThat(sites)
                .withFailMessage("""
                        Ces filtres declarent des chemins publics a la main :

                        %s

                        La regle vit dans CheminsPublics (platform-commons). Cinq
                        copies locales ont deja diverge, et TROIS defauts de la
                        meme famille en sont sortis — un controle qui designe
                        plus large que son intention :

                          startsWith("/actuator")  exemptait env, loggers, httptrace
                          contains("/api-docs")    exemptait tout chemin le contenant
                          endsWith("/error")       corrige dans UNE copie sur quatre

                        Utiliser CheminsPublics.sondes() et ses methodes. Pour un
                        chemin propre a un service, cheminExact(...) ou prefixe(...)
                        — ils refusent ce qui ressemble a un motif.

                        Si une liste locale est legitime, l'inscrire dans TOLERES
                        avec sa raison.
                        """.formatted(sites.stream()
                        .map(s -> "  %s%n      %s  ->  %s"
                                .formatted(s.fichier(), s.tournure(), s.raison()))
                        .reduce((a, b) -> a + "\n" + b).orElse("")))
                .isEmpty();
    }
}
