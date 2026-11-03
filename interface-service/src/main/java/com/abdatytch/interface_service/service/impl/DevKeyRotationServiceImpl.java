package com.abdatytch.interface_service.service.impl;

import com.abdatytch.interface_service.service.IDevKeyRotationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**

 * Service de rotation des clés RSA pour les JWT des développeurs
 * Clés isolées de auth-service pour séparer les utilisateurs des développeurs
 */
@Service
public class DevKeyRotationServiceImpl implements IDevKeyRotationService {

    private static final Logger log = LoggerFactory.getLogger(DevKeyRotationServiceImpl.class);

    private static final String REDIS_KEY_PREFIX = "dev:jwt:keys:";
    private static final String REDIS_CURRENT_KEY_ID = "dev:jwt:keys:current";
    private static final int RSA_KEY_SIZE = 2048;

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Value("${jwt.dev.key.rotation.enabled:false}")
    private boolean rotationEnabled;

    @Value("${jwt.dev.key.rotation.interval-days:90}")
    private int rotationIntervalDays;

    @Value("${jwt.dev.key.rotation.grace-period-days:7}")
    private int gracePeriodDays;

    /**
     * Cle privee de signature, encodee en Base64 (contenu PEM sans les
     * en-tetes, ou PEM complet).
     *
     * <h3>Pourquoi c'est la source a privilegier</h3>
     *
     * Le defaut precedent etait {@code classpath:keys/dev_private.pem}, et la
     * cle vivait dans {@code src/main/resources}. Elle etait donc <b>empaquetee
     * dans le JAR livre</b> : quiconque disposait de l'artefact — une image de
     * conteneur, une sortie de build, un artefact de CI — pouvait l'en extraire
     * et forger n'importe quel jeton, pour n'importe quel porteur et n'importe
     * quel role, {@code SUPER_ADMIN} compris.
     *
     * <p>Une cle de signature ne doit jamais se trouver dans l'artefact qu'elle
     * protege. Elle vient de l'environnement, d'un secret monte, ou d'un service
     * de gestion de cles.
     */
    @Value("${jwt.dev.private-key:}")
    private String privateKeyBase64;

    @Value("${jwt.dev.public-key:}")
    private String publicKeyBase64;

    /**
     * Autorise la generation d'une paire ephemere quand rien n'est configure.
     *
     * <p>Faux par defaut, et a laisser faux hors poste de developpement : une
     * paire ephemere change a chaque redemarrage — tous les jetons en circulation
     * deviennent invalides — et differe d'une instance a l'autre, ce qui fait
     * refuser aleatoirement les jetons derriere un repartiteur de charge.
     */
    @Value("${jwt.dev.key.allow-ephemeral:false}")
    private boolean allowEphemeralKeys;

    @Value("${jwt.dev.private-key-path:}")
    private String privateKeyPath;

    @Value("${jwt.dev.public-key-path:}")
    private String publicKeyPath;

    private final Map<String, KeyPairInfo> keyCache = new ConcurrentHashMap<>();
    private volatile String currentKeyId;
    private volatile PrivateKey currentPrivateKey;
    private volatile PublicKey currentPublicKey;

    public DevKeyRotationServiceImpl(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        try {
            loadInitialKeys();
            log.info(" Dev Key Rotation Service initialized with key ID: {}", currentKeyId);

            if (rotationEnabled) {
                log.info(" Dev automatic key rotation enabled (interval: {} days, grace period: {} days)",
                    rotationIntervalDays, gracePeriodDays);
            }
        } catch (Exception e) {
            log.error(" Failed to initialize Dev Key Rotation Service", e);
            throw new RuntimeException("Failed to initialize Dev Key Rotation Service", e);
        }
    }

    /**
     * Charge la paire de cles, par ordre de preference :
     * <ol>
     *   <li>{@code jwt.dev.private-key} / {@code jwt.dev.public-key} — contenu
     *       fourni par l'environnement. Source a privilegier ;</li>
     *   <li>{@code jwt.dev.private-key-path} — fichier <b>hors du classpath</b>,
     *       typiquement un secret monte ;</li>
     *   <li>paire ephemere, uniquement si {@code jwt.dev.key.allow-ephemeral}
     *       est vrai.</li>
     * </ol>
     *
     * <p>Le chargement depuis le classpath a ete <b>retire</b> : il empaquetait
     * la cle privee dans le JAR livre.
     */
    private void loadInitialKeys() throws Exception {
        if (estRenseigne(privateKeyBase64) && estRenseigne(publicKeyBase64)) {
            log.info("Cles de signature chargees depuis la configuration");
            currentPrivateKey = parsePrivateKey(privateKeyBase64);
            currentPublicKey = parsePublicKey(publicKeyBase64);
        } else if (estRenseigne(privateKeyPath) && estRenseigne(publicKeyPath)) {
            refuserClasspath(privateKeyPath);
            refuserClasspath(publicKeyPath);
            log.info("Cles de signature chargees depuis le systeme de fichiers");
            currentPrivateKey = parsePrivateKey(loadKeyFromPath(privateKeyPath));
            currentPublicKey = parsePublicKey(loadKeyFromPath(publicKeyPath));
        } else if (allowEphemeralKeys) {
            log.warn("=".repeat(78));
            log.warn("PAIRE DE CLES EPHEMERE GENEREE - developpement uniquement.");
            log.warn("Elle change a chaque redemarrage : tous les jetons emis");
            log.warn("deviennent invalides. Elle differe d'une instance a l'autre :");
            log.warn("derriere un repartiteur, les jetons seront refuses au hasard.");
            log.warn("Renseigner JWT_DEV_PRIVATE_KEY et JWT_DEV_PUBLIC_KEY.");
            log.warn("=".repeat(78));
            KeyPair paire = genererPaire();
            currentPrivateKey = paire.getPrivate();
            currentPublicKey = paire.getPublic();
        } else {
            throw new IllegalStateException(
                "Aucune cle de signature configuree. Renseigner JWT_DEV_PRIVATE_KEY et "
                + "JWT_DEV_PUBLIC_KEY (contenu Base64), ou jwt.dev.private-key-path vers "
                + "un fichier hors du classpath. Pour un poste de developpement "
                + "uniquement : jwt.dev.key.allow-ephemeral=true.");
        }

        currentKeyId = generateKeyId(currentPublicKey);

        KeyPairInfo keyInfo = new KeyPairInfo(
            currentKeyId,
            currentPrivateKey,
            currentPublicKey,
            Instant.now(),
            null
        );
        keyCache.put(currentKeyId, keyInfo);

        log.info(" Dev RSA key pair loaded with ID: {}", currentKeyId);
    }

    private static boolean estRenseigne(String valeur) {
        return valeur != null && !valeur.isBlank();
    }

    /**
     * Le classpath n'est plus une source acceptable : une cle qui s'y trouve est
     * empaquetee dans l'artefact livre.
     */
    private void refuserClasspath(String path) {
        if (path.startsWith("classpath:")) {
            throw new IllegalStateException(
                "Chargement d'une cle depuis le classpath refuse (" + path + "). "
                + "Elle serait empaquetee dans le JAR livre, donc extractible par "
                + "quiconque dispose de l'artefact. Utiliser JWT_DEV_PRIVATE_KEY ou "
                + "un chemin de fichier hors du classpath.");
        }
    }

    private KeyPair genererPaire() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(RSA_KEY_SIZE, new SecureRandom());
        return keyGen.generateKeyPair();
    }

    private String loadKeyFromPath(String path) throws Exception {
        refuserClasspath(path);
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String key = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(key);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    private PublicKey parsePublicKey(String pem) throws Exception {
        String key = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(key);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    private String generateKeyId(PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, 8));
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().substring(0, 11);
        }
    }

    @Override
    public PrivateKey getCurrentPrivateKey() {
        return currentPrivateKey;
    }

    @Override
    public PublicKey getCurrentPublicKey() {
        return currentPublicKey;
    }

    @Override
    public List<PublicKey> getAllValidPublicKeys() {
        List<PublicKey> validKeys = new ArrayList<>();
        Instant now = Instant.now();

        for (KeyPairInfo keyInfo : keyCache.values()) {
            if (keyInfo.expiresAt == null || keyInfo.expiresAt.isAfter(now)) {
                validKeys.add(keyInfo.publicKey);
            }
        }

        return validKeys;
    }

    @Override
    public Map<String, PublicKey> getAllValidPublicKeysById() {
        Map<String, PublicKey> validKeys = new java.util.LinkedHashMap<>();
        Instant now = Instant.now();

        for (KeyPairInfo keyInfo : keyCache.values()) {
            if (keyInfo.expiresAt == null || keyInfo.expiresAt.isAfter(now)) {
                validKeys.put(keyInfo.keyId, keyInfo.publicKey);
            }
        }

        return validKeys;
    }

    @Override
    public String getCurrentKeyId() {
        return currentKeyId;
    }

    @Override
    public Mono<Void> rotateKeys() {
        if (!rotationEnabled) {
            log.warn(" Dev key rotation is disabled");
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            log.info(" Starting dev key rotation...");

            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(RSA_KEY_SIZE, new SecureRandom());
            KeyPair newKeyPair = keyGen.generateKeyPair();

            String newKeyId = generateKeyId(newKeyPair.getPublic());

            KeyPairInfo oldKeyInfo = keyCache.get(currentKeyId);
            if (oldKeyInfo != null) {
                oldKeyInfo.expiresAt = Instant.now().plus(Duration.ofDays(gracePeriodDays));
                log.info(" Old dev key {} will expire at {}", currentKeyId, oldKeyInfo.expiresAt);
            }

            KeyPairInfo newKeyInfo = new KeyPairInfo(
                newKeyId,
                newKeyPair.getPrivate(),
                newKeyPair.getPublic(),
                Instant.now(),
                null
            );
            keyCache.put(newKeyId, newKeyInfo);

            String oldKeyId = currentKeyId;
            currentKeyId = newKeyId;
            currentPrivateKey = newKeyPair.getPrivate();
            currentPublicKey = newKeyPair.getPublic();

            log.info(" Dev key rotation completed. New key ID: {}, Old key ID: {}", newKeyId, oldKeyId);

            return newKeyId;
        })
        .flatMap(newKeyId ->
            redisTemplate.opsForValue()
                .set(REDIS_CURRENT_KEY_ID, newKeyId)
                .then()
        )
        .doOnError(e -> log.error(" Dev key rotation failed", e));
    }

    @Override
    public boolean isRotationNeeded() {
        if (!rotationEnabled) {
            return false;
        }

        KeyPairInfo currentKey = keyCache.get(currentKeyId);
        if (currentKey == null) {
            return false;
        }

        Instant rotationThreshold = currentKey.createdAt.plus(Duration.ofDays(rotationIntervalDays));
        return Instant.now().isAfter(rotationThreshold);
    }

    @Override
    public PublicKey getPublicKeyById(String keyId) {
        KeyPairInfo keyInfo = keyCache.get(keyId);
        if (keyInfo == null) {
            return null;
        }

        if (keyInfo.expiresAt != null && keyInfo.expiresAt.isBefore(Instant.now())) {
            return null;
        }

        return keyInfo.publicKey;
    }

    @Override
    public Mono<Integer> cleanupExpiredKeys() {
        return Mono.fromCallable(() -> {
            int removed = 0;
            Instant now = Instant.now();

            Iterator<Map.Entry<String, KeyPairInfo>> iterator = keyCache.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, KeyPairInfo> entry = iterator.next();
                KeyPairInfo keyInfo = entry.getValue();

                if (entry.getKey().equals(currentKeyId)) {
                    continue;
                }

                if (keyInfo.expiresAt != null && keyInfo.expiresAt.isBefore(now)) {
                    iterator.remove();
                    removed++;
                    log.info(" Removed expired dev key: {}", entry.getKey());
                }
            }

            return removed;
        });
    }

    @Scheduled(cron = "0 0 0 * * ?")
    public void checkAndRotateIfNeeded() {
        if (isRotationNeeded()) {
            log.info(" Automatic dev key rotation triggered");
            rotateKeys()
                .then(cleanupExpiredKeys())
                .subscribe(
                    removed -> log.info(" Dev cleanup completed, removed {} expired keys", removed),
                    error -> log.error(" Scheduled dev rotation/cleanup failed", error)
                );
        }
    }

    private static class KeyPairInfo {
        final String keyId;
        final PrivateKey privateKey;
        final PublicKey publicKey;
        final Instant createdAt;
        Instant expiresAt;

        KeyPairInfo(String keyId, PrivateKey privateKey, PublicKey publicKey,
                   Instant createdAt, Instant expiresAt) {
            this.keyId = keyId;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }
}
