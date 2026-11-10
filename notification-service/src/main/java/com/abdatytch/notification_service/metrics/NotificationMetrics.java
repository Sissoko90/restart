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
    /**
     * Nom volontairement prefixe {@code sms.} et non {@code notification.} :
     * c'est une depense chez un tiers, pas une remise. Expose par Micrometer
     * sous {@code sms_plafond_atteint_total}.
     */
    private static final String PLAFOND_SMS_ATTEINT = "sms.plafond.atteint.total";

    private static final String DUREE = "notification.traitement.duree";

    /** Sessions WebSocket ouvertes a cet instant. */
    private static final String SESSIONS = "notification.websocket.sessions";

    /**
     * Un canal est-il en mesure de remettre ? 1 ou 0, par canal.
     *
     * <h3>Pourquoi une metrique et pas seulement un indicateur de sante</h3>
     *
     * {@code /actuator/health} repond a qui le demande ; Prometheus ne le
     * demande pas. Sans cette jauge, « le canal PUSH est tombe » n'existe que
     * dans un journal et dans une reponse HTTP que personne n'interroge — donc
     * ni panneau, ni alerte, ni historique.
     *
     * <p>Le defaut que cela ferme a ete constate en vrai : le canal PUSH s'est
     * declare ACTIF sans pouvoir envoyer, et seul le journal de demarrage le
     * disait. Une jauge aurait montre la bascule a la seconde.
     */
    private static final String CANAL_ACTIF = "notification.canal.actif";

    /**
     * Une notification CRITIQUE a du repartir par le compte ordinaire.
     *
     * <h3>Pourquoi cette metrique existe</h3>
     *
     * Le repli SAUVE le message, et c'est precisement ce qui le rend invisible :
     * l'appelant recoit « remis », la personne recoit son courriel, et le
     * compte dedie reste casse indefiniment.
     *
     * <p>Le cas est reel. Mesure sur l'environnement de developpement le
     * 2026-11-10 : le compte critique etait REFUSE par le serveur — identifiants
     * invalides — pendant que le compte ordinaire s'authentifiait. Sans ce
     * compteur, la seule trace serait une ligne de journal par message, dans un
     * journal que personne ne lit quand tout fonctionne.
     *
     * <p>Une valeur non nulle signifie que les alertes de securite partent du
     * compte dont la reputation d'expedition n'est PAS protegee.
     */
    private static final String COURRIEL_REPLI = "notification.courriel.repli.total";

    /** Surcharges de modeles en vigueur, lues en base. */
    private static final String MODELES_SURCHARGES = "notification.modeles.surcharges";

    /**
     * Age de la derniere lecture REUSSIE des surcharges, en secondes.
     *
     * <p>{@code -1} tant qu'aucune lecture n'a abouti. La distinction compte :
     * « jamais lu » signifie que les corrections a chaud n'ont jamais
     * fonctionne, « lu il y a 40 minutes » que le rechargement s'est arrete.
     */
    private static final String MODELES_AGE_LECTURE =
            "notification.modeles.derniere.lecture.secondes";

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

    /**
     * Un envoi de SMS refuse par le plafond journalier de depense.
     *
     * <h2>Pourquoi un compteur, et pas seulement un journal en ERROR</h2>
     *
     * Les alertes de cette plateforme sont evaluees par Prometheus, sur des
     * series. Un evenement qui n'existe que sous forme de ligne de journal ne
     * declenche rien : il faut que quelqu'un lise Loki au bon moment.
     *
     * <p>Or l'evenement « plafond atteint » est exactement celui qu'on veut
     * apprendre sans le chercher : des utilisateurs ne recoivent plus leur code,
     * et la cause n'est pas une panne mais une limite voulue — donc rien
     * d'autre ne va se mettre en alerte.
     *
     * <h2>Compteur distinct de {@code notification.remises.total}</h2>
     *
     * Un refus de plafond n'est pas une remise ratee parmi d'autres. Noye dans
     * les remises, il serait indistinguable d'un WebSocket sans session ou d'un
     * canal desactive, et aucune alerte utilisable ne pourrait s'ecrire dessus.
     */
    public void plafondSmsAtteint() {
        Counter.builder(PLAFOND_SMS_ATTEINT)
                .description("Envois de SMS refuses par le plafond journalier de depense")
                .register(registre)
                .increment();
    }

    /**
     * Compte un repli du compte critique vers le compte ordinaire.
     *
     * <p>L'etiquette porte la CLASSE de l'erreur et non son message : un
     * message d'exception SMTP peut citer une adresse, et une etiquette non
     * bornee fait tomber la collecte entiere. Les classes d'exception de
     * JavaMail sont en nombre fini.
     */
    public void repliDuCompteCritique(String classeDeLErreur) {
        Counter.builder(COURRIEL_REPLI)
                .tag("cause", classeDeLErreur == null ? "inconnue" : classeDeLErreur)
                .description("Notifications CRITIQUES reparties par le compte ordinaire "
                        + "parce que le compte dedie a echoue")
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

    /**
     * Publie l'etat de chaque canal en jauge.
     *
     * <h3>Pourquoi les canaux ne sont pas injectes dans cette classe</h3>
     *
     * Ils le seraient volontiers, et ce serait un cycle : un canal peut avoir
     * besoin des metriques. L'appel vient donc de
     * {@code SurveillanceDesCanauxConfig}, qui detient les deux.
     *
     * <p>La jauge lit {@code estActif()} a chaque collecte — la MEME methode
     * que le pipeline de remise. Aucune seconde source de verite : si le canal
     * mentait, il mentirait identiquement aux deux.
     *
     * @param canaux tous les canaux declares, injectes par Spring
     */
    public void suivreCanaux(java.util.List<com.abdatytch.notification_service.service.CanalDeRemise> canaux) {
        for (var canal : canaux) {
            io.micrometer.core.instrument.Gauge
                    .builder(CANAL_ACTIF, canal, c -> c.estActif() ? 1d : 0d)
                    .tag("canal", canal.canal().name())
                    .description("1 si ce canal peut remettre, 0 sinon")
                    .register(registre);
        }
    }

    /**
     * Publie l'etat du moteur de modeles en deux jauges.
     *
     * <p>Le nombre de surcharges et l'age de la derniere lecture reussie. La
     * seconde est celle qui compte : le rechargement degrade en silence, en
     * conservant l'instantane precedent. Sans elle, une base injoignable
     * ressemble a une base ou rien n'a change.
     */
    public void suivreMoteurDeModeles(
            com.abdatytch.notification_service.service.impl.MoteurDeModeles moteur) {
        io.micrometer.core.instrument.Gauge
                .builder(MODELES_SURCHARGES, moteur, m -> m.surchargesEnVigueur().size())
                .description("Surcharges de modeles en vigueur, lues en base")
                .register(registre);

        io.micrometer.core.instrument.Gauge
                .builder(MODELES_AGE_LECTURE, moteur, m -> {
                    java.time.Instant lue = m.derniereLectureReussie();
                    return lue == null
                            ? -1d
                            : java.time.Duration.between(lue, java.time.Instant.now()).toSeconds();
                })
                .description("Secondes depuis la derniere lecture reussie des surcharges, -1 si jamais")
                .register(registre);
    }
}
