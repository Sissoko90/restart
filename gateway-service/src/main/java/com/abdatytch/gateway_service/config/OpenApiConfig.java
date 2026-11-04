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

    @org.springframework.beans.factory.annotation.Value("${app.contact.email:}")
    private String adresseDeContact;

    @org.springframework.beans.factory.annotation.Value("${app.contact.url:}")
    private String siteDeContact;

    @Bean
    public OpenAPI gatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("API Gateway")
                        .description("""
                                # API Gateway - Point d'Entrée Unique
                                
                                Bienvenue dans la documentation centralisée de l'API Restart.
                                
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
                                
                                Les coordonnées de contact figurent dans le champ `info.contact`
                                de ce document, lorsqu'elles sont configurées.
                                
                                ##Versions
                                
                                - **API Gateway** : v1.0.0
                                - **Auth Service** : v1.0.0
                                - **User Service** : v1.0.0
                                - **Notification Service** : v1.0.0
                                """
)
                        .version("1.0.0")
                        .contact(contact())
                        .license(new License().name("Proprietaire - Restart")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8081")
                                .description("API Gateway - Développement")
                ));
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
