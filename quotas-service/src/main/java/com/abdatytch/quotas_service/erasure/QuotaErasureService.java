package com.abdatytch.quotas_service.erasure;

import com.abdatytch.quotas_service.repositories.UserConsumptionRepository;
import com.abdatytch.quotas_service.repositories.UserQuotaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**

 * Efface les données de quota rattachées à un porteur.
 *
 * <h2>Pourquoi supprimer plutôt qu'anonymiser</h2>
 *
 * Contrairement aux journaux d'authentification, les compteurs de consommation
 * n'ont aucune valeur une fois détachés de leur titulaire : un palier
 * d'abonnement sans abonné ne sert à rien. Les lignes sont donc réellement
 * supprimées.
 *
 * <p><b>Une donnée agrégée resterait légitime</b> — le total d'appels d'API du
 * mois, tous porteurs confondus, ne concerne personne en particulier. Cette
 * agrégation n'existe pas encore ; tant qu'elle n'existe pas, supprimer est le
 * comportement correct.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaErasureService {

    private final UserQuotaRepository quotaRepository;
    private final UserConsumptionRepository consumptionRepository;

    /**

     * @param subjectId identifiant du porteur — clé primaire des deux tables
     * @return le nombre de lignes effacées
     */
    public Mono<Integer> erase(String subjectId) {
        return Mono.zip(
                        supprimerSiPresent(quotaRepository.existsById(subjectId),
                                () -> quotaRepository.deleteById(subjectId)),
                        supprimerSiPresent(consumptionRepository.existsById(subjectId),
                                () -> consumptionRepository.deleteById(subjectId)))
                .map(t -> t.getT1() + t.getT2())
                .doOnSuccess(n -> log.info("{} ligne(s) de quota effacée(s) pour {}",
                        n, subjectId));
    }

    /**

     * Compte avant de supprimer : une suppression renvoie {@code Mono<Void>},
     * dont on ne peut rien déduire. Sans ce comptage, le registre indiquerait
     * toujours zéro et la preuve d'effacement serait inexploitable.
     *
     * <p>La suppression est passée en <b>fournisseur</b>, non en {@code Mono}
     * déjà construit : un argument est évalué à l'appel, donc avant même de
     * savoir si l'enregistrement existe. Le {@code Mono} n'aurait pas été
     * souscrit — rien n'aurait été supprimé — mais le code aurait exprimé le
     * contraire de son intention.
     */
    private Mono<Integer> supprimerSiPresent(Mono<Boolean> present,
                                             java.util.function.Supplier<Mono<Void>> suppression) {
        return present.flatMap(existe -> Boolean.TRUE.equals(existe)
                ? suppression.get().thenReturn(1)
                : Mono.just(0));
    }
}
