package com.abdatytch.user_service.scheduler;

import com.abdatytch.user_service.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

/**

 * Applique les durées de conservation de user-service.
 *
 * <p>La durée était déclarée dans {@code data-retention} sans que rien ne
 * l'applique : les journaux d'audit s'accumulaient indéfiniment. Leur champ de
 * détail peut porter des données personnelles — une adresse saisie lors d'une
 * mise à jour de profil, par exemple.
 *
 * <p>Les lignes sont <b>supprimées</b> et non anonymisées : contrairement aux
 * journaux d'authentification, qui gardent une valeur antifraude une fois
 * détachés de la personne, un journal d'audit de plus de trois ans ne sert plus
 * ni à l'audit ni à rien d'autre.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class RetentionPurgeScheduler {

    private final AuditLogRepository auditLogRepository;

    @Value("${data-retention.audit-logs-days:1095}")
    private int auditLogsDays;

    /**

     * Le dimanche à 4 h : hors des heures d'activité, et décalé par rapport aux
     * purges d'auth-service pour ne pas concentrer la charge sur la base.
     */
    @Scheduled(cron = "${scheduler.cleanup.audit-logs.cron:0 0 4 * * SUN}")
    public void purgerJournauxDAudit() {
        LocalDateTime limite = LocalDateTime.now().minusDays(auditLogsDays);
        log.info(" Purge des journaux d'audit antérieurs au {}", limite);

        auditLogRepository.deleteByTimestampBefore(limite)
                .count()
                .doOnSuccess(n -> log.info(" {} journal/journaux d'audit purgé(s)", n))
                .doOnError(e -> log.error(" Purge des journaux d'audit échouée : {}",
                        e.getMessage()))
                .onErrorComplete()
                // La souscription est ce qui déclenche réellement la suppression.
                // Sans elle, le Mono est construit puis abandonné, et la purge
                // n'a jamais lieu — sans la moindre erreur pour le signaler.
                .subscribe();
    }
}
