package com.abdatytch.commons.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lecture des claims du contrat JWT de la plateforme (voir
 * {@code docs/JWT-CONTRACT.md}).
 *
 * <h2>Pourquoi cette classe est partagée</h2>
 *
 * L'autorité émet le rôle sous <b>deux formes</b> selon le porteur :
 *
 * <table border="1">
 *   <caption>Formes du claim de rôle</caption>
 *   <tr><th>Porteur</th><th>Claim</th><th>Type</th></tr>
 *   <tr><td>Développeur</td><td>{@code role}</td><td>{@code String}</td></tr>
 *   <tr><td>Utilisateur final</td><td>{@code roles}</td><td>{@code List<String>}</td></tr>
 * </table>
 *
 * Chaque service avait sa propre lecture, et trois d'entre elles faisaient
 * l'hypothèse — jamais vraie pour un jeton utilisateur — que {@code roles} est
 * une chaîne :
 *
 * <pre>{@code claims.get("roles", String.class)  // lève RequiredTypeException}</pre>
 *
 * Les conséquences observées : le Gateway refusait en 401 la totalité des jetons
 * légitimes, et media-service refusait en 403 l'accès administrateur aux
 * documents. Dans les deux cas l'exception était captée et transformée en refus,
 * donc invisible autrement qu'à l'usage.
 *
 * <p>Cette classe est désormais la seule lecture autorisée. Elle accepte les
 * deux formes et ne lève jamais sur un claim d'un type inattendu — un jeton
 * malformé donne une liste vide, ce qui vaut « aucun rôle », pas une erreur.
 *
 * <h2>Pourquoi les méthodes prennent une {@code Map} et non un {@code Claims}</h2>
 *
 * {@code io.jsonwebtoken.Claims} <b>étend</b> {@code Map<String, Object>} : un
 * appelant qui détient un {@code Claims} n'a rien à changer. Mais tous les
 * services n'en ont pas un — auth-service expose
 * {@code extractAllClaims(String)}, qui rend une {@code Map}. Tant que la
 * signature exigeait un {@code Claims}, ce service <b>ne pouvait pas</b>
 * utiliser cette classe, et il en avait donc réécrit une lecture divergente :
 * c'est précisément la situation que cette classe existe pour supprimer.
 */
public final class JwtClaimsReader {

    /**
     * Types de jetons recevables comme jeton d'<b>accès</b>.
     *
     * <p>{@code refresh} et {@code refresh_user} en sont exclus délibérément :
     * ils portent la même signature et la même audience qu'un jeton d'accès mais
     * vivent bien plus longtemps. Les accepter reviendrait à accorder une session
     * de la durée du rafraîchissement.
     */
    private static final Set<String> ACCESS_TOKEN_TYPES = Set.of("developer", "user");

    private JwtClaimsReader() {
    }

    /** @return {@code true} si le claim {@code type} désigne un jeton d'accès. */
    public static boolean isAccessToken(Map<String, Object> claims) {
        return claims != null
                && claims.get("type") instanceof String type
                && ACCESS_TOKEN_TYPES.contains(type);
    }

    /**
     * Extrait les rôles, quelle que soit la forme émise.
     *
     * <p>Ordre de priorité : {@code roles} (liste ou chaîne), puis
     * {@code primaryRole}, puis {@code role}, puis {@code authorities}.
     *
     * @return les rôles non vides ; une liste vide si le jeton n'en porte aucun.
     *         Jamais {@code null}, jamais d'exception.
     */
    public static List<String> roles(Map<String, Object> claims) {
        if (claims == null) {
            return List.of();
        }

        List<String> fromRoles = asRoleList(claims.get("roles"));
        if (!fromRoles.isEmpty()) {
            return fromRoles;
        }

        String primary = asRole(claims.get("primaryRole"));
        if (primary != null) {
            return List.of(primary);
        }

        String single = asRole(claims.get("role"));
        if (single != null) {
            return List.of(single);
        }

        return asRoleList(claims.get("authorities"));
    }

    /** @return le rôle principal, ou {@code null} si le jeton n'en porte aucun. */
    public static String primaryRole(Map<String, Object> claims) {
        if (claims == null) {
            return null;
        }
        String primary = asRole(claims.get("primaryRole"));
        if (primary != null) {
            return primary;
        }
        List<String> roles = roles(claims);
        return roles.isEmpty() ? null : roles.get(0);
    }

    /**
     * Rôles du jeton sous la forme attendue par Spring Security.
     *
     * <h2>Pourquoi cette méthode est nécessaire</h2>
     *
     * {@code hasRole("ADMIN")} ne compare pas à {@code ADMIN} : il préfixe
     * lui-même et exige l'autorité {@code ROLE_ADMIN}. Un jeton de développeur
     * porte {@code role: "DEVELOPER"} <b>sans préfixe</b>, un jeton
     * d'utilisateur final porte {@code roles: ["ROLE_ADMIN", ...]} <b>avec</b>.
     * Transmettre le claim brut comme autorité — ce que faisaient les deux
     * filtres de la plateforme — rend donc le résultat de {@code hasRole}
     * dépendant de l'émetteur du jeton.
     *
     * <p>Le préfixe est posé s'il manque, et jamais doublé : un
     * {@code ROLE_ROLE_ADMIN} ne satisferait aucune règle.
     *
     * @return les autorités, toutes préfixées ; une liste vide si le jeton ne
     *         porte aucun rôle. Jamais {@code null}.
     */
    public static List<String> authorities(Map<String, Object> claims) {
        return roles(claims).stream()
                .map(JwtClaimsReader::stripPrefix)
                .filter(Objects::nonNull)
                .map(role -> "ROLE_" + role)
                .distinct()
                .toList();
    }

    /**
     * Comparaison de rôle insensible au préfixe {@code ROLE_}.
     *
     * <p>Les deux écritures circulent dans la plateforme : le Gateway propage
     * {@code ROLE_ADMIN}, certains services comparent à {@code ADMIN}. Une
     * égalité stricte laisse donc passer un refus selon l'émetteur du jeton.
     *
     * <p>La comparaison est une <b>égalité</b>, jamais un {@code contains} : un
     * rôle nommé {@code ADMIN_ASSISTANT} ne doit pas satisfaire une exigence
     * d'{@code ADMIN}.
     */
    public static boolean hasRole(Map<String, Object> claims, String expectedRole) {
        String expected = stripPrefix(expectedRole);
        if (expected == null) {
            return false;
        }
        return roles(claims).stream()
                .map(JwtClaimsReader::stripPrefix)
                .filter(Objects::nonNull)
                .anyMatch(expected::equalsIgnoreCase);
    }

    /**
     * Retire le préfixe {@code ROLE_}, quelle qu'en soit la casse.
     *
     * <p>Cette tolérance ne relâche rien : ce qui reste est comparé par
     * <b>égalité</b>. Elle évite seulement qu'un service écrivant le préfixe
     * autrement voie ses autorisations refusées sans explication.
     */
    private static String stripPrefix(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String trimmed = role.trim();
        return trimmed.regionMatches(true, 0, "ROLE_", 0, "ROLE_".length())
                ? trimmed.substring("ROLE_".length())
                : trimmed;
    }

    private static List<String> asRoleList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(role -> !role.isEmpty())
                    .toList();
        }
        String single = asRole(value);
        return single != null ? List.of(single) : List.of();
    }

    private static String asRole(Object value) {
        return value instanceof String role && !role.isBlank() ? role.trim() : null;
    }
}
