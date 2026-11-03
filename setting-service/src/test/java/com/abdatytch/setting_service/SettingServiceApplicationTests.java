package com.abdatytch.setting_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.abdatytch.setting_service.repositories.SystemSettingRepository;
import com.abdatytch.setting_service.repositories.UserSettingRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Vérifie que le contexte Spring démarre sans infrastructure externe.
 * Voir src/test/resources/application-test.yml.
 */
@SpringBootTest
@ActiveProfiles("test")
class SettingServiceApplicationTests {

	// R2DBC est désactivé en test : les repositories sont remplacés par des mocks
	@MockitoBean private SystemSettingRepository systemSettingRepository;
	@MockitoBean private UserSettingRepository userSettingRepository;

	@Test
	void contextLoads() {
	}

}
