package com.abdatytch.interface_service.controller;

import com.abdatytch.interface_service.service.IDevKeyRotationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

/**

 * Controller pour la gestion des clés JWT des développeurs
 *
 * Endpoints:
 * - GET /dev/.well-known/jwks.json : Expose les clés publiques (standard JWKS)
 * - POST /dev/admin/keys/rotate : Déclenche une rotation manuelle (admin only)
 * - GET /dev/admin/keys/status : Statut des clés (admin only)
 */
@RestController
@RequestMapping("/dev")
public class DevKeyManagementController {

    private static final Logger log = LoggerFactory.getLogger(DevKeyManagementController.class);

    private final IDevKeyRotationService keyRotationService;

    public DevKeyManagementController(IDevKeyRotationService keyRotationService) {
        this.keyRotationService = keyRotationService;
    }

    /**

     * JWKS (JSON Web Key Set) endpoint pour les développeurs
     * Standard OAuth2/OIDC pour exposer les clés publiques
     * Permet aux autres services de valider les JWT des développeurs
     */
    @GetMapping("/.well-known/jwks.json")
    public Mono<ResponseEntity<Map<String, Object>>> getJwks() {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> keys = new ArrayList<>();

            // Le `kid` provient du service de rotation (source unique de vérité) :
            // il doit correspondre exactement au `kid` inscrit dans l'en-tête des tokens.
            for (Map.Entry<String, PublicKey> entry : keyRotationService.getAllValidPublicKeysById().entrySet()) {
                if (entry.getValue() instanceof RSAPublicKey rsaKey) {
                    Map<String, Object> jwk = new LinkedHashMap<>();
                    jwk.put("kty", "RSA");
                    jwk.put("use", "sig");
                    jwk.put("alg", "RS256");
                    jwk.put("kid", entry.getKey());
                    jwk.put("n", base64UrlEncode(rsaKey.getModulus().toByteArray()));
                    jwk.put("e", base64UrlEncode(rsaKey.getPublicExponent().toByteArray()));
                    keys.add(jwk);
                }
            }

            Map<String, Object> jwks = new LinkedHashMap<>();
            jwks.put("keys", keys);

            log.debug("JWKS endpoint appelé - {} clés exposées", keys.size());

            return ResponseEntity.ok()
                    .header("Cache-Control", "public, max-age=3600")
                    .header("Content-Type", "application/json")
                    .body(jwks);
        });
    }

    /**

     * Déclenche une rotation manuelle des clés
     * Réservé aux administrateurs
     */
    @PostMapping("/admin/keys/rotate")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Map<String, Object>>> rotateKeys() {
        log.info("Manual dev key rotation requested");

        return keyRotationService.rotateKeys()
                .then(Mono.fromCallable(() -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "success");
                    response.put("message", "Dev key rotation completed successfully");
                    response.put("newKeyId", keyRotationService.getCurrentKeyId());
                    response.put("timestamp", java.time.Instant.now().toString());

                    return ResponseEntity.ok(response);
                }))
                // Un refus n'est pas une panne, et les confondre coute cher ici.
                //
                // Quand la rotation est desactivee — ce qui est le cas PAR
                // DEFAUT — le service rend desormais une IllegalStateException.
                // La rendre en 500 dirait a l'exploitant que le service est
                // casse, alors que la plateforme va bien et qu'il manque un
                // reglage. 409 est le code juste : la requete est bien formee,
                // mais l'etat courant du service l'interdit.
                //
                // Avant, ce cas rendait 200 avec « rotation completed
                // successfully » : le service rendait un Mono VIDE, que le
                // .then() ci-dessus transformait en reponse de succes, avec un
                // newKeyId inchange.
                .onErrorResume(IllegalStateException.class, refus -> {
                    log.warn("Rotation des cles refusee : {}", refus.getMessage());
                    Map<String, Object> reponse = new LinkedHashMap<>();
                    reponse.put("status", "refused");
                    reponse.put("message", refus.getMessage());
                    reponse.put("currentKeyId", keyRotationService.getCurrentKeyId());
                    reponse.put("timestamp", java.time.Instant.now().toString());
                    return Mono.just(ResponseEntity
                            .status(org.springframework.http.HttpStatus.CONFLICT)
                            .body(reponse));
                })
                .onErrorResume(e -> {
                    log.error("Dev key rotation failed", e);
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("status", "error");
                    error.put("message", "Dev key rotation failed: " + e.getMessage());
                    return Mono.just(ResponseEntity.internalServerError().body(error));
                });
    }

    /**

     * Retourne le statut des clés
     * Réservé aux administrateurs
     */
    @GetMapping("/admin/keys/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Map<String, Object>>> getKeyStatus() {
        return Mono.fromCallable(() -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("currentKeyId", keyRotationService.getCurrentKeyId());
            status.put("totalValidKeys", keyRotationService.getAllValidPublicKeys().size());
            status.put("rotationNeeded", keyRotationService.isRotationNeeded());
            status.put("issuer", "interface-service");
            status.put("audience", "restart-developers");
            status.put("timestamp", java.time.Instant.now().toString());

            return ResponseEntity.ok(status);
        });
    }

    /**

     * Nettoie les clés expirées
     * Réservé aux administrateurs
     */
    @DeleteMapping("/admin/keys/expired")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Map<String, Object>>> cleanupExpiredKeys() {
        log.info("Manual dev key cleanup requested");

        return keyRotationService.cleanupExpiredKeys()
                .map(removed -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "success");
                    response.put("removedKeys", removed);
                    response.put("timestamp", java.time.Instant.now().toString());

                    return ResponseEntity.ok(response);
                });
    }

    private String base64UrlEncode(byte[] bytes) {
        if (bytes.length > 0 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
