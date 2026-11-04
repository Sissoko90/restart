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

    @org.springframework.beans.factory.annotation.Value("${app.contact.email:}")
    private String adresseDeContact;

    @org.springframework.beans.factory.annotation.Value("${app.contact.url:}")
    private String siteDeContact;

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
                        .contact(contact())
                        .license(new License().name("Proprietaire - Restart")))
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

    /**
     * Le contact, avec seulement les champs reellement configures.
     *
     * <p>Ils etaient ecrits en dur : une adresse electronique et deux URL. On ne
     * les corrige pas sans recompiler, et elles finissent dans la documentation
     * <b>publique</b> de l'API — une adresse perimee y invite a ecrire a
     * personne.
     *
     * <p>Le nom est toujours pose : c'est le produit, il ne depend d'aucun
     * reglage. L'adresse et l'URL ne le sont que si elles valent quelque chose.
     * Un contact vide dans un document OpenAPI est pire qu'un contact absent,
     * parce qu'il annonce un canal qui n'existe pas.
     *
     * <p>Ce bloc n'est pas factorise dans platform-commons, et c'est delibere :
     * ce module ne contient que ce dont une divergence entre services
     * <b>creerait une faille</b>. Quatre blocs de contact qui divergent
     * produisent une incoherence cosmetique, pas un defaut de securite — et l'y
     * mettre obligerait le module partage a dependre des modeles OpenAPI.
     */
    private Contact contact() {
        Contact contact = new Contact().name("Restart");
        if (adresseDeContact != null && !adresseDeContact.isBlank()) {
            contact.email(adresseDeContact);
        }
        if (siteDeContact != null && !siteDeContact.isBlank()) {
            contact.url(siteDeContact);
        }
        return contact;
    }
}
