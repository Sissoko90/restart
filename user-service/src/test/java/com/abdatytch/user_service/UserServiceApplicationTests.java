package com.abdatytch.user_service;

import com.abdatytch.user_service.repository.AuditLogRepository;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Le contexte Spring démarre.
 *
 * <h2>Pourquoi ce test manquait, et ce que son absence coûtait</h2>
 *
 * user-service portait soixante-quatre tests au vert, tous unitaires et montés
 * sur des simulacres. <b>Aucun</b> ne chargeait le contexte Spring. Rien ne
 * vérifiait donc que le service puisse démarrer, et une erreur de câblage —
 * un bean manquant, une propriété sans valeur, une configuration qui rend
 * {@code null} — ne se manifestait qu'au premier lancement réel.
 *
 * <p>C'est le même angle mort que celui de {@code ModuleLivrableTest} au niveau
 * de la plateforme, ici au niveau d'un service : BUILD SUCCESS ne dit rien de la
 * capacité à démarrer.
 *
 * <h2>Pourquoi les dépôts sont simulés</h2>
 *
 * R2DBC et Flyway sont exclus par {@code application-test.yml} : les tests ne
 * touchent pas la base. Aucun dépôt n'est donc créé, et ils sont remplacés
 * <b>un par un</b> ci-dessous — une liste à tenir d'accord avec le paquet
 * {@code repository}, ce que surveille {@code DepotsSimulesComplets}.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Le contexte de user-service demarre")
class UserServiceApplicationTests {

    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserProfileRepository userProfileRepository;
    @MockitoBean private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("le contexte se charge sans config-server ni fichier .env")
    void contextLoads() {
        // Le chargement EST l'assertion : un bean manquant, une propriete sans
        // valeur ou une configuration qui rend null font echouer ce test avant
        // qu'il n'atteigne son corps.
    }
}
