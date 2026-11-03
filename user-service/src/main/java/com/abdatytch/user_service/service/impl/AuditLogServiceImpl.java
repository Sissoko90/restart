package com.abdatytch.user_service.service.impl;

import com.abdatytch.user_service.constants.AuditConstants;
import com.abdatytch.user_service.dto.request.AuditLogCreateDTO;
import com.abdatytch.user_service.dto.response.AuditLogDTO;
import com.abdatytch.user_service.entities.AuditLog;
import com.abdatytch.user_service.enums.ActionType;
import com.abdatytch.user_service.mapper.AuditLogMapper;
import com.abdatytch.user_service.repository.AuditLogRepository;
import com.abdatytch.user_service.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Journal des décisions d'administration.
 *
 * <p>Voir {@link AuditLogService} pour ce qui a changé : les lectures paginent
 * désormais en SQL, et le journal est enfin consultable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final AuditLogMapper auditLogMapper;

    @Override
    public Mono<Void> logAction(AuditLogCreateDTO dto) {
        log.info(AuditConstants.LOG_AUDIT_ACTION,
                dto.getActionType(), dto.getAdminId(), dto.getUserId());

        if (dto.getStatus() == null) {
            dto.setStatus(AuditConstants.STATUS_SUCCESS);
        }

        AuditLog auditLog = auditLogMapper.toEntity(dto);
        return auditLogRepository.save(auditLog).then();
    }

    @Override
    public Flux<AuditLogDTO> journalDuCompte(java.util.UUID userId, int limit, int offset) {
        log.info(AuditConstants.LOG_FETCHING_USER_LOGS, userId);
        return auditLogRepository.findByUserIdWithPagination(userId, limit, offset)
                .map(auditLogMapper::toDTO);
    }

    @Override
    public Flux<AuditLogDTO> journalDunAdministrateur(String adminId, int limit, int offset) {
        log.info(AuditConstants.LOG_FETCHING_ADMIN_LOGS, adminId);
        // L'identifiant d'administrateur est une CHAINE, et non un UUID.
        //
        // L'ancienne signature prenait un `UUID` puis appelait `.toString()`
        // dessus. Or la colonne porte l'`authUserId` de l'administrateur — un
        // identifiant d'auth-service — et peut valoir « INCONNU » quand la
        // decision a lieu hors contexte de securite. Exiger un UUID rendait la
        // moitie des entrees inatteignables.
        return auditLogRepository.findByAdminIdWithPagination(adminId, limit, offset)
                .map(auditLogMapper::toDTO);
    }

    @Override
    public Flux<AuditLogDTO> journalComplet(ActionType actionType, int limit, int offset) {
        if (actionType == null) {
            log.info(AuditConstants.LOG_FETCHING_ALL_LOGS);
            return auditLogRepository.findAllWithPagination(limit, offset)
                    .map(auditLogMapper::toDTO);
        }
        log.info(AuditConstants.LOG_FETCHING_BY_ACTION, actionType);
        return auditLogRepository.findByActionTypeWithPagination(actionType, limit, offset)
                .map(auditLogMapper::toDTO);
    }
}
