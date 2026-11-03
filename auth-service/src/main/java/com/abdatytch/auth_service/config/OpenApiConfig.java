package com.abdatytch.auth_service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration OpenAPI/Swagger pour Auth Service
 * Fournit la documentation complète de l'API d'authentification
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI authServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Auth Service API")
                        .description("""
                                # Service d'Authentification et d'Autorisation
                                
                                Ce service gère toutes les opérations liées à l'authentification et à l'autorisation des utilisateurs.
                                
                                ## Fonctionnalités principales
                                
                                ###Authentification
                                - **Inscription** : Création de nouveaux comptes utilisateurs
                                - **Connexion** : Authentification par email/téléphone et mot de passe
                                - **OAuth2** : Connexion via Google et Facebook
                                - **Tokens JWT** : Génération et validation de tokens d'accès
                                - **Refresh Tokens** : Renouvellement des tokens expirés
                                
                                ### Vérification
                                - **Email** : Envoi et validation de codes de vérification par email
                                - **SMS** : Envoi et validation de codes OTP par SMS
                                - **Codes de vérification** : Gestion des codes à usage unique
                                
                                ###Gestion des mots de passe
                                - **Réinitialisation** : Processus de récupération de mot de passe
                                - **Changement** : Modification du mot de passe actuel
                                - **Validation** : Vérification de la force des mots de passe
                                
                                ###Profils OAuth2
                                - **Complétion de profil** : Finalisation des informations après connexion OAuth2
                                - **Liaison de comptes** : Association de comptes OAuth2 existants
                                
                                ## Sécurité
                                
                                - **JWT Bearer Token** : Authentification par token JWT dans le header Authorization
                                - **Rate Limiting** : Protection contre les attaques par force brute
                                - **CORS** : Configuration pour les appels cross-origin
                                - **Validation** : Validation stricte des entrées utilisateur
                                
                                ## Codes d'erreur
                                
                                - **400** : Requête invalide (données manquantes ou incorrectes)
                                - **401** : Non authentifié (token manquant ou invalide)
                                - **403** : Non autorisé (permissions insuffisantes)
                                - **404** : Ressource non trouvée
                                - **409** : Conflit (email/téléphone déjà utilisé)
                                - **429** : Trop de requêtes (rate limit dépassé)
                                - **500** : Erreur serveur interne
                                """
)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Abdaty Technologies")
                                .email("contact@abdatech.ml")
                                .url("https://abdatytch.com"))
                        .license(new License()
                                .name("Propriétaire")
                                .url("https://abdatytch.com/license")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8082/api/v1")
                                .description("Serveur de développement local"),
                        new Server()
                                .url("http://localhost:8081/api/v1")
                                .description("Via API Gateway (développement)")
                ))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new io.swagger.v3.oas.models.Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Entrez le token JWT. Format: Bearer {token}")));
    }
}
