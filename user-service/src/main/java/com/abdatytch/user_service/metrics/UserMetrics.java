package com.abdatytch.user_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Métriques métier de user-service — <b>classe unique</b>.
 *
 * <h2>Pourquoi il n'y en a plus deux</h2>
 *
 * Ce service en portait deux, et c'est ce qui laissait son tableau de bord
 * désespérément plat :
 *
 * <ul>
 *   <li>{@code UserServiceMetrics} enregistrait quatorze compteurs et trois
 *       jauges dans un {@code @PostConstruct}, et <b>aucune classe ne
 *       l'injectait</b>. Les séries existaient, exportées à zéro, et n'étaient
 *       jamais incrémentées : un tableau de bord qui n'affiche rien pour une
 *       raison qui ne ressemble pas à une panne.</li>
 *   <li>{@code UserMetrics} était bien injecté — mais dans une seule classe, et
 *       il déclarait des noms <b>qui recouvraient</b> ceux de l'autre
 *       ({@code users.created}, {@code users.blocked}, {@code users.suspended})
 *       avec des étiquettes différentes. Deux séries pour un même nom : la somme
 *       affichée dépendait de l'étiquette retenue par la requête.</li>
 * </ul>
 *
 * <p>Pire : {@code blocked} et {@code suspended} étaient déclarés ici alors que
 * le blocage et la suspension se produisent dans {@code UserAdminServiceImpl},
 * qui ne portait <b>aucune</b> dépendance de métrique. Les deux compteurs ne
 * pouvaient pas bouger, où qu'on regarde.
 *
 * <h2>Les compteurs sont construits une fois</h2>
 *
 * L'ancien {@code UserMetrics} rappelait {@code Counter.builder(...).register(...)}
 * à chaque incrément. Micrometer rend alors le compteur déjà enregistré, donc
 * c'était correct — mais au prix d'une recherche dans le registre à chaque
 * événement. Ils sont désormais résolus à la construction.
 *
 * <h2>Les noms correspondent au tableau de bord</h2>
 *
 * Micrometer traduit {@code user_service.users.created} en
 * {@code user_service_users_created_total} côté Prometheus. Les noms déclarés
 * ici sont exactement ceux qu'interroge {@code user-service-dashboard.json} ;
 * {@code MetriquesDesAlertesTest} vérifie la correspondance dans les deux sens.
 */
@Component
public class UserMetrics {

    /** Étiquette commune : elle distingue les séries dans un registre partagé. */
    private static final String ETIQUETTE_SERVICE = "service";
    private static final String SERVICE = "user-service";

    private final MeterRegistry registre;

    /** Nom de la série des décisions d'administration, étiquetée par décision. */
    private static final String DECISIONS = "user_service.users.admin_decisions";
    private static final String ETIQUETTE_DECISION = "decision";

    private final Counter comptesCrees;
    private final Counter comptesModifies;
    private final Counter photosEnvoyees;
    private final Counter echecsDenvoiDePhoto;
    private final Counter evenementsKafkaTraites;
    private final Counter evenementsKafkaEchoues;
    private final Counter cacheTrouve;
    private final Counter cacheManque;

    private final Timer dureeDeCreation;

    private final AtomicInteger jaugeComptesActifs = new AtomicInteger();
    private final AtomicInteger jaugeComptesBloques = new AtomicInteger();
    private final AtomicInteger jaugeComptesSuspendus = new AtomicInteger();

    public UserMetrics(MeterRegistry registre) {
        this.registre = registre;

        this.comptesCrees = compteur("user_service.users.created",
                "Nombre de comptes crees");
        this.comptesModifies = compteur("user_service.users.updated",
                "Nombre de modifications de profil");
        this.photosEnvoyees = compteur("user_service.profile_pictures.uploaded",
                "Nombre de photos de profil enregistrees");
        this.echecsDenvoiDePhoto = compteur("user_service.profile_pictures.upload_errors",
                "Nombre d'envois de photo de profil en echec");
        this.evenementsKafkaTraites = compteur("user_service.kafka.events.processed",
                "Nombre d'evenements Kafka traites");
        this.evenementsKafkaEchoues = compteur("user_service.kafka.events.failed",
                "Nombre d'evenements Kafka en echec");
        this.cacheTrouve = compteur("user_service.cache.hits",
                "Lectures servies par le cache Redis");
        this.cacheManque = compteur("user_service.cache.misses",
                "Lectures ayant du atteindre la base");

        this.dureeDeCreation = Timer.builder("user_service.users.creation.duration")
                .description("Duree de creation d'un compte")
                .tag(ETIQUETTE_SERVICE, SERVICE)
                .publishPercentileHistogram()
                .register(registre);

        // Les jauges sont alimentees par JaugesDesComptes, qui recompte
        // periodiquement. Micrometer ne garde qu'une reference faible sur
        // l'objet mesure : les AtomicInteger sont des champs de ce bean, qui vit
        // aussi longtemps que le contexte.
        jauge("user_service.users.active.count",
                "Comptes vivants", jaugeComptesActifs);
        jauge("user_service.users.blocked.count",
                "Comptes bloques", jaugeComptesBloques);
        jauge("user_service.users.suspended.count",
                "Comptes suspendus", jaugeComptesSuspendus);
    }

    private Counter compteur(String nom, String description) {
        return Counter.builder(nom)
                .description(description)
                .tag(ETIQUETTE_SERVICE, SERVICE)
                .register(registre);
    }

    private void jauge(String nom, String description, AtomicInteger valeur) {
        io.micrometer.core.instrument.Gauge.builder(nom, valeur, AtomicInteger::get)
                .description(description)
                .tag(ETIQUETTE_SERVICE, SERVICE)
                .register(registre);
    }

    public void compteCree() {
        comptesCrees.increment();
    }

    public void compteModifie() {
        comptesModifies.increment();
    }

    /**
     * Compte une décision d'administration, étiquetée par sa nature.
     *
     * <p>Une seule série étiquetée plutôt que cinq compteurs distincts : les
     * cinq décisions se comparent et se somment, et une série par décision
     * obligerait à retoucher le tableau de bord à chaque décision ajoutée. Elles
     * partagent aussi la même question — « combien de décisions sur les
     * dernières 24 h » — à laquelle un {@code sum by (decision)} répond d'un
     * trait.
     *
     * <p>Le compteur est résolu à l'appel, et non à la construction : l'étiquette
     * en fait une série par valeur. Micrometer rend celui déjà enregistré, donc
     * le coût est une recherche dans le registre — acceptable sur une décision
     * d'administration, qui est rare par nature.
     *
     * @param decision valeur de l'étiquette, prise dans
     *                 {@code AdminConstants.DECISION_*} pour qu'une faute de
     *                 frappe ne crée pas une série fantôme
     */
    public void decisionPrise(String decision) {
        Counter.builder(DECISIONS)
                .description("Decisions d'administration sur les comptes")
                .tag(ETIQUETTE_SERVICE, SERVICE)
                .tag(ETIQUETTE_DECISION, decision)
                .register(registre)
                .increment();
    }

    public void photoEnvoyee() {
        photosEnvoyees.increment();
    }

    public void envoiDePhotoEnEchec() {
        echecsDenvoiDePhoto.increment();
    }

    public void evenementKafkaTraite() {
        evenementsKafkaTraites.increment();
    }

    public void evenementKafkaEnEchec() {
        evenementsKafkaEchoues.increment();
    }

    public void lectureServieParLeCache() {
        cacheTrouve.increment();
    }

    public void lectureAbsenteDuCache() {
        cacheManque.increment();
    }

    /** Démarre la mesure de durée d'une création de compte. */
    public Timer.Sample debutDeCreation() {
        return Timer.start(registre);
    }

    /** Clôt la mesure ouverte par {@link #debutDeCreation()}. */
    public void finDeCreation(Timer.Sample mesure) {
        mesure.stop(dureeDeCreation);
    }

    public void majDesJauges(int actifs, int bloques, int suspendus) {
        jaugeComptesActifs.set(actifs);
        jaugeComptesBloques.set(bloques);
        jaugeComptesSuspendus.set(suspendus);
    }
}
