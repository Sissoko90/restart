package com.abdatytch.setting_service.service;

import com.abdatytch.setting_service.config.SettingsProperties;
import com.abdatytch.setting_service.constant.SettingsConstants;
import com.abdatytch.setting_service.entities.SystemSetting;
import com.abdatytch.setting_service.entities.UserSetting;
import com.abdatytch.setting_service.enums.SettingCategory;
import com.abdatytch.setting_service.exception.InvalidSettingValueException;
import com.abdatytch.setting_service.exception.SettingNotFoundException;
import com.abdatytch.setting_service.repositories.SystemSettingRepository;
import com.abdatytch.setting_service.repositories.UserSettingRepository;
import com.abdatytch.setting_service.service.impl.SettingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests des paramètres système et des préférences utilisateur.
 *
 * <p>Le point central : la base ne stocke que les <b>surcharges</b>. Ces tests
 * verrouillent la combinaison défaut-de-configuration + surcharge, et le fait
 * qu'une réinitialisation supprime la ligne plutôt que d'écrire la valeur d'origine.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SettingServiceImpl - Tests Unitaires")
class SettingServiceImplTest {

    /**
     * Paramètre booléen de référence.
     *
     * <p>C'était {@code system.maintenance-mode}, parti dans security-service
     * avec toute la politique de sécurité : il est remplacé par les fenêtres de
     * maintenance, qui portent un message, une durée et un service — et qui,
     * elles, coupent réellement le trafic.
     *
     * <p>Ces tests ne portent pas sur la maintenance mais sur le mécanisme de
     * surcharge : n'importe quel booléen du catalogue fait l'affaire, et
     * {@code registration-enabled} est celui qui reste.
     */
    private static final String BOOLEEN = "system.registration-enabled";

    /**
     * Paramètre de référence pour le refus d'une valeur mal typée.
     *
     * <p>C'était {@code security.max-login-attempts}, un entier. Le catalogue
     * restant n'a plus d'entier : la vérification de type porte donc sur un
     * booléen, ce qui exerce la même règle.
     */
    private static final String TYPE_REFUSE = "system.registration-enabled";
    private static final String USER_ID = "user-1";
    private static final String ADMIN = "admin-1";

    @Mock
    private SystemSettingRepository systemSettingRepository;
    @Mock
    private UserSettingRepository userSettingRepository;

    private SettingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SettingServiceImpl(
                systemSettingRepository, userSettingRepository, new SettingsProperties());

        when(systemSettingRepository.findAll()).thenReturn(Flux.empty());
        when(systemSettingRepository.save(any(SystemSetting.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(userSettingRepository.save(any(UserSetting.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    // ==========================================================
    // Paramètres système
    // ==========================================================

    @Test
    @DisplayName("sans surcharge : la valeur de configuration s'applique")
    void fallsBackToConfiguredDefault() {
        when(systemSettingRepository.findById(BOOLEEN)).thenReturn(Mono.empty());

        StepVerifier.create(service.getSystemSetting(BOOLEEN))
                .assertNext(setting -> {
                    // registration-enabled vaut true par defaut, la ou
                    // maintenance-mode valait false. La regle testee est la
                    // meme : sans surcharge, c'est le defaut du catalogue qui
                    // sort.
                    assertThat(setting.getValue()).isEqualTo("true");
                    assertThat(setting.getDefaultValue()).isEqualTo("true");
                    assertThat(setting.isOverridden()).isFalse();
                    assertThat(setting.getCategory()).isEqualTo(SettingCategory.SYSTEM);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("avec surcharge : la valeur en base l'emporte et est signalée comme telle")
    void overrideWinsOverDefault() {
        when(systemSettingRepository.findById(BOOLEEN)).thenReturn(Mono.just(
                SystemSetting.builder()
                        .settingKey(BOOLEEN)
                        // La surcharge doit DIFFERER du defaut, sinon le test
                        // ne prouve rien : une implementation qui ignorerait la
                        // surcharge passerait.
                        .settingValue("false")
                        .category(SettingCategory.SYSTEM)
                        .updatedBy(ADMIN)
                        .updatedAt(Instant.now())
                        .build()));

        StepVerifier.create(service.getSystemSetting(BOOLEEN))
                .assertNext(setting -> {
                    assertThat(setting.getValue()).isEqualTo("false");
                    assertThat(setting.getDefaultValue()).isEqualTo("true");
                    assertThat(setting.isOverridden()).isTrue();
                    assertThat(setting.getUpdatedBy()).isEqualTo(ADMIN);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("clé hors catalogue : refusée (liste blanche)")
    void unknownKeyIsRejected() {
        assertThatThrownBy(() -> service.getSystemSetting("system.inexistant"))
                .isInstanceOf(SettingNotFoundException.class);

        assertThatThrownBy(() -> service.updateSystemSetting("hack.injected", "true", ADMIN))
                .isInstanceOf(SettingNotFoundException.class);
    }

    @Test
    @DisplayName("valeur incompatible avec le type déclaré : refusée")
    void invalidValueForTypeIsRejected() {
        when(systemSettingRepository.findById(TYPE_REFUSE)).thenReturn(Mono.empty());

        StepVerifier.create(service.updateSystemSetting(TYPE_REFUSE, "beaucoup", ADMIN))
                .expectError(InvalidSettingValueException.class)
                .verify();

        verify(systemSettingRepository, never()).save(any());
    }

    @Test
    @DisplayName("un booléen n'accepte que true/false")
    void booleanTypeIsEnforced() {
        when(systemSettingRepository.findById(BOOLEEN)).thenReturn(Mono.empty());

        StepVerifier.create(service.updateSystemSetting(BOOLEEN, "oui", ADMIN))
                .expectError(InvalidSettingValueException.class)
                .verify();
    }

    @Test
    @DisplayName("première modification : crée la surcharge avec son auteur")
    void firstUpdateCreatesOverride() {
        when(systemSettingRepository.findById(BOOLEEN)).thenReturn(Mono.empty());

        StepVerifier.create(service.updateSystemSetting(BOOLEEN, "true", ADMIN))
                .assertNext(setting -> {
                    assertThat(setting.getValue()).isEqualTo("true");
                    assertThat(setting.isOverridden()).isTrue();
                })
                .verifyComplete();

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(systemSettingRepository).save(saved.capture());
        assertThat(saved.getValue().getSettingKey()).isEqualTo(BOOLEEN);
        assertThat(saved.getValue().getUpdatedBy()).isEqualTo(ADMIN);
        // Nouvelle ligne : doit produire un INSERT, pas un UPDATE sans effet
        assertThat(saved.getValue().isNew()).isTrue();
    }

    @Test
    @DisplayName("modification d'une surcharge existante : mise à jour, pas de doublon")
    void secondUpdateModifiesExistingOverride() {
        SystemSetting existing = SystemSetting.builder()
                .settingKey(BOOLEEN)
                .settingValue("true")
                .category(SettingCategory.SYSTEM)
                .updatedBy("un-autre-admin")
                .updatedAt(Instant.now().minusSeconds(3600))
                .newEntity(false)
                .build();
        when(systemSettingRepository.findById(BOOLEEN)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.updateSystemSetting(BOOLEEN, "false", ADMIN))
                .assertNext(setting -> assertThat(setting.getValue()).isEqualTo("false"))
                .verifyComplete();

        assertThat(existing.getUpdatedBy()).isEqualTo(ADMIN);
        assertThat(existing.isNew()).isFalse();
    }

    @Test
    @DisplayName("réinitialisation : supprime la surcharge et rend le défaut")
    void resetDeletesOverrideAndReturnsDefault() {
        when(systemSettingRepository.deleteBySettingKey(BOOLEEN)).thenReturn(Mono.empty());

        StepVerifier.create(service.resetSystemSetting(BOOLEEN))
                .assertNext(setting -> {
                    assertThat(setting.getValue()).isEqualTo("true");
                    assertThat(setting.isOverridden()).isFalse();
                })
                .verifyComplete();

        verify(systemSettingRepository).deleteBySettingKey(BOOLEEN);
        // La valeur d'origine n'est jamais réécrite en base
        verify(systemSettingRepository, never()).save(any());
    }

    @Test
    @DisplayName("le catalogue expose tous les paramètres, filtrables par catégorie")
    void catalogIsExposedAndFilterable() {
        StepVerifier.create(service.getAllSystemSettings())
                .expectNextCount(new SettingsProperties().catalog().size())
                .verifyComplete();

        // SECURITY puis NOTIFICATIONS ont ete RETIREES : la politique de
        // securite est portee par security-service, et les interrupteurs de
        // notification par le service qui les applique. Il ne reste qu'une
        // categorie, SYSTEM — c'est le MECANISME de filtrage qui est teste, pas
        // la categorie, donc la derniere suffit.
        //
        // Le jour ou il n'en resterait aucune, ce test deviendrait vert sans
        // rien verifier : l'assertion de comptage ci-dessus le rattrape, car un
        // catalogue vide ferait echouer getAllSystemSettings.
        StepVerifier.create(service.getSystemSettingsByCategory(SettingCategory.SYSTEM))
                .thenConsumeWhile(setting -> {
                    assertThat(setting.getCategory()).isEqualTo(SettingCategory.SYSTEM);
                    return true;
                })
                .verifyComplete();
    }

    // ==========================================================
    // Préférences utilisateur
    // ==========================================================

    @Test
    @DisplayName("préférence absente : Mono vide, pas une erreur")
    void missingUserSettingIsEmptyNotError() {
        when(userSettingRepository.findByUserIdAndSettingKey(USER_ID, "theme"))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.getUserSetting(USER_ID, "theme")).verifyComplete();
    }

    @Test
    @DisplayName("première définition : identifiant généré et ligne marquée neuve")
    void firstUserSettingGetsGeneratedId() {
        when(userSettingRepository.findByUserIdAndSettingKey(USER_ID, "theme"))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.updateUserSetting(USER_ID, "theme", "dark"))
                .assertNext(setting -> assertThat(setting.getValue()).isEqualTo("dark"))
                .verifyComplete();

        ArgumentCaptor<UserSetting> saved = ArgumentCaptor.forClass(UserSetting.class);
        verify(userSettingRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isNotBlank();
        assertThat(saved.getValue().isNew()).isTrue();
    }

    @Test
    @DisplayName("mise à jour : conserve l'identifiant, ne crée pas de doublon")
    void updatingUserSettingKeepsSameRow() {
        UserSetting existing = UserSetting.builder()
                .id("setting-1")
                .userId(USER_ID)
                .settingKey("theme")
                .settingValue("light")
                .newEntity(false)
                .build();
        when(userSettingRepository.findByUserIdAndSettingKey(USER_ID, "theme"))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service.updateUserSetting(USER_ID, "theme", "dark"))
                .assertNext(setting -> assertThat(setting.getValue()).isEqualTo("dark"))
                .verifyComplete();

        assertThat(existing.getId()).isEqualTo("setting-1");
        assertThat(existing.isNew()).isFalse();
    }

    @Test
    @DisplayName("suppression de compte : toutes les préférences sont retirées")
    void deleteAllUserSettings() {
        when(userSettingRepository.deleteByUserId(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.deleteAllUserSettings(USER_ID)).verifyComplete();

        verify(userSettingRepository).deleteByUserId(USER_ID);
    }
}
