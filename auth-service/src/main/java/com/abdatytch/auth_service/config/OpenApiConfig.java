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

    @org.springframework.beans.factory.annotation.Value("${app.contact.email:}")
    private String adresseDeContact;

    @org.springframework.beans.factory.annotation.Value("${app.contact.url:}")
    private String siteDeContact;

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
                        .contact(contact())
                        .license(new License().name("Proprietaire - Restart")))
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
