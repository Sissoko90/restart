package com.abdatytch.quotas_service;

import com.abdatytch.quotas_service.repositories.UserConsumptionRepository;
import com.abdatytch.quotas_service.repositories.UserQuotaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Vérifie que le contexte Spring démarre sans infrastructure externe.
 *
 * <p>L'auto-configuration Cassandra est désactivée (voir
 * src/test/resources/application-test.yml) ; les repositories qu'elle aurait fournis
 * sont donc remplacés par des mocks. Ce test valide le câblage (filtres, sécurité,
 * contrôleurs, listener Kafka), pas la persistance — celle-ci demandera des tests
 * d'intégration Testcontainers.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuotasServiceApplicationTests {

	@MockitoBean
	private UserQuotaRepository userQuotaRepository;

	@MockitoBean
	private UserConsumptionRepository userConsumptionRepository;

	@Test
	void contextLoads() {
	}

}
