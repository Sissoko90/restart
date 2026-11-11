package com.abdatytch.market_service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La regle de visibilite n'est ecrite qu'a UN endroit.
 *
 * <h2>Ce que ce garde-fou protege</h2>
 *
 * Quatre conditions decident qu'un article est visible : la boutique approuvee,
 * l'article approuve et actif, le pays pour un particulier, la visibilite pour
 * un prix de gros. Recopiees dans chaque requete, elles finiraient par diverger
 * — et il suffit d'EN OUBLIER UNE, dans UNE requete, pour montrer a quelqu'un
 * ce qu'il n'a pas le droit de voir.
 *
 * <p>Ce depot a paye cinq fois cette erreur sur les chemins publics : cinq
 * listes divergentes, dont trois ouvraient plus large que leur intention. La
 * forme du defaut est toujours la meme.
 *
 * <h2>La regle exacte</h2>
 *
 * Toute requete de {@code ProduitRepository} qui RAMENE des produits doit citer
 * {@link PorteeDuCatalogue#CLAUSE_SQL}. Les exceptions sont nommees ici, une
 * par une, avec ce qui les autorise — et leur nombre est PINCE : en ajouter une
 * demande de toucher ce test, donc de l'expliquer.
 */
@DisplayName("La regle de visibilite a un seul detenteur")
class RegleDeVisibiliteUniqueTest {

    private static final Path DEPOT = Path.of(
            "src/main/java/com/abdatytch/market_service/repository/ProduitRepository.java");

    /**
     * Les requetes qui ramenent des produits SANS la clause, et pourquoi.
     *
     * <ul>
     *   <li>{@code parBoutique} — la vitrine du vendeur. L'autorisation est
     *       portee par l'appelant, qui a retrouve la boutique par le sujet du
     *       jeton : un vendeur voit ses propres articles quel que soit leur
     *       etat, c'est le but ;</li>
     *   <li>{@code aModerer} — la file de moderation. Son appelant exige
     *       {@code ROLE_ADMIN}, et un moderateur doit precisement voir ce qui
     *       n'est pas encore visible.</li>
     * </ul>
     *
     * <p>Les deux prennent un identifiant deja verifie, ou aucun. Aucune des
     * deux n'accepte de parametre venu de la requete HTTP.
     */
    private static final Set<String> EXCEPTIONS_EXPLIQUEES =
            Set.of("parBoutique", "aModerer");

    /**
     * Les methodes qui ne ramenent AUCUN produit.
     *
     * <p>Elles comptent, ou elles ecrivent. {@code compterLeCatalogue} porte la
     * clause parce qu'un COMPTE sans elle annoncerait des pages vides ; les
     * autres ne rendent rien qu'on puisse lire.
     */
    private static final Set<String> SANS_LECTURE_DE_PRODUIT =
            Set.of("reconcilierLesAgregats", "propagerLePays", "compterAModerer");

    @Test
    @DisplayName("toute requete qui ramene des produits cite la clause partagee")
    void aucuneRequeteSansClause() throws IOException {
        String source = Files.readString(DEPOT);
        List<String> fautives = new ArrayList<>();

        // Chaque @Query et la signature qui la suit.
        Matcher m = Pattern.compile(
                "@Query\\((.*?)\\)\\s*(?:Flux|Mono)<([^>]+)>\\s+(\\w+)\\(",
                Pattern.DOTALL).matcher(source);

        int vues = 0;
        while (m.find()) {
            vues++;
            String requete = m.group(1);
            String rendu = m.group(2);
            String methode = m.group(3);

            if (SANS_LECTURE_DE_PRODUIT.contains(methode) || !rendu.contains("Produit")) {
                continue;
            }
            if (requete.contains("PorteeDuCatalogue.CLAUSE_SQL")) {
                continue;
            }
            if (EXCEPTIONS_EXPLIQUEES.contains(methode)) {
                continue;
            }
            fautives.add(methode);
        }

        assertThat(vues)
                .withFailMessage("Aucune requete lue dans %s : le motif ne reconnait "
                        + "plus rien et ce test ne protege plus personne.", DEPOT)
                .isGreaterThan(5);

        assertThat(fautives)
                .withFailMessage("""
                        Ces requetes ramenent des produits SANS citer la clause de \
                        visibilite :

                        %s

                        PorteeDuCatalogue.CLAUSE_SQL est le seul detenteur de la regle. \
                        Quatre conditions decident qu'un article est visible, et il \
                        suffit d'en oublier UNE dans UNE requete pour montrer a \
                        quelqu'un ce qu'il n'a pas le droit de voir.

                        Si cette requete est legitimement hors regle — une vitrine de \
                        vendeur, une file de moderation — ajoutez-la a \
                        EXCEPTIONS_EXPLIQUEES en disant ce qui l'autorise.""", fautives)
                .isEmpty();
    }

    /**
     * Les exceptions sont PINCEES en nombre.
     *
     * <p>Une liste qui grandit sans que personne ne la relise redevient une
     * tolerance ouverte. Ce controle ne juge pas du contenu — il oblige a
     * passer par ici.
     */
    @Test
    @DisplayName("la liste des exceptions n'a pas grandi en silence")
    void exceptionsPincees() {
        assertThat(EXCEPTIONS_EXPLIQUEES)
                .withFailMessage("""
                        La liste des requetes hors regle a change.

                        Ce n'est pas interdit, c'est a EXPLIQUER : chaque entree doit \
                        dire ce qui l'autorise a se passer de la clause de visibilite. \
                        Mettez la javadoc a jour en meme temps que ce test.""")
                .hasSize(2)
                .containsExactlyInAnyOrder("parBoutique", "aModerer");
    }

    /**
     * La clause SQL et la decision unitaire doivent parler des memes champs.
     *
     * <p>{@code voit(Produit, boolean)} existe pour les lectures unitaires et
     * les tests ; si elle s'ecarte de la clause SQL, deux reponses differentes
     * sortent du meme service selon le chemin emprunte.
     */
    @Test
    @DisplayName("la clause SQL nomme les quatre conditions")
    void clauseComplete() {
        String clause = PorteeDuCatalogue.CLAUSE_SQL;
        assertThat(clause)
                .withFailMessage("""
                        La clause de visibilite ne nomme plus les quatre conditions.

                        Clause actuelle :
                        %s

                        Attendu : le statut de la boutique, le statut de moderation du \
                        produit, son activation, son pays et sa visibilite.""", clause)
                .contains("b.statut")
                .contains("p.statut_moderation")
                .contains("p.actif")
                .contains("p.pays")
                .contains("p.visibilite");
    }
}
