package com.abdatytch.user_service.config;

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
 * Configuration OpenAPI/Swagger pour User Service
 * Fournit la documentation complète de l'API de gestion des utilisateurs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI userServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("User Service API")
                        .description("""
                                # Service de Gestion des Utilisateurs
                                
                                Ce service gère toutes les opérations liées aux profils utilisateurs et à leurs données.
                                
                                ## Fonctionnalités principales
                                
                                ###Gestion des profils
                                - **Consultation** : Récupération des informations de profil
                                - **Mise à jour** : Modification des données personnelles
                                - **Suppression** : Désactivation ou suppression de compte
                                - **Recherche** : Recherche d'utilisateurs par critères
                                
                                ###Gestion des médias
                                - **Photo de profil** : Upload et gestion de la photo de profil
                                - **Documents** : Gestion des documents utilisateur
                                - **Validation** : Vérification des formats et tailles de fichiers
                                
                                ### Confidentialité
                                - **Paramètres de confidentialité** : Gestion de la visibilité du profil
                                - **Blocage** : Gestion des utilisateurs bloqués
                                - **Préférences** : Configuration des préférences utilisateur
                                
                                ### Statistiques
                                - **Activité** : Suivi de l'activité utilisateur
                                - **Historique** : Consultation de l'historique des actions
                                - **Rapports** : Génération de rapports d'utilisation
                                
                                ###Relations sociales
                                - **Amis** : Gestion des relations d'amitié
                                - **Abonnements** : Gestion des abonnements/suivis
                                - **Notifications** : Préférences de notifications
                                
                                ###Administration
                                - **Modération** : Outils de modération pour les administrateurs
                                - **Rôles** : Gestion des rôles et permissions
                                - **Audit** : Logs d'audit des actions utilisateur
                                
                                ## Sécurité
                                
                                - **JWT Bearer Token** : Authentification requise pour toutes les opérations
                                - **Autorisation** : Vérification des permissions par rôle
                                - **Validation** : Validation stricte des données entrantes
                                - **Rate Limiting** : Protection contre les abus
                                
                                ## Codes d'erreur
                                
                                - **400** : Requête invalide (données incorrectes)
                                - **401** : Non authentifié (token manquant ou invalide)
                                - **403** : Non autorisé (permissions insuffisantes)
                                - **404** : Utilisateur non trouvé
                                - **409** : Conflit (données en conflit)
                                - **413** : Fichier trop volumineux
                                - **415** : Type de média non supporté
                                - **429** : Trop de requêtes
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
                                .url("http://localhost:8083/api/v1")
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
