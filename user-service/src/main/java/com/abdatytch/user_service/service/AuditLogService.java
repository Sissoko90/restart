package com.abdatytch.user_service.service;

import com.abdatytch.user_service.dto.request.AuditLogCreateDTO;
import com.abdatytch.user_service.dto.response.AuditLogDTO;
import com.abdatytch.user_service.enums.ActionType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Journal des décisions d'administration : écriture et consultation.
 *
 * <h2>Le journal était écrit et illisible</h2>
 *
 * {@code logAction} était appelé ; les quatre méthodes de lecture n'étaient
 * appelées par personne, et <b>aucun endpoint</b> n'exposait le journal. Un
 * journal d'audit existe pour répondre à « qui a décidé quoi, quand » — celui-ci
 * accumulait la réponse sans que quiconque puisse la poser.
 *
 * <p>C'est la forme la plus coûteuse du défaut dominant de ce dépôt : non pas
 * une protection absente, mais une protection qui fonctionne et dont le résultat
 * n'atteint personne. Un registre inconsultable ne vaut pas mieux qu'un registre
 * vide, et il coûte une table qui grossit.
 *
 * <h2>Les lectures paginent en SQL</h2>
 *
 * Les quatre anciennes lectures demandaient <b>tout</b> l'historique au dépôt
 * puis triaient et paginaient en mémoire — {@code findAll().sort(...)} pour l'une
 * d'elles — alors que les variantes paginées existaient juste à côté, inutilisées.
 * Sur une table d'audit, qui ne fait que grossir, le coût d'une page augmentait
 * indéfiniment.
 *
 * <h2>Ce qui a été retiré</h2>
 *
 * {@code cleanupOldLogs} chargeait la table entière, filtrait en mémoire puis
 * appelait {@code deleteAll(liste)}. La purge appartient à
 * {@code RetentionPurgeScheduler}, qui appelle déjà
 * {@code deleteByTimestampBefore} — une suppression en SQL. Deux chemins de
 * purge coexistaient, et seul le bon tournait.
 */
public interface AuditLogService {

    /** Consigne une décision. Un échec d'écriture ne doit pas annuler la décision. */
    Mono<Void> logAction(AuditLogCreateDTO dto);

    /** « Qu'a-t-on décidé sur ce compte », du plus récent au plus ancien. */
    Flux<AuditLogDTO> journalDuCompte(UUID userId, int limit, int offset);

    /** « Qu'a décidé cet administrateur ». */
    Flux<AuditLogDTO> journalDunAdministrateur(String adminId, int limit, int offset);

    /** « Que s'est-il passé », éventuellement filtré par nature de décision. */
    Flux<AuditLogDTO> journalComplet(ActionType actionType, int limit, int offset);
}
