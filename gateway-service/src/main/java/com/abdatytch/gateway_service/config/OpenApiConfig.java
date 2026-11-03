package com.abdatytch.gateway_service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration OpenAPI/Swagger pour API Gateway
 * Point d'entrée centralisé pour la documentation de tous les microservices
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("API Gateway")
                        .description("""
                                # API Gateway - Point d'Entrée Unique
                                
                                Bienvenue dans la documentation centralisée de l'API Abdaty Technologies.
                                
                                ##Architecture Microservices
                                
                                Cette plateforme est construite sur une architecture microservices moderne, 
                                où chaque service est responsable d'un domaine métier spécifique.
                                
                                ##Services Disponibles
                                
                                ###Auth Service (Port 8082)
                                Service d'authentification et d'autorisation
                                - Inscription et connexion
                                - OAuth2 (Google, Facebook)
                                - Gestion des tokens JWT
                                - Vérification email/SMS
                                - Réinitialisation de mot de passe
                                
                                ###User Service (Port 8083)
                                Service de gestion des utilisateurs
                                - Profils utilisateurs
                                - Gestion des médias
                                - Paramètres de confidentialité
                                - Relations sociales
                                - Administration
                                
                                ###Notification Service (Port 8084)
                                Service de notifications
                                - Notifications en temps réel
                                - Emails transactionnels
                                - SMS
                                - Push notifications
                                
                                ##Sécurité
                                
                                ### Authentification
                                Toutes les requêtes (sauf les endpoints publics) nécessitent un token JWT valide.
                                
                                ```
                                Authorization: Bearer <votre_token_jwt>
                                ```
                                
                                ### Rate Limiting
                                - **Endpoints publics** : 5 requêtes/minute
                                - **Endpoints protégés** : 100 requêtes/minute
                                
                                ### CORS
                                Configuré pour accepter les requêtes depuis les origines autorisées.
                                
                                ##Démarrage Rapide
                                
                                1. **Inscription** : Créez un compte via `/api/v1/auth/register`
                                2. **Connexion** : Obtenez un token via `/api/v1/auth/login`
                                3. **Utilisation** : Incluez le token dans le header Authorization
                                
                                ##Documentation
                                
                                Utilisez le menu déroulant en haut à droite pour naviguer entre les différents services.
                                
                                ##Support
                                
                                Pour toute question ou problème, contactez-nous à contact@abdatech.ml
                                
                                ##Versions
                                
                                - **API Gateway** : v1.0.0
                                - **Auth Service** : v1.0.0
                                - **User Service** : v1.0.0
                                - **Notification Service** : v1.0.0
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
                                .url("http://localhost:8081")
                                .description("API Gateway - Développement")
                ));
    }
}
