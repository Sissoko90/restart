package com.abdatytch.auth_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.abdatytch.auth_service.repository.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Vérifie que le contexte Spring démarre sans config-server ni fichier .env.
 * Les valeurs factices sont dans src/test/resources/application-test.yml.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthServiceApplicationTests {

	// R2DBC est désactivé en test : les repositories sont remplacés par des mocks
	@MockitoBean private UserRepository userRepository;
	@MockitoBean private RoleRepository roleRepository;
	@MockitoBean private UserRoleAssignmentRepository userRoleAssignmentRepository;
	@MockitoBean private RefreshTokenRepository refreshTokenRepository;
	@MockitoBean private VerificationCodeRepository verificationCodeRepository;
	@MockitoBean private OAuthAccountRepository oAuthAccountRepository;
	@MockitoBean private BiometricCredentialRepository biometricCredentialRepository;
	@MockitoBean private AuthenticationLogRepository authenticationLogRepository;
	@MockitoBean private AppareilDeConfianceRepository appareilDeConfianceRepository;
	@MockitoBean private CodeDeSecoursRepository codeDeSecoursRepository;

	// Registre des effacements RGPD (voir docs/COMPLIANCE.md §1). Comme les
	// autres, ces repositories sont mockés : ce test vérifie le câblage du
	// contexte, pas la persistance — celle-ci l'est par
	// AuthPersistenceIntegrationTest, sur un vrai PostgreSQL.
	@MockitoBean private ErasureRequestRepository erasureRequestRepository;
	@MockitoBean private ErasureAcknowledgementRepository erasureAcknowledgementRepository;

	@Test
	void contextLoads() {
	}

}
