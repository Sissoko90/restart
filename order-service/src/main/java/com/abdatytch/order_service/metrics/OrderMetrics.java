package com.abdatytch.order_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Les metriques des commandes.
 *
 * <h2>Les compteurs sont crees a ZERO au demarrage</h2>
 *
 * « Aucune compensation echouee » et « la metrique n'existe pas » se
 * ressemblent beaucoup trop, et c'est precisement sur zero qu'on veut pouvoir
 * alerter. Une alerte posee sur une serie inexistante reste verte pour
 * toujours, et sa verdeur se lit comme une absence d'incident.
 *
 * <h2>Le chronometre publie un HISTOGRAMME</h2>
 *
 * Un {@code Timer} ordinaire publie {@code _count}, {@code _sum} et
 * {@code _max}, et AUCUN {@code _bucket}. Or {@code histogram_quantile} ne
 * travaille que sur des buckets : sans
 * {@code publishPercentileHistogram}, le panneau de centiles affiche
 * « No data » et l'alerte qui en depend ne se declenche jamais.
 *
 * <p>Il est enregistre UNE fois, dans le constructeur, et retenu en champ.
 * Micrometer rend le meter deja enregistre pour un identifiant donne et IGNORE
 * la configuration du second appel : deux {@code Timer.builder} dans la meme
 * classe feraient gagner le premier, sans la moindre erreur pour le dire.
 *
 * <h2>Aucune etiquette ne porte l'identifiant d'une personne</h2>
 *
 * Ni l'acheteur, ni la boutique, ni le numero de commande. Une metrique est
 * conservee longtemps et exportee largement : y inscrire qui a commande en
 * ferait un journal d'activite personnel, hors de toute duree de conservation
 * et non effacable. La cardinalite serait de surcroit illimitee.
 *
 * <p>Qui a commande est une question d'AUDIT, et le registre des transitions
 * existe pour cela.
 */
@Component
public class OrderMetrics {

    /** Les commandes creees. */
    public static final String COMMANDES_CREEES = "order.commandes.creees.total";

    /**
     * Les transitions rendues, par etat d'arrivee et par role.
     *
     * <p>Etiquetee par l'ETAT et le ROLE, qui sont tous deux des ensembles
     * fermes et petits : neuf etats, quatre roles. Une etiquette bornee est
     * utile ; une etiquette libre fait exploser le nombre de series.
     */
    public static final String TRANSITIONS = "order.transitions.total";

    /**
     * Les compensations de stock ECHOUEES.
     *
     * <p>La metrique la plus importante de ce module. Elle compte les fois ou
     * du stock est reste retenu chez market-service sans commande en face :
     * invisible, et introuvable sans inventaire. C'est la limite connue du
     * modele a deux bases, et la seule chose qui la rende gerable est de la
     * compter.
     */
    public static final String COMPENSATIONS_ECHOUEES = "order.stock.compensation.echouee.total";

    /** Les articles refuses faute de stock au passage en caisse. */
    public static final String REFUS_DE_STOCK = "order.caisse.refus_de_stock.total";

    /** Les retours, par sens de decision. */
    public static final String RETOURS = "order.retours.total";

    /** Le temps d'un passage en caisse, bout en bout. */
    public static final String DUREE_CAISSE = "order.caisse.duree";

    /** Les files que Sankadi doit traiter. */
    public static final String FILES = "order.files";

    private final MeterRegistry registre;
    private final Timer dureeCaisse;

    private final AtomicInteger commandesAPayer = new AtomicInteger();
    private final AtomicInteger retoursAArbitrer = new AtomicInteger();
    private final AtomicInteger commandesACloturer = new AtomicInteger();

    public OrderMetrics(MeterRegistry registre) {
        this.registre = registre;

        registre.gauge(FILES, List.of(Tag.of("nature", "commandes_a_payer")),
                commandesAPayer, AtomicInteger::get);
        registre.gauge(FILES, List.of(Tag.of("nature", "retours_a_arbitrer")),
                retoursAArbitrer, AtomicInteger::get);
        registre.gauge(FILES, List.of(Tag.of("nature", "commandes_a_cloturer")),
                commandesACloturer, AtomicInteger::get);

        Counter.builder(COMMANDES_CREEES)
                .description("Commandes creees")
                .register(registre);
        Counter.builder(COMPENSATIONS_ECHOUEES)
                .description("Restitutions de stock echouees : du stock reste retenu")
                .register(registre);
        Counter.builder(REFUS_DE_STOCK)
                .description("Passages en caisse refuses faute de stock")
                .register(registre);

        this.dureeCaisse = Timer.builder(DUREE_CAISSE)
                .description("Temps d'un passage en caisse, bout en bout")
                .publishPercentileHistogram()
                .register(registre);
    }

    public void commandeCreee() {
        registre.counter(COMMANDES_CREEES).increment();
    }

    public void transition(String versStatut, String role) {
        Counter.builder(TRANSITIONS)
                .tag("vers", versStatut)
                .tag("role", role)
                .description("Transitions de commande rendues")
                .register(registre)
                .increment();
    }

    /** Du stock est reste retenu sans commande. Voir la javadoc de la constante. */
    public void compensationEchouee() {
        registre.counter(COMPENSATIONS_ECHOUEES).increment();
    }

    public void refusDeStock() {
        registre.counter(REFUS_DE_STOCK).increment();
    }

    public void retour(String sens) {
        Counter.builder(RETOURS)
                .tag("sens", sens)
                .description("Retours, par sens de decision")
                .register(registre)
                .increment();
    }

    public Timer dureeDeLaCaisse() {
        return dureeCaisse;
    }

    /** Mis a jour par le releveur periodique, pas a chaque requete. */
    public void files(int aPayer, int aArbitrer, int aCloturer) {
        commandesAPayer.set(aPayer);
        retoursAArbitrer.set(aArbitrer);
        commandesACloturer.set(aCloturer);
    }
}
