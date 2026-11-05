package com.abdatytch.notification_service;

import com.abdatytch.notification_service.repositories.NotificationRepository;
import com.abdatytch.notification_service.repositories.RemiseParCanalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Le contexte de notification-service se charge.
 *
 * <h2>Ce que ce test prouve, et ce qu'il ne prouve pas</h2>
 *
 * Il prouve que le cablage Spring tient : les beans se construisent, aucune
 * auto-configuration ne manque une dependance, aucun {@code @Value} ne reste
 * irresolu. C'est le minimum qu'un module doit franchir avant d'etre dit
 * livrable — et ce module ne le franchissait pas, puisqu'il n'etait meme pas
 * dans le reacteur.
 *
 * <p>Il ne prouve PAS que le service demarre en vrai : {@code application-test}
 * exclut R2DBC, Flyway, le serveur de configuration et Consul. Une colonne
 * manquante ou une migration fautive resteraient invisibles ici. C'est une
 * limite assumee et deja payee ailleurs dans ce depot ; elle se leve avec
 * Testcontainers, le jour ou ce service aura des depots a exercer.
 *
 * <p>{@code @ActiveProfiles("test")} est indispensable : sans lui, le contexte
 * cherche une URL R2DBC qu'aucune configuration ne fournit en test, et echoue
 * sur « Failed to determine a suitable R2DBC Connection URL ».
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("notification-service - le contexte se charge")
class NotificationServiceApplicationTests {

	/**
	 * Les depots sont SIMULES, comme dans setting-service.
	 *
	 * <p>{@code application-test} exclut
	 * {@code R2dbcRepositoriesAutoConfiguration}, donc aucun bean de depot
	 * n'existe — et l'orchestrateur en exige deux. Sans ces deux declarations,
	 * le contexte echoue sur « No qualifying bean of type
	 * NotificationRepository ».
	 *
	 * <p>C'est la contrepartie assumee d'un test de contexte sans base : il
	 * prouve le cablage, pas le schema. Un vrai test des depots demande
	 * Testcontainers.
	 */
	@MockitoBean
	private NotificationRepository notificationRepository;

	@MockitoBean
	private RemiseParCanalRepository remiseParCanalRepository;

	@Test
	@DisplayName("le cablage Spring tient")
	void contextLoads() {
	}

}
