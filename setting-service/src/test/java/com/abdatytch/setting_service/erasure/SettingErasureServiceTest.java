package com.abdatytch.setting_service.erasure;

import com.abdatytch.setting_service.entities.UserSetting;
import com.abdatytch.setting_service.repositories.UserSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;

/**
 * Effacement des préférences (RGPD art. 17).
 *
 * <p>Leur valeur est une chaîne libre : elles peuvent contenir n'importe quelle
 * donnée personnelle. Tenter de distinguer ce qui l'est de ce qui ne l'est pas
 * reviendrait à deviner, et à se tromper au premier cas non prévu.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SettingErasureService — effacement des préférences")
class SettingErasureServiceTest {

    private static final String PORTEUR = "porteur-1";

    @Mock private UserSettingRepository repository;

    private SettingErasureService service;

    @BeforeEach
    void setUp() {
        service = new SettingErasureService(repository);
        when(repository.deleteByUserId(anyString())).thenReturn(Mono.empty());
    }

    private UserSetting preference(String cle, String valeur) {
        UserSetting setting = new UserSetting();
        setting.setUserId(PORTEUR);
        setting.setSettingKey(cle);
        setting.setSettingValue(valeur);
        return setting;
    }

    @Test
    @DisplayName("toutes les préférences sont supprimées, sans tri")
    void toutesSupprimees() {
        when(repository.findByUserId(PORTEUR)).thenReturn(Flux.just(
                preference("langue", "fr"),
                preference("contact", "awa.traore@abdatech.ml"),
                preference("theme", "sombre")));

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(3)
                .verifyComplete();

        verify(repository).deleteByUserId(PORTEUR);
    }

    @Test
    @DisplayName("un porteur sans préférence donne zéro sans appeler la suppression")
    void aucunePreference() {
        when(repository.findByUserId(PORTEUR)).thenReturn(Flux.empty());

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(0)
                .verifyComplete();

        verify(repository, never()).deleteByUserId(anyString());
    }
}
