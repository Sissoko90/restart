package com.abdatytch.order_service.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * La decomposition d'une commande entre Sankadi et la boutique.
 *
 * <h2>L'arrondi va dans le sens de la BOUTIQUE</h2>
 *
 * La commission est arrondie vers le BAS, et la somme due a la boutique est
 * obtenue par soustraction. Deux consequences, et les deux sont voulues :
 *
 * <ul>
 *   <li>la decomposition BOUCLE toujours : articles = commission + du. La
 *       contrainte {@code commande_decomposition_boucle} l'exige, et calculer
 *       les deux parts separement laisserait un centime orphelin que personne
 *       ne saurait attribuer ;</li>
 *   <li>un centime d'arrondi va a la boutique et non a Sankadi. C'est un choix,
 *       pas un hasard : l'intermediaire ne doit jamais gagner sur un arrondi,
 *       sinon chaque commande lui donne un avantage systematique et un vendeur
 *       qui recompte le remarque.</li>
 * </ul>
 *
 * <h2>Jamais de flottant</h2>
 *
 * Les montants sont en unites mineures, dans des entiers. {@code BigDecimal}
 * n'intervient que pour le taux, et le resultat redescend immediatement en
 * {@code long}. Un {@code double} ferait de 0.1 + 0.2 autre chose que 0.3, et
 * une place de marche additionne des milliers de lignes.
 */
public final class CalculDesMontants {

    private CalculDesMontants() {
    }

    /**
     * @param montantArticles somme des lignes, en unites mineures
     * @param tauxPourcent    le taux de commission, fige sur la commande
     * @return les deux parts, dont la somme vaut exactement
     *         {@code montantArticles}
     */
    public static Decomposition decomposer(long montantArticles, BigDecimal tauxPourcent) {
        if (montantArticles < 0) {
            throw new IllegalArgumentException("Montant negatif : " + montantArticles);
        }
        if (tauxPourcent == null || tauxPourcent.signum() < 0
                || tauxPourcent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Taux de commission hors bornes : " + tauxPourcent);
        }

        long commission = BigDecimal.valueOf(montantArticles)
                .multiply(tauxPourcent)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
                .longValueExact();

        return new Decomposition(montantArticles, commission, montantArticles - commission);
    }

    public record Decomposition(long montantArticles, long commission, long duALaBoutique) {
    }
}
