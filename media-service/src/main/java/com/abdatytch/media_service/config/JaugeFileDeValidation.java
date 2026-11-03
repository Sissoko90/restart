package com.abdatytch.media_service.config;

import com.abdatytch.media_service.enums.DocumentStatus;
import com.abdatytch.media_service.repository.MediaFileRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Jauge du nombre de documents en attente de validation.
 *
 * <h2>Ce qu'elle rend visible</h2>
 *
 * Aucune metrique ne comptait la file. Un empilement signifie que personne ne
 * traite les dossiers, et donc <b>des inscriptions bloquees</b> : un chauffeur
 * qui a envoye sa piece d'identite et attend. C'est une panne silencieuse, sans
 * erreur, sans latence anormale, et sans alerte possible jusqu'ici.
 *
 * <h2>Pourquoi une valeur en memoire et non un rappel direct</h2>
 *
 * Une jauge Micrometer est <b>tiree</b> : le rappel s'execute sur le thread qui
 * sert {@code /actuator/prometheus}, a chaque scrape. Y interroger Mongo
 * demanderait un {@code block()} — soit precisement l'appel bloquant qui vient
 * d'etre retire du journal d'activite de ce module.
 *
 * <p>La valeur est donc tenue dans un {@link AtomicLong}, rafraichi par une
 * tache ordonnancee qui s'abonne a la requete reactive. Le scrape ne fait que
 * lire un {@code long}.
 *
 * <p>Consequence assumee : la valeur a l'age du dernier rafraichissement, au
 * plus l'intervalle configure. Pour une file dont l'empilement se mesure en
 * heures, c'est sans effet sur la decision.
 *
 * <h2>La definition doit rester celle de l'ecran d'administration</h2>
 *
 * Le comptage porte sur {@link DocumentStatus#UPLOADED}, le <b>meme</b> statut
 * que celui servi par {@code /admin/documents/pending}. Une jauge qui
 * compterait autre chose que ce que voit l'administrateur serait pire qu'une
 * absence de jauge : elle ferait alerter sur une file que personne ne retrouve.
 */
@Component
@Slf4j
public class JaugeFileDeValidation {

    /**
     * Micrometer ne garde qu'une reference <b>faible</b> vers l'objet mesure.
     * Ce champ est ce qui empeche la jauge de rendre {@code NaN} apres un
     * passage du ramasse-miettes.
     */
    private final AtomicLong enAttente = new AtomicLong(0L);

    private final MediaFileRepository mediaFileRepository;

    public JaugeFileDeValidation(MeterRegistry registry, MediaFileRepository mediaFileRepository) {
        this.mediaFileRepository = mediaFileRepository;

        Gauge.builder("documents.pending.validation", enAttente, AtomicLong::get)
            .description("Documents awaiting administrator validation")
            .register(registry);
    }

    /**
     * Rafraichit la valeur publiee.
     *
     * <p>{@code subscribe} depuis une tache ordonnancee ne bloque rien : la
     * requete s'execute sur les threads du pilote Mongo reactif, et cette
     * methode rend la main immediatement.
     *
     * <p>L'erreur est journalisee et avalee <b>deliberement</b> : une requete
     * ratee ne doit pas tuer l'ordonnanceur. La valeur garde alors son dernier
     * etat connu, ce qui se lit dans Prometheus comme une serie plate.
     */
    @Scheduled(fixedDelayString = "${media.metrics.file-validation.intervalle-ms:60000}")
    public void rafraichir() {
        mediaFileRepository.countByStatus(DocumentStatus.UPLOADED)
            .subscribe(
                nombre -> enAttente.set(nombre != null ? nombre : 0L),
                erreur -> log.warn("Rafraichissement de la jauge de la file de validation impossible : {}",
                    erreur.getMessage())
            );
    }
}
