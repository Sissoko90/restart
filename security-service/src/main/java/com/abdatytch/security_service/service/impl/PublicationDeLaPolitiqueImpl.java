package com.abdatytch.security_service.service.impl;

import com.abdatytch.commons.security.ClesRedisSecurite;
import com.abdatytch.security_service.config.MetriquesDeSecurite;
import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.entities.FenetreDeMaintenance;
import com.abdatytch.security_service.entities.RegleDIp;
import com.abdatytch.security_service.enums.ParametreDeSecurite;
import com.abdatytch.security_service.repositories.FenetreDeMaintenanceRepository;
import com.abdatytch.security_service.repositories.PaysBloqueRepository;
import com.abdatytch.security_service.repositories.RegleDIpRepository;
import com.abdatytch.security_service.service.IPolitiqueDeSecuriteService;
import com.abdatytch.security_service.service.IPublicationDeLaPolitique;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Publication de la politique : voir {@link IPublicationDeLaPolitique}.
 */
@Service
@Slf4j
public class PublicationDeLaPolitiqueImpl implements IPublicationDeLaPolitique {

    private final ReactiveRedisTemplate<String, String> redis;
    private final IPolitiqueDeSecuriteService politique;
    private final RegleDIpRepository reglesDIp;
    private final PaysBloqueRepository paysBloques;
    private final FenetreDeMaintenanceRepository fenetres;

    /**
     * Metriques de la politique.
     *
     * <p>Mises a jour ICI parce que la publication connait deja la liste : la
     * recalculer dans le composant de metriques demanderait une seconde lecture
     * de la base pour une valeur qu'on vient d'avoir en main.
     */
    private final MetriquesDeSecurite metriques;

    public PublicationDeLaPolitiqueImpl(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redis,
            IPolitiqueDeSecuriteService politique,
            RegleDIpRepository reglesDIp,
            PaysBloqueRepository paysBloques,
            FenetreDeMaintenanceRepository fenetres,
            MetriquesDeSecurite metriques) {
        this.redis = redis;
        this.politique = politique;
        this.reglesDIp = reglesDIp;
        this.paysBloques = paysBloques;
        this.fenetres = fenetres;
        this.metriques = metriques;
    }

    @Override
    public Mono<Integer> publierLaPolitique() {
        // La politique COMPLETE, defauts compris.
        //
        // Publier les seules surcharges obligerait chaque applicateur a
        // connaitre les defauts — donc a les porter en double, dans le gateway
        // et dans auth-service, et a les maintenir d'accord avec le catalogue.
        // Trois copies d'une meme valeur : exactement ce que ce service existe
        // pour supprimer.
        return politique.politiqueComplete()
                .flatMap(valeurs -> redis.opsForHash()
                        .putAll(ClesRedisSecurite.HACHAGE_POLITIQUE, Map.copyOf(valeurs))
                        .thenReturn(valeurs.size()))
                .doOnSuccess(nombre -> log.info(
                        SecurityConstants.LOG_POLITIQUE_PUBLIEE, nombre))
                .onErrorResume(this::redisIndisponible);
    }

    @Override
    public Mono<Integer> publierLesReglesDIp() {
        Instant maintenant = Instant.now();

        return reglesDIp.findByLeveeLeIsNull()
                .collectList()
                .flatMap(toutes -> {
                    // Les regles expirees sont RETIREES du cache, pas seulement
                    // omises. Une cle laissee en place continuerait de bloquer :
                    // une echeance ne vaut que si quelqu'un l'applique.
                    Flux<String> aRetirer = Flux.fromIterable(toutes)
                            .filter(regle -> !regle.estActive(maintenant))
                            .map(regle -> ClesRedisSecurite.PREFIXE_IP_BLOQUEE
                                    + regle.getAdresse());

                    Flux<RegleDIp> aPoser = Flux.fromIterable(toutes)
                            .filter(regle -> regle.estActive(maintenant));

                    return aRetirer.flatMap(redis::delete)
                            .then(aPoser.flatMap(this::poserLaRegle).count())
                            .map(Long::intValue);
                })
                .doOnSuccess(nombre -> log.info(SecurityConstants.LOG_REGLES_IP_PUBLIEES, nombre))
                .onErrorResume(this::redisIndisponible);
    }

    /**
     * Pose une regle dans Redis, avec la duree de vie qui correspond a son
     * echeance.
     *
     * <p>La duree de vie Redis est une <b>seconde</b> barriere, pas la
     * principale : si la republication periodique s'arrete, les regles a
     * echeance disparaissent d'elles-memes. Une regle permanente n'en a pas, et
     * c'est le seul cas ou Redis garde une cle indefiniment.
     */
    private Mono<Boolean> poserLaRegle(RegleDIp regle) {
        String cle = ClesRedisSecurite.PREFIXE_IP_BLOQUEE + regle.getAdresse();
        // Le motif est ecrit comme valeur : le filtre ne lit que l'existence de
        // la cle, mais un diagnostic depuis redis-cli sans motif ne dit rien.
        String valeur = regle.getMotif() != null ? regle.getMotif().name() : "";

        if (regle.getExpireLe() == null) {
            return redis.opsForValue().set(cle, valeur);
        }
        Duration restant = Duration.between(Instant.now(), regle.getExpireLe());
        if (restant.isNegative() || restant.isZero()) {
            // Course possible entre le filtrage et ici : la regle a expire
            // entre-temps. Ne rien poser, plutot qu'une cle a duree negative
            // que Redis refuserait.
            return Mono.just(false);
        }
        return redis.opsForValue().set(cle, valeur, restant);
    }

    @Override
    public Mono<Integer> publierLesPaysBloques() {
        return paysBloques.findByLeveeLeIsNull()
                .filter(pays -> pays.estActif())
                .map(pays -> pays.getCodeIso())
                .collectList()
                .flatMap(codes -> {
                    // Les metriques suivent la liste : l'alerte
                    // TooManyBlockedCountries et le panneau « Pays Bloques »
                    // les interrogent, et une metrique absente rend une alerte
                    // qui ne se declenche jamais.
                    metriques.majPaysRefuses(codes);

                    // L'ensemble est REMPLACE, pas complete : un pays debloque
                    // doit disparaitre. Un SADD sans suppression prealable
                    // laisserait un pays bloque pour toujours, et le debloquer
                    // paraitrait sans effet.
                    Mono<Long> purge = redis.delete(ClesRedisSecurite.ENSEMBLE_PAYS_BLOQUES);
                    if (codes.isEmpty()) {
                        return purge.thenReturn(0);
                    }
                    return purge.then(redis.opsForSet()
                                    .add(ClesRedisSecurite.ENSEMBLE_PAYS_BLOQUES,
                                            codes.toArray(String[]::new)))
                            .thenReturn(codes.size());
                })
                .doOnSuccess(nombre -> log.info(SecurityConstants.LOG_PAYS_PUBLIES, nombre))
                .onErrorResume(this::redisIndisponible);
    }

    @Override
    public Mono<Integer> publierLaMaintenance() {
        Instant maintenant = Instant.now();

        return fenetres.findByLeveeLeIsNull()
                .filter(fenetre -> fenetre.estActive(maintenant))
                .collectList()
                .flatMap(actives -> redis.delete(ClesRedisSecurite.HACHAGE_MAINTENANCE)
                        .then(Mono.defer(() -> {
                            if (actives.isEmpty()) {
                                return Mono.just(0);
                            }
                            Map<String, String> parService = new java.util.HashMap<>();
                            for (FenetreDeMaintenance fenetre : actives) {
                                // Le message et l'echeance sont publies, pas un
                                // simple drapeau : le gateway doit pouvoir les
                                // rendre a l'appelant sans appeler personne.
                                parService.put(fenetre.getService(), decrire(fenetre));
                            }
                            return redis.opsForHash()
                                    .putAll(ClesRedisSecurite.HACHAGE_MAINTENANCE,
                                            Map.copyOf(parService))
                                    .thenReturn(parService.size());
                        })))
                .doOnSuccess(nombre -> log.info(SecurityConstants.LOG_MAINTENANCE_PUBLIEE, nombre))
                .onErrorResume(this::redisIndisponible);
    }

    /**
     * Serialise une fenetre pour le cache.
     *
     * <p>Format volontairement plat — {@code message|echeance} — et non du JSON :
     * le gateway le lit sur le chemin de chaque requete, et un desserialisateur
     * JSON y ajouterait du travail pour deux champs. L'echeance vide signifie
     * « sans terme annonce ».
     */
    private static String decrire(FenetreDeMaintenance fenetre) {
        String echeance = fenetre.getFinitLe() != null ? fenetre.getFinitLe().toString() : "";
        return fenetre.getMessage() + SecurityConstants.SEPARATEUR_MAINTENANCE + echeance;
    }

    @Override
    public Mono<Void> publierTout() {
        return publierLaPolitique()
                .then(publierLesReglesDIp())
                .then(publierLesPaysBloques())
                .then(publierLaMaintenance())
                .then();
    }

    /**
     * Redis indisponible : l'erreur est journalisee et <b>avalee</b>.
     *
     * <h2>Pourquoi ne pas propager</h2>
     *
     * La publication suit une ecriture en base qui, elle, a abouti. Propager
     * ferait echouer l'appel d'administration apres que la decision a ete
     * enregistree : l'administrateur verrait une erreur, croirait son action
     * perdue, et la rejouerait — alors qu'elle est bien en base.
     *
     * <h2>Pourquoi ce n'est pas un silence</h2>
     *
     * La republication periodique ramene le cache sur la base des que Redis
     * revient. Et l'echec est compte : une alerte sur ce compteur signale
     * exactement le seul etat dangereux, celui ou la base et le cache divergent.
     */
    private <T> Mono<T> redisIndisponible(Throwable erreur) {
        log.error(SecurityConstants.LOG_PUBLICATION_ECHOUEE,
                erreur.getClass().getSimpleName());
        return Mono.empty();
    }
}
