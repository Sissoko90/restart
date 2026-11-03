package com.abdatytch.user_service.metrics;

import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;

/**
 * Alimente les trois jauges de {@link UserMetrics}.
 *
 * <h2>Pourquoi un recomptage périodique</h2>
 *
 * Une jauge dit « combien y en a-t-il maintenant », pas « combien en a-t-on
 * fait » : elle ne se déduit pas des compteurs. Les trois jauges
 * {@code users.active.count}, {@code users.blocked.count} et
 * {@code users.suspended.count} étaient déclarées et <b>restaient à zéro</b> —
 * personne n'appelait les {@code setXxxCount}, parce que personne n'injectait la
 * classe qui les portait.
 *
 * <h2>Pourquoi pas une jauge à fonction de lecture</h2>
 *
 * Micrometer sait interroger une fonction à chaque relevé, ce qui serait plus
 * juste. Mais cette fonction est <b>synchrone</b> : il faudrait y bloquer sur
 * une requête SQL, et sur le fil d'exécution du relevé Prometheus. La politique
 * du projet l'interdit, et BlockHound le ferait échouer. Un recomptage
 * périodique, lui, s'abonne normalement.
 *
 * <h2>Le délai entre deux relevés</h2>
 *
 * Trente secondes par défaut : assez court pour qu'un panneau « comptes
 * bloqués » suive une décision d'administration, assez long pour que trois
 * {@code COUNT(*)} n'aient pas d'incidence. La valeur est configurable, parce
 * qu'elle dépend du volume de la table.
 *
 * <p>Un échec de comptage ne propage rien : les jauges gardent leur dernière
 * valeur, et l'erreur est journalisée. Une base indisponible doit se voir sur
 * l'état de santé du service, pas faire tomber l'export de métriques.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JaugesDesComptes {

    private final UserRepository userRepository;
    private final UserMetrics userMetrics;

    @Scheduled(fixedDelayString = "${metrics.jauges.delai-ms:30000}",
            initialDelayString = "${metrics.jauges.delai-initial-ms:10000}")
    public void recompter() {
        Mono.zip(
                        userRepository.compterParStatut(UserStatus.ACTIVE),
                        userRepository.compterParStatut(UserStatus.BLOCKED),
                        userRepository.compterParStatut(UserStatus.SUSPENDED))
                .subscribe(this::poser,
                        erreur -> log.warn("Recomptage des jauges impossible, "
                                + "les valeurs precedentes sont conservees : {}",
                                erreur.getMessage()));
    }

    /**
     * Pose les trois valeurs relevées.
     *
     * <p>{@code COUNT(*)} rend un {@code long} ; les jauges sont des entiers, et
     * la conversion est bornée plutôt que tronquée. Au-delà de deux milliards de
     * comptes la jauge sature au lieu de devenir négative — un plafond visible
     * vaut mieux qu'une valeur fausse.
     */
    private void poser(Tuple3<Long, Long, Long> comptes) {
        userMetrics.majDesJauges(
                borner(comptes.getT1()),
                borner(comptes.getT2()),
                borner(comptes.getT3()));
    }

    private static int borner(Long valeur) {
        if (valeur == null) {
            return 0;
        }
        return (int) Math.min(valeur, Integer.MAX_VALUE);
    }
}
