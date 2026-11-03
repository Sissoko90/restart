package com.abdatytch.user_service.scheduler;

import com.abdatytch.user_service.entities.AuditLog;
import com.abdatytch.user_service.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Purge des journaux d'audit.
 *
 * <p>Comme pour les purges d'auth-service, ce qui est vérifié ici est la
 * <b>souscription</b> : un {@code Flux} de suppression construit puis abandonné
 * ne supprime rien, et ne produit aucune erreur pour le signaler.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RetentionPurgeScheduler — purge des journaux d'audit")
class RetentionPurgeSchedulerTest {

    @Mock private AuditLogRepository auditLogRepository;

    private RetentionPurgeScheduler scheduler;
    private AtomicBoolean execute;

    @BeforeEach
    void setUp() {
        scheduler = new RetentionPurgeScheduler(auditLogRepository);
        ReflectionTestUtils.setField(scheduler, "auditLogsDays", 1095);
        execute = new AtomicBoolean(false);
    }

    @Test
    @DisplayName("la suppression est SOUSCRITE, pas seulement construite")
    void suppressionSouscrite() {
        when(auditLogRepository.deleteByTimestampBefore(any()))
                .thenReturn(Flux.<AuditLog>empty().doOnSubscribe(s -> execute.set(true)));

        scheduler.purgerJournauxDAudit();

        assertThat(execute)
                .withFailMessage("le Flux de suppression a été construit puis abandonné : "
                        + "les journaux d'audit s'accumulent indéfiniment")
                .isTrue();
    }

    @Test
    @DisplayName("la date limite découle de la durée configurée")
    void dateLimiteCalculee() {
        ReflectionTestUtils.setField(scheduler, "auditLogsDays", 30);
        when(auditLogRepository.deleteByTimestampBefore(any())).thenReturn(Flux.empty());

        scheduler.purgerJournauxDAudit();

        ArgumentCaptor<LocalDateTime> limite = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditLogRepository).deleteByTimestampBefore(limite.capture());

        assertThat(limite.getValue())
                .isBefore(LocalDateTime.now().minusDays(29))
                .isAfter(LocalDateTime.now().minusDays(31));
    }

    @Test
    @DisplayName("un échec de la base n'interrompt pas l'ordonnanceur")
    void echecTolere() {
        when(auditLogRepository.deleteByTimestampBefore(any()))
                .thenReturn(Flux.error(new RuntimeException("base injoignable")));

        scheduler.purgerJournauxDAudit();

        verify(auditLogRepository).deleteByTimestampBefore(any());
    }
}
