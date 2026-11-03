package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.service.IAuthenticationLogService;
import com.abdatytch.auth_service.service.IRefreshTokenService;
import com.abdatytch.auth_service.service.IVerificationCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Purges périodiques.
 *
 * <h2>Ce que ces tests vérifient, et pourquoi c'est inhabituel</h2>
 *
 * Il ne suffit pas de vérifier que la méthode de purge est <b>appelée</b> :
 * c'était déjà le cas dans la version défectueuse. Elle était appelée, le
 * {@code Mono} était construit — puis jeté, sans jamais être souscrit. Un
 * {@code Mono} froid non souscrit n'exécute rien.
 *
 * <pre>{@code
 * // Version défectueuse : le Mono retourné est perdu
 * Mono.fromRunnable(() -> service.cleanupOldLogs(90)).subscribe();
 * }</pre>
 *
 * <p>Un {@code verify(service).cleanupOldLogs(90)} passait donc au vert sur du
 * code qui ne supprimait rien. Ces tests observent la <b>souscription</b> elle-même,
 * au moyen d'un {@code Mono} qui note son propre déclenchement.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduledTasksConfig — les purges doivent réellement s'exécuter")
class ScheduledTasksConfigTest {

    @Mock private IVerificationCodeService verificationCodeService;
    @Mock private IRefreshTokenService refreshTokenService;
    @Mock private IAuthenticationLogService authenticationLogService;

    private ScheduledTasksConfig config;

    /** Passe à `true` uniquement si le Mono de purge est souscrit. */
    private AtomicBoolean execute;

    @BeforeEach
    void setUp() {
        config = new ScheduledTasksConfig(verificationCodeService, refreshTokenService,
                authenticationLogService);
        ReflectionTestUtils.setField(config, "authenticationLogsDays", 365);

        execute = new AtomicBoolean(false);
    }

    /** Un Mono qui n'a d'effet que s'il est souscrit — le cœur de ces tests. */
    private Mono<Void> purgeObservable() {
        return Mono.fromRunnable(() -> execute.set(true));
    }

    @Test
    @DisplayName("la purge des codes de vérification est SOUSCRITE, pas seulement construite")
    void codesDeVerificationPurges() {
        when(verificationCodeService.cleanupExpiredCodes()).thenReturn(purgeObservable());

        config.cleanupExpiredVerificationCodes();

        assertThat(execute)
                .withFailMessage("le Mono de purge a été construit puis abandonné : les codes "
                        + "à usage unique s'accumulent indéfiniment")
                .isTrue();
    }

    @Test
    @DisplayName("la purge des jetons de rafraîchissement est souscrite")
    void jetonsPurges() {
        when(refreshTokenService.cleanupExpiredTokens()).thenReturn(purgeObservable());

        config.cleanupExpiredRefreshTokens();

        assertThat(execute).isTrue();
    }

    @Test
    @DisplayName("la purge des journaux d'authentification est souscrite")
    void journauxPurges() {
        // Ces journaux portent adresse IP, agent utilisateur et numéro de
        // téléphone : les laisser s'accumuler est un manquement à la limitation
        // de conservation.
        when(authenticationLogService.cleanupOldLogs(anyInt())).thenReturn(purgeObservable());

        config.cleanupOldAuthenticationLogs();

        assertThat(execute)
                .withFailMessage("les journaux d'authentification ne sont jamais purgés : "
                        + "adresses IP et numéros de téléphone s'accumulent sans limite")
                .isTrue();
    }

    @Test
    @DisplayName("la durée de conservation vient de la configuration, pas d'une valeur en dur")
    void dureeIssueDeLaConfiguration() {
        // La version précédente passait 90 en dur, alors que la configuration
        // annonçait 365 jours. Une durée déclarée mais ignorée par le code est
        // pire qu'une durée absente : elle donne à croire qu'une règle
        // s'applique.
        ReflectionTestUtils.setField(config, "authenticationLogsDays", 180);
        when(authenticationLogService.cleanupOldLogs(anyInt())).thenReturn(Mono.empty());

        config.cleanupOldAuthenticationLogs();

        verify(authenticationLogService).cleanupOldLogs(180);
    }

    @Test
    @DisplayName("une purge en échec n'interrompt pas l'ordonnanceur")
    void echecTolere() {
        // Mais elle est journalisée : une purge qui échoue en silence, c'est
        // précisément ce qui vient d'être corrigé.
        when(authenticationLogService.cleanupOldLogs(anyInt()))
                .thenReturn(Mono.error(new RuntimeException("base injoignable")));

        config.cleanupOldAuthenticationLogs();

        // Aucune exception ne remonte : l'ordonnanceur reste en état de tourner.
        verify(authenticationLogService).cleanupOldLogs(365);
    }
}
