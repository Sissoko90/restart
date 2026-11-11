package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.service.IAuthenticationLogService;
import com.abdatytch.auth_service.service.IRefreshTokenService;
import com.abdatytch.auth_service.service.IVerificationCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

/**

 * Purges périodiques des données à durée de conservation limitée.
 *
 * <h2>Le défaut que ces tâches présentaient</h2>
 *
 * Elles étaient écrites ainsi :
 *
 * <pre>{@code
 * Mono.fromRunnable(() -> authenticationLogService.cleanupOldLogs(90))
 * .subscribeOn(Schedulers.boundedElastic())
 * .subscribe();
 * }</pre>
 *
 * Le corps du {@code Runnable} <b>appelle</b> la méthode, donc construit le
 * {@code Mono} de suppression — puis le jette. Un {@code Mono} froid qui n'est
 * jamais souscrit n'exécute rien. Ce qui était souscrit, c'est le
 * {@code fromRunnable} lui-même, dont le travail se réduisait à créer un objet.
 *
 * <p><b>Conséquence.</b> Aucune des trois purges ne supprimait quoi que ce soit.
 * Codes à usage unique, jetons de rafraîchissement expirés et journaux
 * d'authentification — avec les adresses IP, agents utilisateurs et numéros de
 * téléphone qu'ils portent — s'accumulaient indéfiniment. Le message
 * « démarrage de la tâche de nettoyage » était bien écrit dans les journaux à
 * chaque exécution, ce qui donnait toutes les apparences du bon fonctionnement.
 *
 * <p>Le {@code Mono} est désormais souscrit directement. Il n'a pas besoin d'être
 * déporté sur un ordonnanceur élastique : la chaîne est réactive de bout en bout,
 * et le thread de l'ordonnanceur n'est pas un thread de l'EventLoop.
 *
 * <h2>Durées</h2>
 *
 * Elles proviennent de {@code data-retention} (voir {@code docs/COMPLIANCE.md}
 * §2) et ne sont plus écrites en dur. Une durée déclarée dans la configuration
 * mais ignorée par le code est pire qu'une durée absente : elle donne à croire
 * qu'une règle s'applique.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class ScheduledTasksConfig {

    private final IVerificationCodeService verificationCodeService;
    private final IRefreshTokenService refreshTokenService;
    private final IAuthenticationLogService authenticationLogService;

    @Value("${data-retention.authentication-logs-days:365}")
    private int authenticationLogsDays;

    /** Toutes les heures : les codes à usage unique expirent en quelques minutes. */
    @Scheduled(cron = "${scheduler.cleanup.verification-codes.cron:0 0 * * * *}")
    public void cleanupExpiredVerificationCodes() {
        souscrire("codes de vérification expirés",
                verificationCodeService.cleanupExpiredCodes());
    }

    @Scheduled(cron = "${scheduler.cleanup.refresh-tokens.cron:0 0 2 * * *}")
    public void cleanupExpiredRefreshTokens() {
        souscrire("jetons de rafraîchissement expirés",
                refreshTokenService.cleanupExpiredTokens());
    }

    /**

     * Les journaux d'authentification portent adresse IP, agent utilisateur et
     * numéro de téléphone : ce sont des données personnelles, dont la durée de
     * conservation doit être bornée et communiquée aux personnes.
     */
    @Scheduled(cron = "${scheduler.cleanup.authentication-logs.cron:0 0 3 * * SUN}")
    public void cleanupOldAuthenticationLogs() {
        souscrire("journaux d'authentification de plus de " + authenticationLogsDays + " jours",
                authenticationLogService.cleanupOldLogs(authenticationLogsDays));
    }

    /**

     * Souscrit la purge et journalise son issue.
     *
     * <p>L'échec est signalé sans être propagé : une purge ratée ne doit pas
     * interrompre l'ordonnanceur, mais elle ne doit pas non plus passer
     * inaperçue — c'est exactement ce qui s'est produit ici.
     */
    private void souscrire(String description, Mono<Void> purge) {
        log.info("Purge : {}", description);
        purge.doOnSuccess(v -> log.info("Purge terminée : {}", description))
                .doOnError(e -> log.error("Purge échouée ({}) : {}", description, e.getMessage()))
                .onErrorComplete()
                .subscribe();
    }
}
