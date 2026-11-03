package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.dto.request.MessageTemplateRequest;
import com.abdatytch.auth_service.entities.MessageTemplate;
import com.abdatytch.auth_service.service.IMessageTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(AppConstants.ADMIN_BASE_PATH + AppConstants.TEMPLATES_PATH)
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
public class MessageTemplateController {
    
    private final IMessageTemplateService templateService;
    
    @GetMapping
    public Mono<ResponseEntity<List<MessageTemplate>>> getAllTemplates() {
        log.info("GET {} - Récupération de tous les templates", AppConstants.ADMIN_BASE_PATH + AppConstants.TEMPLATES_PATH);
        return templateService.getAllTemplates().collectList()
            .map(ResponseEntity::ok);
    }
    
    @PostMapping
    public Mono<ResponseEntity<MessageTemplate>> createTemplate(
            @Valid @RequestBody MessageTemplateRequest request) {
        log.info("POST {} - Création d'un template: {}", AppConstants.ADMIN_BASE_PATH + AppConstants.TEMPLATES_PATH, request.getTemplateType());
        return Mono.defer(() -> templateService.createTemplate(request))
            .map(template -> ResponseEntity.status(HttpStatus.CREATED).body(template));
    }
    
    @PutMapping("/{templateId}")
    public Mono<ResponseEntity<MessageTemplate>> updateTemplate(
            @PathVariable String templateId,
            @Valid @RequestBody MessageTemplateRequest request) {
        log.info("PUT {}/{} - Mise à jour du template", AppConstants.ADMIN_BASE_PATH + AppConstants.TEMPLATES_PATH, templateId);
        return Mono.defer(() -> templateService.updateTemplate(templateId, request))
            .map(ResponseEntity::ok);
    }
    
    @DeleteMapping("/{templateId}")
    public Mono<ResponseEntity<Map<String, String>>> deleteTemplate(@PathVariable String templateId) {
        log.info("DELETE {}/{} - Suppression du template", AppConstants.ADMIN_BASE_PATH + AppConstants.TEMPLATES_PATH, templateId);
        return templateService.deleteTemplate(templateId)
                .thenReturn(Map.of("message", "Template supprimé avec succès"))
                .map(ResponseEntity::ok);
    }
}
