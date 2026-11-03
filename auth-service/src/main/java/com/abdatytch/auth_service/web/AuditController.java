package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.dto.request.AuditPhoneSearchRequest;
import com.abdatytch.auth_service.entities.AuthenticationLog;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import com.abdatytch.auth_service.service.IAuthenticationLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Contrôleur admin pour la consultation des audits et logs d'authentification
 * - Consultation des logs d'authentification
 * - Statistiques de sécurité
 * - Analyse des tentatives échouées
 */
@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
@Slf4j
public class AuditController {
    
    private final IAuthenticationLogService authenticationLogService;
    
    /**
     * Obtenir les logs d'authentification récents
     * GET /admin/audit/logs?limit=100
     */
    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> getRecentLogs() {
        
        log.info("Admin - Consultation des logs d'authentification");
        
        return authenticationLogService.getAllLogs().collectList()
            .map(ResponseEntity::ok);
    }
    
    /**
     * Obtenir les logs pour un utilisateur spécifique
     * GET /admin/audit/logs/user/{userId}
     */
    @GetMapping("/logs/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> getUserLogs(@PathVariable String userId) {
        log.info("Admin - Consultation logs pour utilisateur: {}", userId);
        
        return authenticationLogService.getLogsByUserId(userId).collectList()
            .map(ResponseEntity::ok);
    }
    
    /**
     * Journaux d'authentification d'un numéro de téléphone.
     *
     * <h3>Pourquoi un POST avec un corps, et non un GET avec le numéro dans l'URL</h3>
     *
     * L'endpoint était {@code GET /admin/audit/logs/phone/{phoneNumber}}. Le
     * numéro figurait donc dans le chemin, et une URL est écrite en clair dans
     * les journaux d'accès du Gateway, de tout proxy traversé, des traces de
     * supervision, de l'historique du navigateur et de l'en-tête
     * {@code Referer} — même en HTTPS.
     *
     * <p>Le point décisif n'est pas la divulgation mais l'<b>ineffaçabilité</b> :
     * {@code AccountErasureService} sait écraser une colonne, il ne sait pas
     * retirer un numéro des journaux d'accès d'un proxy. Une donnée personnelle
     * versée dans une URL sort définitivement du périmètre que l'effacement
     * RGPD peut atteindre.
     *
     * <p>Un paramètre de requête n'y changerait rien : la chaîne de requête est
     * journalisée avec le chemin. Seul le corps y échappe, d'où le {@code POST}.
     *
     * <p>Le numéro ne figure pas non plus dans la journalisation applicative de
     * cette méthode : l'y écrire aurait recréé exactement le même problème dans
     * Loki, à un endroit que l'effacement n'atteint pas davantage.
     */
    @PostMapping("/logs/search")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> searchLogsByPhone(
            @Valid @RequestBody AuditPhoneSearchRequest requete) {
        log.info("Admin - Consultation des journaux par numéro de téléphone");

        return authenticationLogService.getLogsByPhoneNumber(requete.getPhoneNumber())
                .collectList()
                .map(ResponseEntity::ok);
    }
    
    /**
     * Obtenir les tentatives échouées récentes
     * GET /admin/audit/failed-attempts
     */
    @GetMapping("/failed-attempts")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Map<String, Object>>> getFailedAttempts() {
        
        log.info("Admin - Consultation tentatives échouées");
        
        return authenticationLogService
                .getLogsByEventTypeAndStatus(EventType.LOGIN, Status.FAILED)
                .collectList()
                .map(failedLogins -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("failedAttempts", failedLogins);
                    response.put("count", failedLogins.size());
                    return ResponseEntity.ok(response);
                });
    }
    
    /**
     * Statistiques de sécurité
     * GET /admin/audit/security-stats
     */
    @GetMapping("/security-stats")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Map<String, Object>>> getSecurityStats() {
        log.info("Admin - Consultation statistiques de sécurité");
        
        return Mono.zip(
                authenticationLogService.getAllLogs().collectList(),
                authenticationLogService.getLogsByEventTypeAndStatus(EventType.LOGIN, Status.FAILED)
                        .collectList())
                .map(tuple -> {
            List<AuthenticationLog> allLogs = tuple.getT1();
            List<AuthenticationLog> failedLogins = tuple.getT2();
            
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalLogs", allLogs.size());
            stats.put("failedLogins", failedLogins.size());
            stats.put("successRate", calculateSuccessRate(allLogs.size(), failedLogins.size()));
            
            return ResponseEntity.ok(stats);
        });
    }
    
    /**
     * Nettoyer les anciens logs
     * DELETE /admin/audit/logs/cleanup?days=90
     */
    @DeleteMapping("/logs/cleanup")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Map<String, String>>> cleanupOldLogs(
            @RequestParam(defaultValue = "90") int days) {
        
        // Borne inferieure VERIFIEE EXPLICITEMENT, et non par une annotation.
        //
        // `days=0` demandait de supprimer tout journal de plus de zero jour —
        // c'est-a-dire TOUS les journaux : le registre de preuve exige par
        // l'article 5.2 du RGPD, et la trace qui permet de reperer une attaque.
        // Aucune borne n'etait posee. Un chiffre mal saisi suffisait.
        //
        // Le controle n'est pas delegue a @Min + @Validated a dessein. Cette
        // paire repose sur un proxy AOP : si @Validated manque, l'annotation est
        // purement decorative et ne protege rien, en silence. Ce depot a deja
        // connu exactement cela — 33 @PreAuthorize inertes faute de
        // @EnableReactiveMethodSecurity. Sur une operation irreversible, le
        // garde-fou doit etre dans le corps de la methode.
        if (days < 1) {
            log.warn("Purge des journaux refusee : duree de conservation demandee = {}", days);
            Map<String, String> refus = new HashMap<>();
            refus.put("error", "La duree de conservation doit etre d'au moins un jour");
            return Mono.just(ResponseEntity.badRequest().body(refus));
        }

        log.info("Admin - Nettoyage des logs de plus de {} jours", days);
        
        return authenticationLogService.cleanupOldLogs(days)
                .then(Mono.fromCallable(() -> {
                    Map<String, String> response = new HashMap<>();
                    response.put("message", "Logs nettoyés avec succès");
                    response.put("period", "Plus de " + days + " jours");
                    return ResponseEntity.ok(response);
                }));
    }
    
    private double calculateSuccessRate(int total, int failed) {
        if (total == 0) return 0.0;
        int successful = total - failed;
        return (successful * 100.0) / total;
    }
}
