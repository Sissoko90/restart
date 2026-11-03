package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.dto.request.LogFilterRequest;
import com.abdatytch.auth_service.dto.request.AuditIpSearchRequest;
import com.abdatytch.auth_service.dto.request.AuditPhoneSearchRequest;
import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.entities.AuthenticationLog;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import com.abdatytch.auth_service.service.IAuthenticationLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping(AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH)
@RequiredArgsConstructor
@Slf4j
public class AuthenticationLogController {

    /**
     * Plafond de pagination, applique dans le corps de la methode.
     *
     * <p>Sans plafond, {@code size=2000000000} faisait construire une sous-liste
     * de la taille demandee apres avoir charge la table entiere.
     */
    private static final int TAILLE_PAGE_MAX = 500;

    private final IAuthenticationLogService logService;
    
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> getAllLogs() {
        log.info("GET {} - Récupération de tous les logs", AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH);
        return logService.getAllLogs().collectList()
            .map(ResponseEntity::ok);
    }
    
    @GetMapping(AppConstants.LOGS_USER_PATH)
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> getLogsByUserId(@PathVariable String userId) {
        log.info("GET {}/user/{} - Récupération des logs par userId", 
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH, userId);
        return logService.getLogsByUserId(userId).collectList()
            .map(ResponseEntity::ok);
    }
    
    /**
     * Journaux d'un numéro de téléphone.
     *
     * <p>Le numéro était dans le chemin de l'URL, donc dans les journaux
     * d'accès du Gateway, de tout proxy, des traces de supervision et de
     * l'historique du navigateur — des copies que l'effacement RGPD ne sait pas
     * atteindre. Il passe désormais par le corps, ce qui impose un
     * {@code POST} : une chaîne de requête serait journalisée comme le chemin.
     *
     * <p>Il ne figure plus non plus dans la journalisation applicative : l'y
     * écrire recréait le même problème dans Loki.
     */
    @PostMapping(AppConstants.LOGS_PHONE_SEARCH_PATH)
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> searchLogsByPhoneNumber(
            @Valid @RequestBody AuditPhoneSearchRequest requete) {
        log.info("POST {}{} - Recherche des journaux par numéro de téléphone",
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH,
                AppConstants.LOGS_PHONE_SEARCH_PATH);
        return logService.getLogsByPhoneNumber(requete.getPhoneNumber()).collectList()
            .map(ResponseEntity::ok);
    }
    
    @GetMapping(AppConstants.LOGS_EVENT_STATUS_PATH)
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> getLogsByEventAndStatus(
            @PathVariable EventType eventType,
            @PathVariable Status status) {
        log.info("GET {}/event/{}/status/{} - Récupération des logs par type et statut", 
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH, eventType, status);
        return logService.getLogsByEventTypeAndStatus(eventType, status).collectList()
            .map(ResponseEntity::ok);
    }
    
    @DeleteMapping(AppConstants.LOGS_CLEANUP_PATH)
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Map<String, String>>> cleanupOldLogs(
            @RequestParam(defaultValue = "90") int daysToKeep) {
        log.info("DELETE {}/cleanup - Nettoyage des logs de plus de {} jours", 
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH, daysToKeep);
        return logService.cleanupOldLogs(daysToKeep)
                .thenReturn(Map.of(
                        "message", "Logs nettoyés avec succès",
                        "daysKept", String.valueOf(daysToKeep)))
                .map(ResponseEntity::ok);
    }
    
    /**
     * Filtrage généraliste des journaux.
     *
     * <h3>Pourquoi un POST</h3>
     *
     * C'était {@code GET /filter?phoneNumber=...&ipAddress=...}. Une chaîne de
     * requête est journalisée <b>avec le chemin</b> par le Gateway, les proxys
     * et la supervision : déplacer une donnée personnelle du chemin vers un
     * paramètre ne la protège pas. Les deux valeurs étaient de surcroît écrites
     * telles quelles dans la journalisation applicative, donc dans Loki.
     *
     * <p>C'est le plus exposé des trois endpoints concernés : les deux autres
     * cherchent sur un seul critère, celui-ci est le point d'entrée généraliste.
     *
     * <h3>Plafond de pagination</h3>
     *
     * {@code size} est borné <b>dans le corps de la méthode</b>, et non laissé
     * aux seules annotations : {@code @Min}/{@code @Max} dépendent d'un proxy de
     * validation, et ce dépôt a déjà connu 33 {@code @PreAuthorize} inertes
     * faute de l'activation correspondante. Sans borne,
     * {@code size=2000000000} faisait construire une sous-liste de la taille
     * demandée.
     */
    @PostMapping("/filter")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> filterLogs(
            @Valid @RequestBody LogFilterRequest requete) {

        final EventType eventType = requete.getEventType();
        final Status status = requete.getStatus();
        final String userId = requete.getUserId();
        final String phoneNumber = requete.getPhoneNumber();
        final String ipAddress = requete.getIpAddress();
        final LocalDateTime startDate = requete.getStartDate();
        final LocalDateTime endDate = requete.getEndDate();
        final String sortDirection = requete.getSortDirection();
        final int page = Math.max(0, requete.getPage());
        final int size = Math.min(Math.max(1, requete.getSize()), TAILLE_PAGE_MAX);

        // Ni le numero de telephone ni l'adresse IP ne sont journalises : les y
        // ecrire recreerait dans Loki le probleme que ce POST vient de retirer
        // des journaux d'acces.
        log.info("POST {}/filter - Filtrage des journaux : eventType={}, status={}, "
                        + "page={}, size={}",
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH,
                eventType, status, page, size);

        return logService.getAllLogs().collectList().map(allLogs -> {
            List<AuthenticationLog> logs = new java.util.ArrayList<>(allLogs);
            
            // Filtrage
            if (eventType != null) {
                logs.removeIf(log -> log.getEventType() != eventType);
            }
            
            if (status != null) {
                logs.removeIf(log -> log.getStatus() != status);
            }
            
            if (userId != null && !userId.isEmpty()) {
                logs.removeIf(log -> !userId.equals(log.getUserId()));
            }
            
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                logs.removeIf(log -> !phoneNumber.equals(log.getPhoneNumber()));
            }
            
            if (ipAddress != null && !ipAddress.isEmpty()) {
                logs.removeIf(log -> !ipAddress.equals(log.getIpAddress()));
            }
            
            if (startDate != null) {
                logs.removeIf(log -> log.getTimestamp().isBefore(startDate));
            }
            
            if (endDate != null) {
                logs.removeIf(log -> log.getTimestamp().isAfter(endDate));
            }
            
            // Tri
            if ("DESC".equalsIgnoreCase(sortDirection)) {
                logs.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));
            } else {
                logs.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            }
            
            // Pagination
            int start = page * size;
            int end = Math.min(start + size, logs.size());
            
            if (start >= logs.size()) {
                return (List<AuthenticationLog>) new java.util.ArrayList<AuthenticationLog>();
            }
            
            return (List<AuthenticationLog>) new java.util.ArrayList<>(logs.subList(start, end));
        })
        .map(ResponseEntity::ok);
    }
    
    @GetMapping("/by-status/{status}")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> getLogsByStatus(@PathVariable Status status) {
        log.info("GET {}/by-status/{} - Récupération des logs par statut", 
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH, status);
        return logService.getAllLogs()
                .filter(log -> log.getStatus() == status)
                .collectList()
            .map(ResponseEntity::ok);
    }
    
    @GetMapping("/by-event/{eventType}")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> getLogsByEventType(@PathVariable EventType eventType) {
        log.info("GET {}/by-event/{} - Récupération des logs par type d'événement", 
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH, eventType);
        return logService.getAllLogs()
                .filter(log -> log.getEventType() == eventType)
                .collectList()
            .map(ResponseEntity::ok);
    }
    
    /**
     * Journaux issus d'une adresse IP.
     *
     * <p>Une adresse IP est une donnée personnelle — {@code AccountErasureService}
     * l'efface explicitement à ce titre. La mettre dans le chemin la versait dans
     * les journaux d'accès, hors de portée de cet effacement : le service
     * effaçait d'un côté ce qu'il publiait de l'autre.
     *
     * <p><b>Limite connue, non corrigée ici</b> : le filtrage charge
     * {@code getAllLogs()} puis trie en mémoire. Sur une table qui grossit
     * indéfiniment, cela finira par être coûteux ; il faudrait une requête
     * indexée sur {@code ip_address}. C'est un changement de dépôt, pas de
     * contrôleur, et sans rapport avec la donnée personnelle traitée ici.
     */
    @PostMapping(AppConstants.LOGS_IP_SEARCH_PATH)
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> searchLogsByIpAddress(
            @Valid @RequestBody AuditIpSearchRequest requete) {
        log.info("POST {}{} - Recherche des journaux par adresse IP",
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH,
                AppConstants.LOGS_IP_SEARCH_PATH);
        return logService.getAllLogs()
                .filter(entree -> requete.getIpAddress().equals(entree.getIpAddress()))
                .collectList()
            .map(ResponseEntity::ok);
    }
    
    @GetMapping("/by-date-range")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<List<AuthenticationLog>>> getLogsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        log.info("GET {}/by-date-range - Récupération des logs entre {} et {}", 
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH, startDate, endDate);
        return logService.getAllLogs()
                .filter(log -> (log.getTimestamp().isAfter(startDate) || log.getTimestamp().isEqual(startDate))
                        && (log.getTimestamp().isBefore(endDate) || log.getTimestamp().isEqual(endDate)))
                .collectList()
            .map(ResponseEntity::ok);
    }
    
    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Map<String, Object>>> getLogStatistics() {
        log.info("GET {}/stats - Récupération des statistiques des logs", 
                AppConstants.ADMIN_BASE_PATH + AppConstants.LOGS_PATH);
        return logService.getAllLogs().collectList().map(logs -> {
            
            long totalLogs = logs.size();
            long successCount = logs.stream().filter(log -> log.getStatus() == Status.SUCCESS).count();
            long failedCount = logs.stream().filter(log -> log.getStatus() == Status.FAILED).count();
            
            Map<EventType, Long> eventTypeCounts = logs.stream()
                .collect(Collectors.groupingBy(AuthenticationLog::getEventType, Collectors.counting()));
            
            Map<Status, Long> statusCounts = logs.stream()
                .collect(Collectors.groupingBy(AuthenticationLog::getStatus, Collectors.counting()));
            
            return Map.of(
                "totalLogs", totalLogs,
                "successCount", successCount,
                "failedCount", failedCount,
                "successRate", totalLogs > 0 ? (double) successCount / totalLogs * 100 : 0.0,
                "eventTypeCounts", eventTypeCounts,
                "statusCounts", statusCounts
            );
        })
        .map(ResponseEntity::ok);
    }
}
