package com.abdatytch.market_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Les metriques de ce service.
 *
 * <h2>Chaque metrique repond a une QUESTION d'exploitation</h2>
 *
 * Et aucune n'existe parce qu'elle etait facile a poser. Ce depot a deja porte
 * des alertes muettes — posees sur des noms que personne n'emettait — et
 * {@code MetriquesDesAlertesTest} verifie desormais que chaque alerte et chaque
 * panneau visent une metrique qui EXISTE.
 *
 * <h2>Les compteurs sont crees a l'avance</h2>
 *
 * Un compteur enregistre au premier incrementage n'apparait pas dans Prometheus
 * avant le premier evenement. « Zero refus » et « la metrique n'existe pas » se
 * ressemblent alors beaucoup trop, et c'est precisement sur zero qu'on veut
 * pouvoir alerter.
 */
@Component
public class MarketMetrics {

    /** La file de moderation. Un article en attente est un article invisible. */
    public static final String FILE_DE_MODERATION = "market.moderation.file";

    /** Les decisions rendues, par nature et par sens. */
    public static final String DECISIONS = "market.moderation.decisions.total";

    /**
     * Les refus pour portee insuffisante : la regle de visibilite au travail.
     *
     * <p>Souligne et non tiret dans {@code hors_portee}. Micrometer accepte les
     * deux et les rend identiques cote Prometheus, mais la convention du depot
     * est le point et le souligne — et {@code MetriquesDesAlertesTest} ne
     * reconnait un litteral de metrique qu'a cette forme. Un nom avec tiret est
     * donc declare FANTOME, alors qu'il est bien emis.
     */
    public static final String HORS_PORTEE = "market.catalogue.hors_portee.total";

    /** Le temps de rendu d'une page de catalogue, bout en bout. */
    public static final String DUREE_CATALOGUE = "market.catalogue.duree";

    /** Les photos demandees que media-service n'a pas pu signer. */
    public static final String PHOTOS_NON_SIGNEES = "market.photos.non_signees.total";

    private final MeterRegistry registre;

    /**
     * L'unique instance du chronometre, enregistree ici et nulle part
     * ailleurs.
     *
     * <p>Micrometer rend le meter DEJA enregistre pour un identifiant donne
     * et <b>ignore</b> la configuration du second appel. Deux
     * {@code Timer.builder(DUREE_CATALOGUE)} dans cette classe — un au
     * constructeur, un a l'accesseur — faisaient donc gagner le premier :
     * poser {@code publishPercentileHistogram()} sur le second n'avait
     * aucun effet, sans la moindre erreur pour le dire. Un seul
     * enregistrement supprime la question.
     */
    private final Timer dureeCatalogue;

    private final AtomicInteger boutiquesEnAttente = new AtomicInteger();
    private final AtomicInteger produitsEnAttente = new AtomicInteger();
    private final AtomicInteger avisEnAttente = new AtomicInteger();

    public MarketMetrics(MeterRegistry registre) {
        this.registre = registre;

        registre.gauge(FILE_DE_MODERATION, java.util.List.of(
                io.micrometer.core.instrument.Tag.of("nature", "boutique")),
                boutiquesEnAttente, AtomicInteger::get);
        registre.gauge(FILE_DE_MODERATION, java.util.List.of(
                io.micrometer.core.instrument.Tag.of("nature", "produit")),
                produitsEnAttente, AtomicInteger::get);
        registre.gauge(FILE_DE_MODERATION, java.util.List.of(
                io.micrometer.core.instrument.Tag.of("nature", "avis")),
                avisEnAttente, AtomicInteger::get);

        // Crees a l'avance, a zero : voir la javadoc de la classe.
        Counter.builder(HORS_PORTEE)
                .description("Acces refuses parce que hors de la portee du demandeur")
                .register(registre);
        Counter.builder(PHOTOS_NON_SIGNEES)
                .description("Photos rattachees que media-service n'a pas pu signer")
                .register(registre);
        this.dureeCatalogue = Timer.builder(DUREE_CATALOGUE)
                .description("Temps de rendu d'une page de catalogue, bout en bout")
                .publishPercentileHistogram()
                .register(registre);
    }

    public void decisionDeModeration(String nature, String sens) {
        Counter.builder(DECISIONS)
                .tag("nature", nature)
                .tag("sens", sens)
                .description("Decisions de moderation rendues")
                .register(registre)
                .increment();
    }

    /** Un acces refuse faute de portee. Sans etiquette : voir le commentaire. */
    public void horsPortee() {
        // AUCUNE etiquette portant l'identifiant demande, ni le produit vise.
        // Une metrique est conservee longtemps et exportee largement ; y
        // inscrire ce que quelqu'un a tente de voir en ferait un journal
        // d'activite personnel.
        registre.counter(HORS_PORTEE).increment();
    }

    public void photosNonSignees(int combien) {
        if (combien > 0) {
            registre.counter(PHOTOS_NON_SIGNEES).increment(combien);
        }
    }

    /**
     * Le chronometre de rendu d'une page de catalogue.
     *
     * <h3>{@code publishPercentileHistogram} n'est pas un reglage de confort</h3>
     *
     * Un {@code Timer} ordinaire publie trois series : {@code _count},
     * {@code _sum} et {@code _max}. Il ne publie <b>aucun</b>
     * {@code _bucket} — et {@code histogram_quantile} ne travaille que sur des
     * buckets.
     *
     * <p>Mesure du 2026-11-13 sur le service en marche : le panneau
     * « Rendu d'une page de catalogue (centiles) » demandait trois centiles sur
     * {@code market_catalogue_duree_seconds_bucket}, et l'alerte
     * {@code CatalogueRalenti} comparait le centile 95 du jour a celui de la
     * veille sur la meme serie. Cette serie n'existait pas. Le panneau affichait
     * « No data », et surtout l'alerte ne pouvait <b>jamais</b> se declencher :
     * {@code histogram_quantile} sur un ensemble vide ne rend rien, et une
     * regle qui ne rend rien ne passe jamais a {@code firing}.
     *
     * <p>Le garde-fou {@code MetriquesDesAlertesTest} ne l'avait pas vu parce
     * qu'il verifie qu'un NOM de metrique existe dans le code, pas son mode de
     * publication. Un nom present et des buckets absents se lisent pareil pour
     * lui. C'est la raison d'etre du test ajoute le meme jour, qui exige qu'une
     * metrique interrogee en {@code _bucket} soit declaree avec un histogramme.
     *
     * <h3>Ce que cela coute</h3>
     *
     * Les buckets par defaut de Micrometer multiplient les series de cette
     * metrique. C'est le prix d'un centile : une moyenne
     * ({@code _sum / _count}) ne dit rien de la minorite d'utilisateurs qui
     * attend, et c'est elle qu'un catalogue ralenti fait fuir.
     */
    public Timer dureeDuCatalogue() {
        return dureeCatalogue;
    }

    /** Mis a jour par le releveur periodique, pas a chaque requete. */
    public void filesDeModeration(int boutiques, int produits, int avis) {
        boutiquesEnAttente.set(boutiques);
        produitsEnAttente.set(produits);
        avisEnAttente.set(avis);
    }
}
