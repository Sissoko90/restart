package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.WebConstants;
import com.abdatytch.auth_service.dto.response.UserResponse;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.mappers.UserMapper;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.IAccountErasureService;
import com.abdatytch.auth_service.service.IInternalAccountService;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent.ErasureReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Operations de {@code /internal/auth/**}.
 *
 * <p>Voir {@link IInternalAccountService} pour le pourquoi de ce service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternalAccountServiceImpl implements IInternalAccountService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final IAccountErasureService accountErasureService;

    @Override
    @Transactional(readOnly = true)
    public Mono<UserResponse> lireCompte(String userId) {
        log.info(WebConstants.LOG_INFO_INTERNAL_GET_USER, userId);

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .map(userMapper::toResponse)
                .doOnSuccess(reponse -> log.info(
                        WebConstants.LOG_INFO_INTERNAL_USER_RETRIEVED, userId));
    }

    @Override
    @Transactional
    public Mono<UUID> effacerCompte(String userId, String demandeur) {
        log.info(WebConstants.LOG_INFO_INTERNAL_DELETE_USER, userId);

        // L'existence est verifiee d'abord : requestErasure tolere un sujet
        // inconnu (il compte zero enregistrement), or le contrat de cet endpoint
        // est de signaler un identifiant inexistant a l'appelant.
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.error(WebConstants.LOG_ERROR_REQUEST_FAILED, userId);
                    return Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND));
                }))
                .flatMap(user -> accountErasureService.requestErasure(
                        userId, demandeur, ErasureReason.DEMANDE_DE_LA_PERSONNE))
                // Un effacement qui ne rend aucun identifiant de demande n'a pas
                // ete enregistre au registre. Sans ce garde-fou, le `thenReturn`
                // de l'appelant emet 204 sur un Mono vide : l'appelant considere
                // la personne effacee, et personne ne reprend le traitement.
                // C'est le pire des etats pour une operation dont la preuve est
                // exigee par l'article 5.2.
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        WebConstants.EX_EFFACEMENT_NON_ENREGISTRE + userId)))
                .doOnSuccess(requestId -> log.info(
                        WebConstants.LOG_INFO_EFFACEMENT_ENREGISTRE, userId, requestId));
    }
}
