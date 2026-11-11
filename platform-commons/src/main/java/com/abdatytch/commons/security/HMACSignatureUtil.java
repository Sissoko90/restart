package com.abdatytch.commons.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**

 * Signature HMAC-SHA256 des requêtes Gateway  services — implémentation unique.
 *
 * <p>Le Gateway signe chaque requête sortante ; chaque service vérifie la signature
 * avant de traiter la requête. C'est ce qui empêche l'accès direct aux ports des
 * services en contournant le Gateway.
 *
 * <p>Données signées : {@code timestamp|path|method|requestId}. Le timestamp fournit
 * la protection anti-rejeu (fenêtre de tolérance de 5 secondes).
 */
public class HMACSignatureUtil {

    private static final Logger log = LoggerFactory.getLogger(HMACSignatureUtil.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** Tolérance d'horloge acceptée entre le Gateway et le service, en millisecondes. */
    private static final long TIMESTAMP_TOLERANCE_MS = 5_000L;

    public String generateSignature(String secretKey, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));

            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Échec de génération de la signature HMAC: {}", e.getMessage());
            throw new IllegalStateException("Échec de génération de la signature HMAC", e);
        }
    }

    /**

     * Valide une signature fournie, en temps constant.
     */
    public boolean validateSignature(String secretKey, String data, String providedSignature) {
        try {
            String expectedSignature = generateSignature(secretKey, data);
            return SecretComparisonUtil.matches(expectedSignature, providedSignature);
        } catch (Exception e) {
            log.error("Erreur de validation de signature: {}", e.getMessage());
            return false;
        }
    }

    public String buildSignatureData(String timestamp, String path, String method, String requestId) {
        return String.format("%s|%s|%s|%s", timestamp, path, method, requestId);
    }

    /**

     * Vérifie que le timestamp de la requête est dans la fenêtre de tolérance (anti-rejeu).
     */
    public boolean isTimestampValid(String timestamp) {
        try {
            long requestTime = Long.parseLong(timestamp);
            return Math.abs(System.currentTimeMillis() - requestTime) <= TIMESTAMP_TOLERANCE_MS;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
