package com.abdatytch.interface_service;

import org.junit.jupiter.api.Test;
import com.abdatytch.interface_service.repository.ProjectRepository;
import com.abdatytch.interface_service.repository.UserRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Vérifie que le contexte Spring démarre sans infrastructure externe.
 * Les secrets sans défaut (MAINTENANCE_SECRET_KEY, admin.default.email) reçoivent
 * des valeurs factices via src/test/resources/application-test.yml.
 */
@SpringBootTest(properties = {
        // Le contexte de test ne doit pas interroger le config-server : sinon
        // l'issue du build dépend de ce qui tourne sur le poste, et les
        // valeurs de dev l'emportent sur application-test.yml.
        // Ces clés sont évaluées AVANT le chargement des fichiers de
        // configuration : elles doivent donc être posées ici, pas dans le YAML.
        "spring.cloud.config.enabled=false",
        "spring.cloud.consul.enabled=false"
})
@ActiveProfiles("test")
class InterfaceServiceApplicationTests {

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private ProjectRepository projectRepository;

	@Test
	void contextLoads() {
	}

}
