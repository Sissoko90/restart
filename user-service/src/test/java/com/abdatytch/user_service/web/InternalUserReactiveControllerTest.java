package com.abdatytch.user_service.web;

import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La surface inter-services : une lecture, protégée par un secret partagé.
 *
 * <h2>Ce que ce fichier ne teste plus</h2>
 *
 * Il couvrait trois endpoints. Deux ont été supprimés, et c'est l'objet du
 * chantier :
 *
 * <ul>
 *   <li>{@code POST /internal/users} doublait l'événement Kafka
 *       {@code user.created}. auth-service appelait <b>les deux</b> dans la même
 *       méthode, et la voie Feign échouait depuis toujours — enveloppée dans un
 *       {@code catch} qui journalisait seulement, elle passait inaperçue parce
 *       que Kafka faisait le travail ;</li>
 *   <li>{@code POST /internal/users/auth/{id}/login} rendait
 *       {@code Mono.empty()} : la date de dernière connexion appartient à
 *       auth-service.</li>
 * </ul>
 *
 * <h2>Et ce qu'il teste désormais</h2>
 *
 * Le secret. La version précédente annonçait « sécurisé par JWT avec rôle
 * SERVICE » et <b>aucun test ne vérifiait un refus</b> : les trois tests
 * appelaient le contrôleur sans jeton et attendaient un succès. La protection
 * était donc documentée, absente des tests, et inopérante en production — le
 * filtre censé accorder le rôle gardait un préfixe que ce service ne sert pas.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalUserReactiveController — lecture inter-services")
class InternalUserReactiveControllerTest {

    private static final String AUTH_USER_ID = "auth-123";

    @Mock
    private UserService userService;

    @InjectMocks
    private InternalUserReactiveController controller;

    private UserDetailDTO compte;

    @BeforeEach
    void setUp() {
        compte = new UserDetailDTO(
                UUID.randomUUID(), AUTH_USER_ID, "user@example.com", "+22300000000", "ML",
                "John", "Doe", null, null,
                UserStatus.ACTIVE, UserRole.USER, null, null, null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("le profil demandé est rendu")
    void profilRendu() {
        when(userService.getUserByAuthUserId(AUTH_USER_ID)).thenReturn(Mono.just(compte));

        StepVerifier.create(controller.getUserByAuthUserId(AUTH_USER_ID))
                .assertNext(reponse -> {
                    assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(reponse.getBody()).isEqualTo(compte);
                })
                .verifyComplete();
    }

    /**
     * Ce contrôleur ne vérifie <b>plus</b> le secret, et c'est voulu.
     *
     * <p>{@code AutorisationInterne} le fait dans la chaîne de filtres, où la
     * règle couvre tout {@code /internal/**} — y compris les endpoints qui
     * n'existent pas encore. Le vérifier ici aussi ferait deux mécanismes pour
     * une même exigence ; ne le vérifier qu'ici protégeait cet endpoint et
     * laissait le suivant ouvert.
     *
     * <p>Ce test fixe cette frontière : la signature de la méthode ne doit pas
     * reprendre un en-tête de secret. {@code AutorisationInterneTest} couvre le
     * refus.
     */
    @Test
    @DisplayName("la signature ne reprend aucun en-tête de secret")
    void aucuneVerificationIci() throws Exception {
        java.lang.reflect.Method methode = InternalUserReactiveController.class
                .getMethod("getUserByAuthUserId", String.class);

        assertThat(methode.getParameterCount())
                .withFailMessage("le controleur reprend un parametre de secret : "
                        + "l'autorisation se decide dans la chaine de filtres, "
                        + "sinon elle existe a deux endroits")
                .isEqualTo(1);

        boolean porteUnEnTete = java.util.Arrays.stream(methode.getParameterAnnotations())
                .flatMap(java.util.Arrays::stream)
                .anyMatch(a -> a instanceof org.springframework.web.bind.annotation.RequestHeader);
        assertThat(porteUnEnTete).isFalse();
    }
}
