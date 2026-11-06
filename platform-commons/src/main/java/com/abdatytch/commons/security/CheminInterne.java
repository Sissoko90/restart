package com.abdatytch.commons.security;

/**
 * Ce qu'est un chemin d'appel inter-services, en un seul detenteur.
 *
 * <h2>Le defaut mesure qui a motive cette classe</h2>
 *
 * Les filtres de passerelle exigent la signature HMAC pour tout chemin hors
 * sondes. {@code /internal/**} n'y echappait que dans <b>deux</b> modules sur
 * quatre :
 *
 * <ul>
 *   <li>interface-service l'exemptait, et sa javadoc raconte l'incident qui
 *       l'avait impose : {@code POST /internal/jwt/sign} repondait 403, donc
 *       auth-service n'obtenait aucun jeton et <b>aucune connexion
 *       n'aboutissait</b> ;</li>
 *   <li>notification-service l'exempte depuis le deplacement du sous-systeme
 *       SMS ;</li>
 *   <li><b>user-service ne l'exemptait pas</b>, et son
 *       {@code InternalUserReactiveController} etait donc injoignable — un
 *       controleur complet, teste, derriere une porte fermee. Rien ne le
 *       signalait : son unique appelant avait ete retire, et il ne restait
 *       qu'un commentaire pour en parler ;</li>
 *   <li>setting-service n'avait pas encore de surface interne.</li>
 * </ul>
 *
 * <p>C'est la forme habituelle du probleme dans ce depot : une regle ecrite
 * plusieurs fois, presente ici, absente la, et dont l'absence ne produit aucune
 * erreur visible — seulement un 403 sur un chemin que personne n'appelle encore.
 *
 * <h2>La subtilite est dans la comparaison, et c'est pourquoi elle est ici</h2>
 *
 * {@code startsWith("/internal")} exempterait aussi {@code /internal-x} ou
 * {@code /internalement} : des chemins que personne n'a declares, et qu'un
 * controleur ajoute plus tard pourrait occuper <b>sans aucun controle</b>. Ce
 * depot a corrige trois fois cette famille de defaut dans ses filtres —
 * {@code contains("/actuator/")} exemptait {@code /auth/x/actuator/y}.
 *
 * <p>Une comparaison par segment entier ecrite a quatre endroits est une
 * comparaison qu'un seul des quatre finira par simplifier.
 *
 * <h2>Ce que cette exemption ne fait PAS</h2>
 *
 * Elle leve <b>la seule signature de passerelle</b>. Deux controles restent, et
 * il faut les deux :
 *
 * <ol>
 *   <li>le pair TCP doit etre une adresse privee — a verifier AVANT cette
 *       exemption, et non apres ;</li>
 *   <li>{@code X-Internal-Secret}, compare a temps constant par
 *       {@link AutorisationInterne} dans la chaine de securite.</li>
 * </ol>
 *
 * <p>Le second est celui qui porte : en conteneurs, « reseau prive » englobe
 * tous les voisins. Et la passerelle n'expose aucune route vers
 * {@code /internal/**}, donc ces chemins restent injoignables de l'exterieur.
 */
public final class CheminInterne {

    /** Le prefixe, nomme une seule fois pour les quatre filtres. */
    public static final String PREFIXE = "/internal";

    private CheminInterne() {
    }

    /**
     * {@code true} si ce chemin est un appel inter-services.
     *
     * @param chemin le chemin de DECISION — celui dont les echappements pourcent
     *               ont ete decodes. Le comparer au chemin brut laisserait
     *               {@code /%69nternal/x} passer pour autre chose que ce qu'il
     *               est
     */
    public static boolean correspond(String chemin) {
        if (chemin == null) {
            return false;
        }
        return chemin.equals(PREFIXE) || chemin.startsWith(PREFIXE + "/");
    }
}
