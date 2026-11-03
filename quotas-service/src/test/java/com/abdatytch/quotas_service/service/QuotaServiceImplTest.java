package com.abdatytch.quotas_service.service;

import com.abdatytch.quotas_service.config.QuotaProperties;
import com.abdatytch.quotas_service.entities.UserConsumption;
import com.abdatytch.quotas_service.entities.UserQuota;
import com.abdatytch.quotas_service.enums.QuotaResource;
import com.abdatytch.quotas_service.enums.QuotaTier;
import com.abdatytch.quotas_service.exception.QuotaExceededException;
import com.abdatytch.quotas_service.repositories.UserConsumptionRepository;
import com.abdatytch.quotas_service.repositories.UserQuotaRepository;
import com.abdatytch.quotas_service.service.impl.QuotaServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuotaServiceImpl - Tests Unitaires")
class QuotaServiceImplTest {

    private static final String USER_ID = "user-123";

    @Mock
    private UserQuotaRepository quotaRepository;

    @Mock
    private UserConsumptionRepository consumptionRepository;

    private QuotaProperties quotaProperties;

    private QuotaServiceImpl quotaService;

    @BeforeEach
    void setUp() {
        quotaProperties = new QuotaProperties();
        // Palier FREE : 10 projets, 1000 appels/h (valeurs de config-repo)
        quotaService = new QuotaServiceImpl(quotaRepository, consumptionRepository, quotaProperties);
    }

    private void givenTier(QuotaTier tier) {
        when(quotaRepository.findById(USER_ID)).thenReturn(Mono.just(
                UserQuota.builder().userId(USER_ID).tier(tier).build()));
    }

    private void givenConsumption(UserConsumption consumption) {
        when(consumptionRepository.findById(USER_ID)).thenReturn(Mono.just(consumption));
    }

    @Test
    @DisplayName("check autorise tant que la limite n'est pas atteinte")
    void checkAllowsBelowLimit() {
        givenTier(QuotaTier.FREE);
        UserConsumption consumption = UserConsumption.emptyFor(USER_ID);
        consumption.setProjectsCount(3);
        givenConsumption(consumption);

        StepVerifier.create(quotaService.check(USER_ID, QuotaResource.PROJECTS, 1))
                .assertNext(response -> {
                    assertThat(response.isAllowed()).isTrue();
                    assertThat(response.getUsed()).isEqualTo(3);
                    assertThat(response.getLimit()).isEqualTo(10);
                    assertThat(response.getUsagePercent()).isEqualTo(30);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("check refuse quand la demande ferait dépasser la limite")
    void checkRefusesAboveLimit() {
        givenTier(QuotaTier.FREE);
        UserConsumption consumption = UserConsumption.emptyFor(USER_ID);
        consumption.setProjectsCount(10);
        givenConsumption(consumption);

        StepVerifier.create(quotaService.check(USER_ID, QuotaResource.PROJECTS, 1))
                .assertNext(response -> {
                    assertThat(response.isAllowed()).isFalse();
                    assertThat(response.getRemaining()).isZero();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("check ne modifie aucun compteur")
    void checkDoesNotPersist() {
        givenTier(QuotaTier.FREE);
        givenConsumption(UserConsumption.emptyFor(USER_ID));

        StepVerifier.create(quotaService.check(USER_ID, QuotaResource.PROJECTS, 1))
                .expectNextCount(1)
                .verifyComplete();

        verify(consumptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("le palier ENTERPRISE relève la limite sans changer le code")
    void enterpriseTierHasHigherLimit() {
        givenTier(QuotaTier.ENTERPRISE);
        UserConsumption consumption = UserConsumption.emptyFor(USER_ID);
        consumption.setProjectsCount(500);
        givenConsumption(consumption);

        StepVerifier.create(quotaService.check(USER_ID, QuotaResource.PROJECTS, 1))
                .assertNext(response -> {
                    assertThat(response.isAllowed()).isTrue();
                    assertThat(response.getLimit()).isEqualTo(1000);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("consume incrémente le compteur et le persiste")
    void consumeIncrementsCounter() {
        givenTier(QuotaTier.FREE);
        UserConsumption consumption = UserConsumption.emptyFor(USER_ID);
        consumption.setProjectsCount(2);
        givenConsumption(consumption);
        when(consumptionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(quotaService.consume(USER_ID, QuotaResource.PROJECTS, 1))
                .assertNext(response -> assertThat(response.getUsed()).isEqualTo(3))
                .verifyComplete();

        verify(consumptionRepository).save(any(UserConsumption.class));
    }

    @Test
    @DisplayName("consume lève QuotaExceededException au-delà de la limite")
    void consumeRejectsBeyondLimit() {
        givenTier(QuotaTier.FREE);
        UserConsumption consumption = UserConsumption.emptyFor(USER_ID);
        consumption.setProjectsCount(10);
        givenConsumption(consumption);

        StepVerifier.create(quotaService.consume(USER_ID, QuotaResource.PROJECTS, 1))
                .expectError(QuotaExceededException.class)
                .verify();

        verify(consumptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("release décrémente sans jamais descendre sous zéro")
    void releaseNeverGoesNegative() {
        givenTier(QuotaTier.FREE);
        UserConsumption consumption = UserConsumption.emptyFor(USER_ID);
        consumption.setProjectsCount(1);
        givenConsumption(consumption);
        when(consumptionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        // Deux libérations pour une seule création (événement rejoué)
        StepVerifier.create(quotaService.release(USER_ID, QuotaResource.PROJECTS, 5))
                .assertNext(response -> assertThat(response.getUsed()).isZero())
                .verifyComplete();
    }

    @Test
    @DisplayName("le compteur d'appels API repart de zéro à chaque nouvelle heure")
    void apiCallsWindowRolls() {
        givenTier(QuotaTier.FREE);
        UserConsumption consumption = UserConsumption.emptyFor(USER_ID);
        consumption.setApiCallsCount(999);
        // Fenêtre de l'heure précédente
        consumption.setApiCallsWindowStart(Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS));
        givenConsumption(consumption);

        StepVerifier.create(quotaService.check(USER_ID, QuotaResource.API_CALLS, 1))
                .assertNext(response -> {
                    assertThat(response.getUsed()).isZero();
                    assertThat(response.isAllowed()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("une quantité nulle ou négative est refusée")
    void rejectsNonPositiveAmount() {
        StepVerifier.create(quotaService.consume(USER_ID, QuotaResource.PROJECTS, 0))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("un utilisateur inconnu est créé au palier par défaut")
    void unknownUserGetsDefaultTier() {
        when(quotaRepository.findById(USER_ID)).thenReturn(Mono.empty());
        when(quotaRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(consumptionRepository.findById(USER_ID)).thenReturn(Mono.empty());
        when(consumptionRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(quotaService.getStatus(USER_ID))
                .assertNext(status -> {
                    assertThat(status.getTier()).isEqualTo(QuotaTier.FREE);
                    assertThat(status.getResources()).hasSize(QuotaResource.values().length);
                })
                .verifyComplete();
    }
}
