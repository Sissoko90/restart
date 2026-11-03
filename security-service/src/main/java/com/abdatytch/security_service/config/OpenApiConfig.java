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
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI securityServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AbdaTech - Security Service")
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
                        .contact(new Contact().name("AbdaTech").email("contact@abdatech.ml"))
                        .license(new License().name("Proprietaire - AbdaTech")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Jeton emis par interface-service, role ADMIN requis")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
