package com.abdatytch.quotas_service.erasure;

import com.abdatytch.quotas_service.repositories.UserConsumptionRepository;
import com.abdatytch.quotas_service.repositories.UserQuotaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;

/**
 * Effacement des quotas (RGPD art. 17).
 *
 * <p>Le point vérifié ici n'est pas la suppression — elle est triviale — mais le
 * <b>décompte</b>. Une suppression renvoie {@code Mono<Void>}, dont on ne peut
 * rien déduire : sans comptage préalable, le registre de conformité indiquerait
 * « 0 enregistrement effacé » pour chaque demande, et la preuve d'effacement
 * serait inexploitable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("QuotaErasureService — effacement des quotas")
class QuotaErasureServiceTest {

    private static final String PORTEUR = "developpeur-1";

    @Mock private UserQuotaRepository quotaRepository;
    @Mock private UserConsumptionRepository consumptionRepository;

    private QuotaErasureService service;

    @BeforeEach
    void setUp() {
        service = new QuotaErasureService(quotaRepository, consumptionRepository);
        when(quotaRepository.deleteById(anyString())).thenReturn(Mono.empty());
        when(consumptionRepository.deleteById(anyString())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("palier et consommation sont supprimés, et comptés")
    void suppressionEtDecompte() {
        when(quotaRepository.existsById(PORTEUR)).thenReturn(Mono.just(true));
        when(consumptionRepository.existsById(PORTEUR)).thenReturn(Mono.just(true));

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(2)
                .verifyComplete();

        verify(quotaRepository).deleteById(PORTEUR);
        verify(consumptionRepository).deleteById(PORTEUR);
    }

    @Test
    @DisplayName("un porteur sans quota donne zéro sans rien supprimer")
    void aucunQuota() {
        // Zéro est une réponse valable : elle distingue « rien à effacer » de
        // « service qui n'a jamais répondu ».
        when(quotaRepository.existsById(PORTEUR)).thenReturn(Mono.just(false));
        when(consumptionRepository.existsById(PORTEUR)).thenReturn(Mono.just(false));

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(0)
                .verifyComplete();

        verify(quotaRepository, never()).deleteById(anyString());
        verify(consumptionRepository, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("un palier sans consommation est compté une seule fois")
    void decomptePartiel() {
        when(quotaRepository.existsById(PORTEUR)).thenReturn(Mono.just(true));
        when(consumptionRepository.existsById(PORTEUR)).thenReturn(Mono.just(false));

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(1)
                .verifyComplete();
    }
}
