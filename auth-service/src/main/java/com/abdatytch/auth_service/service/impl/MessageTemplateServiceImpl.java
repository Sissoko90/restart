package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.request.MessageTemplateRequest;
import com.abdatytch.auth_service.entities.MessageTemplate;
import com.abdatytch.auth_service.enums.TemplateType;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.MessageTemplateRepository;
import com.abdatytch.auth_service.service.IMessageTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gestion réactive des templates de messages.
 *
 * <p><b>Cache retiré.</b> Les annotations {@code @Cacheable} / {@code @CacheEvict}
 * de Spring Cache ne gèrent pas les types réactifs : elles mettraient en cache
 * l'objet {@code Mono} non souscrit plutôt que sa valeur, avec un comportement
 * dépendant de l'ordre de souscription. Les templates sont lus rarement et la
 * requête est indexée : le gain ne justifiait pas ce piège. Un cache réactif
 * explicite (Redis reactive) pourra être réintroduit si le besoin se confirme.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageTemplateServiceImpl implements IMessageTemplateService {

    private final MessageTemplateRepository templateRepository;

    @Override
    public Mono<MessageTemplate> getTemplate(TemplateType templateType) {
        log.info("Récupération du template: {}", templateType);
        return templateRepository.findByTemplateTypeAndIsActiveTrue(templateType)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.TEMPLATE_NOT_FOUND)));
    }

    @Override
    public Mono<String> formatTemplate(TemplateType templateType, Map<String, String> variables) {
        return getTemplate(templateType)
                .map(template -> {
                    String content = template.getContent();
                    for (Map.Entry<String, String> entry : variables.entrySet()) {
                        content = content.replace("{" + entry.getKey() + "}", entry.getValue());
                    }
                    return content;
                });
    }

    @Override
    public Flux<MessageTemplate> getAllTemplates() {
        log.info("Récupération de tous les templates");
        return templateRepository.findAll();
    }

    @Override
    @Transactional
    public Mono<MessageTemplate> createTemplate(MessageTemplateRequest request) {
        log.info("Création d'un nouveau template: {}", request.getTemplateType());

        return templateRepository.existsByTemplateType(request.getTemplateType())
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.error(new AuthException(ErrorCode.TEMPLATE_ALREADY_EXISTS));
                    }

                    MessageTemplate template = MessageTemplate.builder()
                            .templateType(request.getTemplateType())
                            .subject(request.getSubject())
                            .content(request.getContent())
                            .description(request.getDescription())
                            .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                            .variables(request.getVariables())
                            .build();

                    return templateRepository.save(template);
                });
    }

    @Override
    @Transactional
    public Mono<MessageTemplate> updateTemplate(String templateId, MessageTemplateRequest request) {
        log.info("Mise à jour du template: {}", templateId);

        return templateRepository.findById(templateId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.TEMPLATE_NOT_FOUND)))
                .flatMap(template -> {
                    if (request.getSubject() != null) {
                        template.setSubject(request.getSubject());
                    }
                    if (request.getContent() != null) {
                        template.setContent(request.getContent());
                    }
                    if (request.getDescription() != null) {
                        template.setDescription(request.getDescription());
                    }
                    if (request.getIsActive() != null) {
                        template.setIsActive(request.getIsActive());
                    }
                    if (request.getVariables() != null) {
                        template.setVariables(request.getVariables());
                    }

                    return templateRepository.save(template);
                });
    }

    @Override
    @Transactional
    public Mono<Void> deleteTemplate(String templateId) {
        log.info("Suppression du template: {}", templateId);

        return templateRepository.existsById(templateId)
                .flatMap(exists -> Boolean.TRUE.equals(exists)
                        ? templateRepository.deleteById(templateId)
                        : Mono.error(new AuthException(ErrorCode.TEMPLATE_NOT_FOUND)));
    }
}
