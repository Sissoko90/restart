package com.abdatytch.security_service.config;

import com.abdatytch.security_service.service.IPublicationDeLaPolitique;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

/**
 * Republication de la politique : au demarrage, puis periodiquement.
 *
 * <h2>Pourquoi le demarrage compte</h2>
 *
 * Une instance qui demarre apres une purge de Redis — redemarrage du conteneur,
 * eviction, {@code FLUSHDB} malencontreux — trouverait un cache vide. Le gateway
 * lirait alors « aucune adresse bloquee, aucun pays refuse, aucune maintenance »
 * et laisserait tout passer, <b>en silence</b> : une politique vide ressemble
 * exactement a une politique permissive.
 *
 * <h2>Pourquoi la periodicite compte</h2>
 *
 * Deux raisons, et aucune n'est la redondance :
 *
 * <ol>
 *   <li>les regles a echeance doivent <b>disparaitre</b>. Une regle expiree dont
 *       la cle reste en place continue de bloquer : une echeance ne vaut que si
 *       quelqu'un l'applique ;</li>
 *   <li>la publication immediate avale ses erreurs, pour qu'une indisponibilite
 *       de Redis ne fasse pas echouer une ecriture en base deja aboutie. Sans
 *       republication, cette tolerance laisserait le cache et la base en
 *       desaccord definitivement.</li>
 * </ol>
 *
 * <h2>Le piege deja paye dans ce depot</h2>
 *
 * {@code ScheduledTasksConfig} d'auth-service documente une purge ecrite ainsi :
 *
 * <pre>{@code
 * Mono.fromRunnable(() -> service.purger())
 *         .subscribeOn(Schedulers.boundedElastic())
 *         .subscribe();
 * }</pre>
 *
 * Le corps du {@code Runnable} <b>construit</b> le Mono puis le jette. Un Mono
 * froid jamais souscrit n'execute rien : ce qui etait souscrit, c'etait le
 * {@code fromRunnable}, dont le travail se reduisait a creer un objet. Les
 * purges ne purgeaient rien, et aucune erreur ne le signalait.
 *
 * <p>D'ou la forme retenue ici : le Mono est passe <b>en parametre</b>, donc
 * deja construit, et {@link #souscrire} le souscrit reellement.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class PublicationPlanifieeConfig {

    private final IPublicationDeLaPolitique publication;

    /**
     * Publication au demarrage.
     *
     * <p>Un {@link ApplicationRunner} et non un {@code @PostConstruct} : le
     * second tourne avant que le contexte soit complet, donc avant que la
     * connexion Redis soit prete. L'echec serait alors silencieux et
     * systematique.
     */
    @Bean
    public ApplicationRunner publicationAuDemarrage() {
        return (ApplicationArguments arguments) ->
                souscrire("publication initiale de la politique", publication.publierTout());
    }

    /**
     * Republication periodique.
     *
     * <p>Toutes les cinq minutes par defaut. La valeur est configurable parce
     * que le bon compromis depend de la duree la plus courte qu'une regle
     * puisse porter : republier moins souvent que cela laisserait une regle
     * expiree bloquer jusqu'au passage suivant.
     */
    @Scheduled(cron = "${security.publication.cron:0 */5 * * * *}")
    public void republier() {
        souscrire("republication periodique", publication.publierTout());
    }

    /**
     * Souscrit reellement, et journalise l'issue.
     *
     * <p>{@code onErrorComplete} apres les journaux : une tache planifiee dont
     * l'erreur remonte au planificateur peut, selon la configuration, cesser
     * d'etre replanifiee. Une publication en echec ne doit pas arreter les
     * suivantes — c'est precisement la suivante qui rattrapera.
     */
    private void souscrire(String description, Mono<Void> travail) {
        travail.doOnSuccess(rien -> log.info("Politique publiee : {}", description))
                .doOnError(erreur -> log.error(
                        "Publication en echec ({}) : {}",
                        description, erreur.getClass().getSimpleName()))
                .onErrorComplete()
                .subscribe();
    }
}
