package com.abdatytch.user_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Application réactive User-Service
 * - Netty comme moteur HTTP (WebFlux)
 * - R2DBC PostgreSQL pour base de données réactive
 * - Redis réactif pour cache
 * - Kafka réactif pour messaging
 * - WebClient équilibré pour les appels de service à service
 *
 * <p>Plus de {@code @EnableFeignClients} : le seul client Feign du module,
 * {@code AuthServiceClient}, a été remplacé par {@code ClientIdentiteAuth} sur
 * {@code WebClient}. Laisser l'annotation aurait gardé l'infrastructure Feign
 * en mémoire pour aucun client, et surtout laissé la porte ouverte à en
 * réintroduire un sans s'en apercevoir.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class UserServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(UserServiceApplication.class, args);
	}

}
