package com.abdatytch.auth_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Application principale du service d'authentification
 * 
 * @EnableFeignClients : Active les clients Feign pour communication inter-services
 * @EnableKafka : Active Kafka pour publication d'événements
 *
 * Note : @EnableJpaAuditing a disparu avec JPA. L'audit (id, createdAt, updatedAt)
 * est assuré par les callbacks de {@link com.abdatytch.auth_service.config.R2dbcAuditingConfig}.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableCaching
// basePackages explicite : sans lui, Feign balaye tout le paquet de
// l'application et re-enregistre les classes @Configuration deja scannees par
// @SpringBootApplication. C'est ce qui a fait echouer le demarrage sur une
// double definition du bean superAdminSeeder, alors que le test de contexte
// passait. Le balayage est limite au paquet qui contient reellement les clients.
@EnableFeignClients(basePackages = "com.abdatytch.auth_service.client")
@EnableKafka
@EnableAsync
public class AuthServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthServiceApplication.class, args);
	}

}
