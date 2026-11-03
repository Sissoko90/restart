package com.abdatytch.media_service.constants;

import com.abdatytch.commons.security.CheminsPublics;

/**
 * Constantes de sécurité
 */
public final class SecurityConstants {

    private SecurityConstants() {}

    // Headers
    public static final String HEADER_AUTHORIZATION = "Authorization";
    // `HEADER_X_SECRET_KEY` a ete RETIRE : la passerelle n'emet plus cet
    // en-tete et le purge des requetes entrantes, parce qu'il transportait le
    // secret partage sur le reseau a chaque requete. Le garder nomme ici
    // inviterait a revenir a l'ancien contrat, celui qui faisait refuser
    // toutes les requetes.
    public static final String HEADER_X_GATEWAY_SIGNATURE = "X-Gateway-Signature";
    public static final String HEADER_X_GATEWAY_TIMESTAMP = "X-Gateway-Timestamp";
    public static final String HEADER_X_GATEWAY_REQUEST_ID = "X-Gateway-Request-Id";
    public static final String HEADER_X_AUTH_USER_ID = "X-Auth-User-Id";
    
    // Préfixes
    public static final String BEARER_PREFIX = "Bearer ";
    
    /**
     * Les chemins publics de ce module.
     *
     * <h3>Le détenteur est platform-commons</h3>
     *
     * {@link CheminsPublics} remplace les cinq listes que les services tenaient
     * chacun de leur côté, et d'où sont sortis trois défauts de la même
     * famille — un contrôle qui désigne plus large que son intention.
     *
     * <h3>{@code /api/v1/internal/**} a été retiré</h3>
     *
     * L'entrée était fausse deux fois. {@code /api/v1} est le préfixe
     * <b>public</b>, celui que la passerelle retire avant de transmettre :
     * aucun service de cette plateforme n'a de {@code context-path}. Et
     * media-service ne sert <b>aucun</b> chemin {@code /internal/**} — ses trois
     * contrôleurs servent {@code /media}, {@code /admin/documents} et
     * {@code /admin/document-types}.
     *
     * <h3>La documentation OpenAPI : signature exigée, jeton non</h3>
     *
     * {@code documentationViaPasserelle()} place la documentation dans la
     * <b>troisième catégorie</b>, celle qui exprime la règle de la plateforme :
     * <b>la passerelle est la seule porte, en entrée comme en sortie</b>.
     *
     * <ul>
     *   <li>elle n'est <b>pas</b> exemptée du contrôle d'accès : l'attaquer
     *       directement sur le port de ce service échoue, faute de signature
     *       HMAC valide ;</li>
     *   <li>elle est exemptée du <b>jeton</b> dans la chaîne Security : une
     *       requête que la passerelle a signée est servie.</li>
     * </ul>
     *
     * <p>C'est ce qui déplace la décision « qui peut lire la documentation »
     * sur la passerelle, qui l'agrège et la protège <b>une</b> fois, au lieu de
     * sept surfaces à ouvrir et refermer. Le document décrit toute la surface
     * d'API, chemins d'administration compris.
     */
    private static final CheminsPublics CHEMINS_PUBLICS = CheminsPublics.sondes()
            .pageDErreur()
            .documentationViaPasserelle()
            .construire();

    /**
     * Le seul juge de ce qui est public dans ce module.
     *
     * <p>Les deux filtres — accès passerelle et validation du jeton —
     * appliquaient chacun leur copie de
     * {@code path.matches(motif.replace("**", ".*"))}, qui traitait les entrées
     * comme des <b>expressions régulières</b>. Ils appellent désormais ceci.
     */
    public static boolean estCheminPublic(String chemin) {
        return CHEMINS_PUBLICS.contient(chemin);
    }

    /** Les mêmes chemins, pour les {@code pathMatchers} de {@code SecurityConfig}. */
    public static String[] motifsPublicsSpring() {
        return CHEMINS_PUBLICS.motifsPermitAll();
    }
}
