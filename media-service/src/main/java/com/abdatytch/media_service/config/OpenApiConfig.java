package com.abdatytch.media_service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration OpenAPI/Swagger pour documentation API
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mediaServiceOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Media Service API")
                .description("Service de gestion des fichiers média - Architecture Réactive")
                .version("1.0.0")
                .contact(new Contact()
                    .name("Restart")
                    .email("contact@abdaty.com"))
                .license(new License()
                    .name("Apache 2.0")
                    .url("https://www.apache.org/licenses/LICENSE-2.0.html")))
            .servers(List.of(
                new Server()
                    .url("http://localhost:8087")
                    .description("Development Server"),
                new Server()
                    .url("http://localhost:8081/api/v1")
                    .description("Gateway (Production)")
            ));
    }
}
