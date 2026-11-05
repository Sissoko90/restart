package com.abdatytch.notification_service.metrics;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.CompteRenduDeRemise;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Les metriques de ce service, et elles sont toutes alimentees.
 *
 * <h2>Pourquoi cette classe est ecrite avec ses appelants</h2>
 *
 * Ce depot porte le defaut inverse : {@code ProjectMetrics}, injecte dans deux
 * services et appele par aucun — neuf metriques declarees, zero emission. Une
 * classe de metriques sans appelant publie des series a zero, et un panneau
 * construit dessus montre une plateforme parfaitement calme.
 *
 * <p>{@code MetriquesDesAlertesTest} de platform-commons garde ce fait : une
 * classe de metriques que personne n'injecte fait echouer le build.
 *
 * <h2>Les etiquettes sont de cardinalite BORNEE</h2>
 *
 * {@code canal} a cinq valeurs, {@code etat} quatre, {@code emetteur} une
 * dizaine. Jamais d'identifiant de destinataire, jamais d'adresse : chaque
 * valeur distincte cree une serie temporelle permanente dans Prometheus, et une
 * etiquette non bornee fait tomber la collecte entiere — pas seulement la
 * metrique fautive.
 */
@Component
public class NotificationMetrics {

    /** Demandes recues sur le bus, avant tout traitement. */
    private static final String DEMANDES = "notification.demandes.total";

    /** Une par canal essaye : c'est la metrique centrale du service. */
    private static final String REMISES = "notification.remises.total";

    /** Demandes refusees avant traitement : doublon, gabarit inconnu. */
    private static final String REJETS = "notification.rejets.total";

    /** Aucun canal n'a abouti sur une demande CRITIQUE. */
    private static final String CRITIQUES_PERDUES = "notification.critiques.perdues.total";

    /** Duree de traitement complet d'une demande, tous canaux. */
    private static final String DUREE = "notification.traitement.duree";

    /** Sessions WebSocket ouvertes a cet instant. */
    private static final String SESSIONS = "notification.websocket.sessions";

    private final MeterRegistry registre;

    /**
     * Le compte des sessions, tenu a la main et publie en JAUGE.
     *
     * <p>Une jauge et non un compteur : le nombre de connexions monte et
     * descend. Un compteur ne sait que croitre, donc il aurait decrit le total
     * de connexions depuis le demarrage — une autre information, utile aussi,
     * mais qui ne repond pas a « combien de personnes sont en ligne ».
     *
     * <p>Micrometer ne retient qu'une reference FAIBLE vers l'objet observe :
     * conserver cet {@code AtomicInteger} en champ est ce qui empeche le
     * ramasse-miettes de le reprendre, auquel cas la jauge rendrait
     * {@code NaN}. C'est un piege classique des jauges Micrometer.
     */
    private final AtomicInteger sessionsOuvertes = new AtomicInteger(0);

    public NotificationMetrics(MeterRegistry registre) {
        this.registre = registre;
        io.micrometer.core.instrument.Gauge
                .builder(SESSIONS, sessionsOuvertes, AtomicInteger::get)
                .description("Sessions WebSocket ouvertes sur cette instance")
                .register(registre);
    }

    public void demandeRecue(String serviceEmetteur) {
        Counter.builder(DEMANDES)
                .tag("emetteur", serviceEmetteur == null ? "inconnu" : serviceEmetteur)
                .description("Demandes de notification recues")
                .register(registre)
                .increment();
    }

    /**
     * Le sort d'un canal.
     *
     * <p>Les deux etiquettes ensemble, et c'est le point : « combien de SMS
     * echouent » et « combien de remises echouent » sont deux questions, et un
     * compteur par canal sans l'etat ne repondrait ni a l'une ni a l'autre.
     */
    public void remise(CanalDeNotification canal, CompteRenduDeRemise.Etat etat) {
        Counter.builder(REMISES)
                .tag("canal", canal.name())
                .tag("etat", etat.name())
                .description("Remises par canal et par etat")
                .register(registre)
                .increment();
    }

    public void rejet(String motif) {
        Counter.builder(REJETS)
                .tag("motif", motif)
                .description("Demandes rejetees avant traitement")
                .register(registre)
                .increment();
    }

    /**
     * Une notification CRITIQUE dont aucun canal n'a abouti.
     *
     * <p>Compteur distinct, et non deduit des remises : une alerte sur
     * « des remises echouent » se declencherait sur du trafic normal — un
     * WebSocket sans session ouverte, un canal SMS desactive. Une CRITIQUE
     * entierement perdue est d'une autre nature : c'est un code de verification
     * ou une alerte de securite que personne n'a recu.
     */
    public void critiquePerdue(String serviceEmetteur) {
        Counter.builder(CRITIQUES_PERDUES)
                .tag("emetteur", serviceEmetteur == null ? "inconnu" : serviceEmetteur)
                .description("Notifications critiques dont aucun canal n'a abouti")
                .register(registre)
                .increment();
    }

    public Timer.Sample demarrerChrono() {
        return Timer.start(registre);
    }

    public void arreterChrono(Timer.Sample echantillon) {
        echantillon.stop(Timer.builder(DUREE)
                .description("Duree de traitement d'une demande, tous canaux")
                .register(registre));
    }

    public void sessionOuverte() {
        sessionsOuvertes.incrementAndGet();
    }

    public void sessionFermee() {
        sessionsOuvertes.decrementAndGet();
    }

    /** Pour les tests : l'etat courant de la jauge. */
    public int sessionsOuvertes() {
        return sessionsOuvertes.get();
    }
}
