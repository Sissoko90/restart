package com.abdatytch.commons.plateforme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toute requete {@code @Query} qui ECRIT porte {@code @Modifying}.
 *
 * <h2>Pourquoi ce garde-fou existe</h2>
 *
 * Sans {@code @Modifying}, Spring Data R2DBC traite la requete comme une
 * LECTURE. Elle est bien executee — la base est modifiee — mais aucun compte
 * de lignes n'est emis : le {@code Mono} retourne est <b>VIDE</b>.
 *
 * <p>Les consequences sont silencieuses et graves :
 *
 * <ul>
 *   <li>un {@code flatMap} en aval n'est JAMAIS appele, donc toute verification
 *       posee dessus est sautee ;</li>
 *   <li>un {@code Flux} de lignes rend une liste plus courte que demandee, sans
 *       erreur ;</li>
 *   <li>l'appel HTTP rend 200 avec un corps vide, et l'appelant croit avoir
 *       reussi.</li>
 * </ul>
 *
 * <h2>Mesure du 2026-11-14</h2>
 *
 * {@code ProduitVarianteRepository.reserver} declarait
 * {@code Mono<Integer> reserver(...)} sur un {@code UPDATE} sans
 * {@code @Modifying}. Sur le service en marche, reserver cinq unites d'un
 * article qui n'en avait qu'une rendait <b>200 avec une liste vide</b>, sans
 * journaliser le refus. L'appelant aurait cree une commande a zero ligne.
 *
 * <p>Le meme defaut etait dans les deux {@code changerStatut} d'order-service :
 * AUCUNE transition de commande n'aurait jamais abouti, et le verrou optimiste
 * — qui repose entierement sur le compte de lignes modifiees — n'aurait rien
 * verrouille.
 *
 * <h2>Pourquoi un garde-fou structurel et non une relecture</h2>
 *
 * Le defaut ne se voit pas. Le code compile, la requete s'execute, la base est
 * correcte. Seul le COMPTE manque, et c'est sur lui que repose toute la
 * logique de decision. Trois occurrences ont survecu a l'ecriture et a la
 * relecture ; seule l'epreuve sur un service en marche l'a revele.
 */
@DisplayName("Toute requete qui ecrit porte @Modifying")
class RequetesModifiantesAnnoteesTest {

    /**
     * Les mots qui font d'une requete une ecriture.
     *
     * <p>{@code RETURNING} ne les annule pas : un {@code INSERT ... RETURNING *}
     * rend bien des lignes, mais c'est un cas a part traite plus bas.
     */
    private static final Pattern ECRITURE = Pattern.compile(
            "\\b(UPDATE|INSERT\\s+INTO|DELETE\\s+FROM)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Plancher de requetes examinees.
     *
     * <p>Sans lui, une extraction cassee rendrait zero requete et ce test
     * passerait sans rien verifier — le defaut qu'un garde-fou doit s'interdire
     * avant tout autre.
     */
    private static final int PLANCHER_DE_REQUETES = 10;

    @Test
    @DisplayName("aucun UPDATE, INSERT ou DELETE sans @Modifying")
    void toutesAnnotees() throws IOException {
        List<String> manquantes = new ArrayList<>();
        int examinees = 0;

        for (Path fichier : depots()) {
            String source = SourcesDuDepot.sansCommentairesJava(Files.readString(fichier));

            // Chaque @Query et ce qui le PRECEDE immediatement. L'annotation
            // @Modifying peut etre avant ou apres @Query : les deux ordres sont
            // valides, donc on regarde la declaration entiere.
            Matcher requete = Pattern.compile(
                    "((?:@\\w+(?:\\([^)]*\\))?\\s*)*)@Query\\s*\\(\\s*(\"\"\".*?\"\"\"|\"[^\"]*\")\\s*\\)"
                            + "((?:\\s*@\\w+(?:\\([^)]*\\))?)*)\\s*([^;]*);",
                    Pattern.DOTALL).matcher(source);

            while (requete.find()) {
                String sql = requete.group(2);
                if (!ECRITURE.matcher(sql).find()) {
                    continue;
                }
                examinees++;

                // RETURNING : la requete ECRIT et rend des lignes. Spring Data
                // la traite alors comme une lecture, ce qui est CORRECT — c'est
                // bien une ligne qu'on veut, pas un compte. @Modifying y serait
                // faux et casserait le type de retour.
                if (sql.toUpperCase().contains("RETURNING")) {
                    continue;
                }

                String annotations = requete.group(1) + requete.group(3);
                if (!annotations.contains("@Modifying")) {
                    String signature = requete.group(4).replaceAll("\\s+", " ").trim();
                    manquantes.add(fichier.getFileName() + " : " + signature);
                }
            }
        }

        assertThat(examinees)
                .as("""
                        Aucune requete d'ecriture examinee. L'extraction est \
                        cassee, et ce test passerait sans rien verifier.""")
                .isGreaterThanOrEqualTo(PLANCHER_DE_REQUETES);

        assertThat(manquantes)
                .as("""
                        Ces requetes ECRIVENT sans porter @Modifying.

                        Spring Data R2DBC les traite comme des LECTURES : la base \
                        est bien modifiee, mais aucun compte de lignes n'est emis \
                        et le Mono est VIDE.

                        Consequence : tout flatMap en aval est saute, donc toute \
                        verification posee dessus. Un verrou optimiste qui compte \
                        les lignes modifiees ne verrouille alors plus rien, et un \
                        appel HTTP rend 200 sur un refus.

                        Mesure du 2026-11-14 : trois occurrences, dont les deux \
                        changerStatut d'order-service. Aucune transition de \
                        commande n'aurait jamais abouti.

                        Si la requete rend des LIGNES et non un compte, elle \
                        porte RETURNING et ce test l'ignore.""")
                .isEmpty();
    }

    /** Les interfaces de depot de tous les modules. */
    private static List<Path> depots() throws IOException {
        try (Stream<Path> fichiers = Files.walk(SourcesDuDepot.racineDuDepot())) {
            return fichiers
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> p.toString().endsWith("Repository.java"))
                    .sorted()
                    .toList();
        }
    }
}
