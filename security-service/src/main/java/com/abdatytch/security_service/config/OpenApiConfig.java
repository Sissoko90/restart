package com.abdatytch.security_service.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentation OpenAPI de security-service.
 *
 * <p>La description dit ce que le service fait <b>et ce qu'il ne fait pas</b> :
 * c'est la premiere chose qu'un integrateur a besoin de savoir pour ne pas
 * chercher ici l'application d'une regle qui est appliquee par le gateway.
 *
 * <h2>Les coordonnees de contact viennent de la configuration</h2>
 *
 * Elles etaient ecrites en dur. Une adresse electronique et une URL de site
 * dans le code sont deux choses qu'on ne peut pas corriger sans recompiler, et
 * elles finissent dans la documentation <b>publique</b> de l'API : une adresse
 * perimee y invite a ecrire a personne.
 *
 * <p>Le champ est <b>omis</b> quand la propriete est vide, et non rendu avec une
 * chaine vide : un contact vide dans un document OpenAPI est pire qu'un contact
 * absent, parce qu'il annonce un canal qui n'existe pas.
 *
 * <p>Ce bloc n'est pas factorise dans platform-commons, et c'est delibere :
 * platform-commons ne contient que ce dont une divergence entre services
 * <b>creerait une faille</b>. Quatre blocs de contact qui divergent produisent
 * une incoherence cosmetique, pas un defaut de securite — et l'y mettre
 * obligerait ce module partage a dependre des modeles OpenAPI.
 */
@Configuration
public class OpenApiConfig {

    @org.springframework.beans.factory.annotation.Value("${app.contact.email:}")
    private String adresseDeContact;

    @org.springframework.beans.factory.annotation.Value("${app.contact.url:}")
    private String siteDeContact;

    @Bean
    public OpenAPI securityServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Restart - Security Service")
                        .version("v1")
                        .description("""
                                Detenteur de la politique de securite de la plateforme.

                                **Ce service decide.** Politique typee, regles d'adresses, \
                                pays refuses, fenetres de maintenance.

                                **Ce service n'applique rien sur le chemin des requetes.** \
                                Le gateway et auth-service appliquent, en lisant la \
                                politique publiee dans Redis — jamais en appelant ce \
                                service. Un appel par requete mettrait un saut reseau sur \
                                le chemin critique, et une indisponibilite de ce service \
                                arreterait la plateforme.

                                **Toute la surface est reservee a l'administration** et \
                                passe par le Gateway, qui signe ses appels en HMAC.""")
                        .contact(contact())
                        .license(new License().name("Proprietaire - Restart")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Jeton emis par interface-service, role ADMIN requis")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    /**
     * Le contact, avec seulement les champs reellement configures.
     *
     * <p>Le nom est toujours pose — c'est le produit, il ne depend d'aucun
     * reglage. L'adresse et l'URL ne le sont que si elles valent quelque chose.
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
