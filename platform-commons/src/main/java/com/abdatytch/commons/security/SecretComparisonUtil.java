package com.abdatytch.commons.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Comparaison de secrets partagés à temps constant.
 *
 * <p>{@code String.equals} s'interrompt au premier caractère divergent : le temps de
 * réponse dépend donc du nombre de caractères corrects fournis, ce qui permet à un
 * attaquant de reconstituer le secret octet par octet (timing attack). Tous les
 * secrets comparés en bordure d'API (X-Internal-Secret, X-Admin-Secret,
 * X-Maintenance-Key, X-Secret-Key, signatures HMAC) doivent passer par cette classe.
 */
public final class SecretComparisonUtil {

    private SecretComparisonUtil() {
        // Classe utilitaire
    }

    /**
     * Compare deux secrets à temps constant.
     *
     * @param expected secret attendu (issu de la configuration)
     * @param provided secret fourni par l'appelant
     * @return true si les deux secrets sont non nuls, non vides et identiques
     */
    public static boolean matches(String expected, String provided) {
        if (expected == null || expected.isEmpty() || provided == null) {
            return false;
        }

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
