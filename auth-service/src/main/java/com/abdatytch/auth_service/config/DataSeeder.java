package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.constants.ConfigConstants;
import com.abdatytch.auth_service.enums.TemplateType;
import com.abdatytch.auth_service.entities.MessageTemplate;
import com.abdatytch.auth_service.repository.MessageTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
// Activé par défaut ; désactivable (tests, environnements sans base)
// via app.seeders.enabled=false
@ConditionalOnProperty(name = "app.seeders.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
@DependsOnDatabaseInitialization
public class DataSeeder implements CommandLineRunner {
    
    private final MessageTemplateRepository templateRepository;
    
    /**
     * Seeding asynchrone : on souscrit sans bloquer le démarrage. Une base
     * indisponible est journalisée mais n'empêche pas le service de démarrer.
     */
    @Override
    public void run(String... args) {
        seedMessageTemplates()
                .doOnError(error -> log.error("Échec du seeding des templates : {}", error.getMessage()))
                .onErrorResume(error -> Mono.empty())
                .subscribe();
    }

    private Mono<Void> seedMessageTemplates() {
        log.info(ConfigConstants.LOG_INFO_TEMPLATES_SEEDER_START);
        
        return Flux.concat(
            createTemplateIfNotExists(
            TemplateType.SMS_VERIFICATION,
            "Code de vérification",
            "Votre code de vérification est: {code}. Valide pendant {minutes} minutes.",
            "Template SMS pour la vérification du numéro de téléphone",
            "code, minutes"
            ),
            createTemplateIfNotExists(
            TemplateType.SMS_PASSWORD_RESET,
            "Réinitialisation mot de passe",
            "Votre code de réinitialisation est: {code}. Valide pendant {minutes} minutes.",
            "Template SMS pour la réinitialisation du mot de passe",
            "code, minutes"
            ),
            createTemplateIfNotExists(
            TemplateType.EMAIL_VERIFICATION,
            "Code de vérification",
            "Bonjour,\n\nVotre code de vérification est: {code}\n\nCe code est valide pendant {minutes} minutes.\n\nSi vous n'avez pas demandé ce code, veuillez ignorer cet email.\n\nCordialement,\nL'équipe",
            "Template Email pour la vérification de l'adresse email",
            "code, minutes"
            ),
            createTemplateIfNotExists(
            TemplateType.EMAIL_PASSWORD_RESET,
            "Réinitialisation de mot de passe",
            "Bonjour,\n\nVotre code de réinitialisation est: {code}\n\nCe code est valide pendant {minutes} minutes.\n\nSi vous n'avez pas demandé cette réinitialisation, veuillez ignorer cet email et sécuriser votre compte.\n\nCordialement,\nL'équipe",
            "Template Email pour la réinitialisation du mot de passe",
            "code, minutes"
            ),
            createTemplateIfNotExists(
            TemplateType.EMAIL_WELCOME,
            "Bienvenue sur notre plateforme",
            "Bonjour {firstName},\n\nBienvenue sur notre plateforme !\n\nVotre compte a été créé avec succès.\n\nCordialement,\nL'équipe",
            "Template Email de bienvenue après inscription",
            "firstName"
            )
        )
        .then(templateRepository.count())
        .doOnSuccess(total -> log.info(ConfigConstants.LOG_INFO_DOTENV_LOADED, total))
        .then();
    }

    private Mono<Void> createTemplateIfNotExists(TemplateType type, String subject, String content,
                                                 String description, String variables) {
        return templateRepository.existsByTemplateType(type)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.info(ConfigConstants.LOG_INFO_TEMPLATE_EXISTS, type);
                        return Mono.empty();
                    }

                    MessageTemplate template = MessageTemplate.builder()
                            .templateType(type)
                            .subject(subject)
                            .content(content)
                            .description(description)
                            .isActive(true)
                            .variables(variables)
                            .build();

                    return templateRepository.save(template)
                            .doOnSuccess(saved -> log.info(ConfigConstants.LOG_INFO_TEMPLATE_CREATED, type))
                            .then();
                });
    }
}
