package com.abdatytch.user_service.repository;

import com.abdatytch.user_service.entities.AuditLog;
import com.abdatytch.user_service.enums.ActionType;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Dépôt réactif du journal d'audit.
 *
 * <h2>Les lectures non paginées ont été retirées</h2>
 *
 * {@code findByUserIdOrderByTimestampDesc}, {@code findByAdminIdOrderByTimestampDesc}
 * et {@code findByActionType} rendaient <b>tout</b> l'historique, et c'est
 * exactement ce que le service appelait — puis il triait et paginait en mémoire,
 * alors que les variantes paginées existaient juste à côté, inutilisées.
 *
 * <p>Sur une table d'audit, le défaut est pire qu'ailleurs : elle ne fait que
 * grossir, personne ne l'efface, et le coût d'une page augmente indéfiniment.
 * {@code findAll().sort(...)} chargeait le journal entier dans la mémoire du
 * service pour en afficher vingt lignes.
 *
 * <p>{@code findByTimestampBetween} a été retiré de la même façon : non paginé,
 * et appelé par personne.
 */
@Repository
public interface AuditLogRepository extends ReactiveCrudRepository<AuditLog, UUID> {

    /** Journal d'un compte : « qu'a-t-on décidé sur ce compte ». */
    @Query("SELECT * FROM audit_logs WHERE user_id = :userId "
            + "ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    Flux<AuditLog> findByUserIdWithPagination(UUID userId, int limit, int offset);

    /** Journal d'un administrateur : « qu'a décidé cette personne ». */
    @Query("SELECT * FROM audit_logs WHERE admin_id = :adminId "
            + "ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    Flux<AuditLog> findByAdminIdWithPagination(String adminId, int limit, int offset);

    /** Journal par nature de décision. */
    @Query("SELECT * FROM audit_logs WHERE action_type = :actionType "
            + "ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    Flux<AuditLog> findByActionTypeWithPagination(ActionType actionType, int limit, int offset);

    /** Journal complet, pour la vue d'ensemble d'une console d'administration. */
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    Flux<AuditLog> findAllWithPagination(int limit, int offset);

    /**
     * Retire le détail libre des entrées d'un compte, en <b>une</b> requête.
     *
     * <p>{@code ProfileErasureService} lisait toutes les entrées du compte puis
     * en enregistrait chacune : un {@code UPDATE} par ligne de journal, et le
     * journal d'un compte ancien en compte beaucoup. Un effacement RGPD a un
     * délai légal à respecter, pas un budget de requêtes.
     *
     * <p>{@code action_details IS NOT NULL} évite de réécrire les lignes déjà
     * anonymisées : un second effacement rend alors 0, ce qui est la vérité.
     *
     * @return le nombre de lignes effectivement anonymisées
     */
    @Modifying
    @Query("UPDATE audit_logs SET action_details = NULL, updated_at = CURRENT_TIMESTAMP "
            + "WHERE user_id = :userId AND action_details IS NOT NULL")
    Mono<Integer> anonymiserLeDetailParUserId(UUID userId);

    /**
     * Purge par ancienneté.
     *
     * <p>Une suppression en SQL, et non une lecture suivie d'un
     * {@code deleteAll(liste)} : c'est ce que faisait
     * {@code AuditLogService.cleanupOldLogs}, qui chargeait toute la table pour
     * filtrer en mémoire. {@code RetentionPurgeScheduler} appelait déjà cette
     * méthode-ci — les deux chemins de purge coexistaient, et seul le bon
     * tournait.
     */
    Flux<AuditLog> deleteByTimestampBefore(LocalDateTime cutoffDate);
}
