package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.MessageTemplateRequest;
import com.abdatytch.auth_service.entities.MessageTemplate;
import com.abdatytch.auth_service.enums.TemplateType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Gestion des templates de messages (SMS et email).
 */
public interface IMessageTemplateService {

    Mono<MessageTemplate> getTemplate(TemplateType templateType);

    /**
     * Rend le contenu du template en substituant les variables {@code {clé}}.
     */
    Mono<String> formatTemplate(TemplateType templateType, Map<String, String> variables);

    Flux<MessageTemplate> getAllTemplates();

    Mono<MessageTemplate> createTemplate(MessageTemplateRequest request);

    Mono<MessageTemplate> updateTemplate(String templateId, MessageTemplateRequest request);

    Mono<Void> deleteTemplate(String templateId);
}
