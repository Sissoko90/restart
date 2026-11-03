package com.abdatytch.commons.totp;

import com.abdatytch.commons.security.SecretComparisonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Second facteur TOTP, conforme RFC 6238.
 *
 * <p>Partage par tous les services qui protegent un acces privilegie. Vivait
 * auparavant dans interface-service ; remonte ici pour qu'auth-service protege
 * son compte {@code SUPER_ADMIN} sans qu'une seconde implementation soit ecrite —
 * deux implementations d'un second facteur divergent, et l'une des deux finit par
 * etre la mauvaise.
 *
 * <p>Declare par {@code PlatformSecurityAutoConfiguration}. La conformite est
 * verifiee contre les vecteurs d'essai officiels de la RFC : un TOTP « presque »
 * conforme produit des codes coherents avec lui-meme, qu'aucune application
 * d'authentification ne reconnait.
 */
public class TotpServiceImpl implements TotpService {

    private static final Logger log = LoggerFactory.getLogger(TotpServiceImpl.class);
    
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final int TOTP_DIGITS = 6;
    private static final int TIME_STEP = 30; // 30 secondes
    private static final int SECRET_LENGTH = 20; // 160 bits
    
    // Caractères Base32 (sans padding)
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    @Override
    public String generateTotpSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[SECRET_LENGTH];
        random.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /**
     * Construit l'URI d'enrôlement au format « Key URI » attendu par les
     * applications d'authentification.
     *
     * <p>Le libellé et l'émetteur sont <b>encodés</b>. Insérés bruts, une adresse
     * contenant {@code &} ou {@code ?} ajoutait ses propres paramètres à l'URI :
     * l'application affichait alors un émetteur choisi par l'utilisateur, ce qui
     * invite à confondre le compte avec celui d'un autre service. L'encodage est
     * d'ailleurs exigé par la spécification.
     */
    @Override
    public String generateQrCodeUrl(String userEmail, String totpSecret, String issuer) {
        String label = encodeUriComponent(issuer + ":" + userEmail);
        return String.format(
            "otpauth://totp/%s?secret=%s&issuer=%s&algorithm=SHA1&digits=%d&period=%d",
            label, encodeUriComponent(totpSecret), encodeUriComponent(issuer),
            TOTP_DIGITS, TIME_STEP
        );
    }

    /**
     * {@link URLEncoder} encode l'espace en {@code +}, ce qui n'est valide que
     * dans un corps de formulaire — dans une URI il doit être {@code %20}.
     */
    private String encodeUriComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public Mono<Boolean> validateTotpCode(String totpSecret, String totpCode) {
        return Mono.fromCallable(() -> {
            try {
                if (totpCode == null || totpCode.length() != TOTP_DIGITS) {
                    return false;
                }
                
                long currentTime = System.currentTimeMillis() / 1000L;
                long timeWindow = currentTime / TIME_STEP;
                
                // Vérifier le code actuel et les fenêtres adjacentes (±1)
                // pour compenser les décalages d'horloge
                for (int i = -1; i <= 1; i++) {
                    String expectedCode = generateTotpCodeForTime(totpSecret, timeWindow + i);
                    // Comparaison a temps constant. `equals` sur une chaine
                    // s'arrete au premier caractere different : le temps de
                    // reponse renseigne alors sur le nombre de caracteres
                    // corrects. Sur six chiffres et a travers un reseau,
                    // l'exploitation est peu realiste — mais la correction ne
                    // coute rien, et un second facteur est precisement ce qu'on
                    // ne veut pas voir s'eroder.
                    if (SecretComparisonUtil.matches(expectedCode, totpCode)) {
                        log.debug("Code TOTP valide pour la fenêtre temporelle: {}", timeWindow + i);
                        return true;
                    }
                }
                
                log.debug("Code TOTP invalide: {} pour le secret", totpCode);
                return false;
                
            } catch (Exception e) {
                log.error("Erreur lors de la validation du code TOTP", e);
                return false;
            }
        });
    }

    @Override
    public String generateTotpCode(String totpSecret) {
        long currentTime = System.currentTimeMillis() / 1000L;
        long timeWindow = currentTime / TIME_STEP;
        return generateTotpCodeForTime(totpSecret, timeWindow);
    }

    /**
     * Génère un code TOTP pour une fenêtre temporelle donnée
     */
    private String generateTotpCodeForTime(String totpSecret, long timeWindow) {
        try {
            byte[] secretBytes = decodeBase32(totpSecret);
            byte[] timeBytes = ByteBuffer.allocate(8).putLong(timeWindow).array();
            
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretBytes, HMAC_ALGORITHM);
            mac.init(keySpec);
            
            byte[] hash = mac.doFinal(timeBytes);
            
            // Extraction dynamique (RFC 4226)
            int offset = hash[hash.length - 1] & 0x0F;
            int truncatedHash = ((hash[offset] & 0x7F) << 24) |
                               ((hash[offset + 1] & 0xFF) << 16) |
                               ((hash[offset + 2] & 0xFF) << 8) |
                               (hash[offset + 3] & 0xFF);
            
            int code = truncatedHash % (int) Math.pow(10, TOTP_DIGITS);
            return String.format("%0" + TOTP_DIGITS + "d", code);
            
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Erreur lors de la génération du code TOTP", e);
            throw new RuntimeException("Impossible de générer le code TOTP", e);
        }
    }

    /**
     * Encode un tableau de bytes en Base32
     */
    private String encodeBase32(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            
            while (bitsLeft >= 5) {
                result.append(BASE32_CHARS.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        
        if (bitsLeft > 0) {
            result.append(BASE32_CHARS.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        
        return result.toString();
    }

    /**
     * Décode une chaîne Base32 en tableau de bytes
     */
    private byte[] decodeBase32(String base32) {
        base32 = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        
        if (base32.isEmpty()) {
            return new byte[0];
        }
        
        int outputLength = base32.length() * 5 / 8;
        byte[] result = new byte[outputLength];
        
        int buffer = 0;
        int bitsLeft = 0;
        int resultIndex = 0;
        
        for (char c : base32.toCharArray()) {
            int value = BASE32_CHARS.indexOf(c);
            if (value == -1) {
                throw new IllegalArgumentException("Caractère Base32 invalide: " + c);
            }
            
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            
            if (bitsLeft >= 8) {
                result[resultIndex++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        
        return result;
    }
}
