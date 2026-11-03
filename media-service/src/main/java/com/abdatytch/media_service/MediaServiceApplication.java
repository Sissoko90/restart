package com.abdatytch.media_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Media Service Application - Architecture Réactive Complète
 * 
 * Stack Technique:
 * - Spring Boot 4.0.1 + WebFlux (Netty)
 * - MongoDB Reactive (Spring Data Reactive MongoDB)
 * - MinIO/S3 (Stockage fichiers)
 * - Redis Reactive (Cache)
 * - Reactor Kafka (Messaging)
 * - Consul (Service Discovery)
 * 
 * Responsabilités:
 * - Upload/Download de fichiers média (images, vidéos, documents)
 * - Génération de thumbnails
 * - Gestion des métadonnées (MongoDB)
 * - Stockage distribué (MinIO/S3)
 * - Cache des métadonnées (Redis)
 * - Événements Kafka (media uploaded, deleted)
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@EnableFeignClients
public class MediaServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(MediaServiceApplication.class, args);
	}

}
