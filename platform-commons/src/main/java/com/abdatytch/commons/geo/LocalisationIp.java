package com.abdatytch.commons.geo;

import com.maxmind.db.Reader;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.CountryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

/**
 * Consultation MaxMind : <b>une seule</b> implementation pour la plateforme.
 *
 * <h2>Ce que cette classe remplace</h2>
 *
 * Deux implementations independantes lisaient les memes fichiers {@code .mmdb} :
 *
 * <ul>
 *   <li>{@code gateway-service/GeoLocationService} — trois lecteurs (pays,
 *       ville, ASN), 553 lignes melant la consultation et le magasin des pays
 *       bloques ;</li>
 *   <li>{@code media-service/GeoLocationServiceImpl} — un lecteur (pays).</li>
 * </ul>
 *
 * Deux codes a corriger pour un meme defaut, et c'est exactement ce qui s'est
 * produit : le correctif de mode de chargement decrit ci-dessous n'existait dans
 * aucun des deux.
 *
 * <h2>Le defaut de fond : une lecture de fichier sur l'EventLoop</h2>
 *
 * {@code new DatabaseReader.Builder(fichier).build()} utilise par defaut
 * {@link Reader.FileMode#MEMORY_MAPPED} : la base reste sur le disque, projetee
 * en memoire. Chaque consultation peut donc declencher un defaut de page, donc
 * une lecture disque.
 *
 * <p>Or les deux implementations appelaient {@code Mono.fromCallable(...)}
 * <b>sans</b> {@code subscribeOn} : la consultation tournait sur le fil qui
 * souscrit, c'est-a-dire une EventLoop Netty — et sur la passerelle, a
 * <b>chaque</b> requete passant par le filtre de geolocalisation. Netty alloue
 * une EventLoop par cœur ; une lecture disque y bloque tout ce que ce fil sert.
 *
 * <h2>Deux bases, deux traitements, et pourquoi</h2>
 *
 * <ul>
 *   <li><b>Pays</b> — chemin chaud, consulte a chaque requete. Chargee en
 *       {@link Reader.FileMode#MEMORY} : la base entiere va en memoire, la
 *       consultation devient du calcul pur, et elle est alors reellement sans
 *       danger sur une EventLoop. Le cout est environ dix megaoctets de
 *       memoire — moins cher qu'un saut de fil par requete ;</li>
 *   <li><b>Ville et ASN</b> — chemin d'administration, consulte a la demande.
 *       Projetees en memoire, parce que la base Ville pese plusieurs dizaines
 *       de megaoctets et que la charger entierement pour un usage occasionnel
 *       serait du gaspillage. Leurs consultations partent donc sur
 *       {@code boundedElastic}.</li>
 * </ul>
 *
 * <p>Ce n'est pas une optimisation : c'est la seule façon d'avoir les deux
 * proprietes a la fois — pas d'I/O sur le chemin chaud, pas de memoire gaspillee
 * sur le chemin froid.
 *
 * <h2>Pourquoi ce n'est pas un service Spring</h2>
 *
 * La classe est instanciable et sans annotation. {@code geo-service} existe dans
 * le depot comme un squelette d'un seul fichier, et en faire un service appele
 * par le reseau mettrait un saut reseau sur le chemin de chaque requete — pour
 * une fonction qui lit un fichier local. Chaque module l'instancie dans sa
 * propre configuration.
 */
public final class LocalisationIp {

    private static final Logger log = LoggerFactory.getLogger(LocalisationIp.class);

    /** Reponse quand la consultation est impossible ou la base absente. */
    public static final String INCONNU = "UNKNOWN";

    /** Reponse pour une adresse privee : il n'y a pas de pays a trouver. */
    public static final String LOCAL = "LOCAL";

    private final boolean active;
    private final DatabaseReader lecteurPays;
    private final DatabaseReader lecteurVille;
    private final DatabaseReader lecteurAsn;

    /**
     * Rapporteur d'echecs de consultation, facultatif.
     *
     * <h2>Pourquoi un rappel plutot qu'un compteur ici</h2>
     *
     * {@code platform-commons} ne depend pas de Micrometer, et l'y faire
     * dependre imposerait ce choix a tous les modules. L'appelant qui tient des
     * metriques les alimente donc lui-meme.
     *
     * <p>Sans ce rappel, la metrique {@code geo_lookup_errors_total} de la
     * passerelle aurait disparu en silence lors de l'extraction de la lecture :
     * retirer un signal d'observabilite sans le dire est precisement ce que ce
     * depot a corrige sur les alertes muettes.
     *
     * <p>Distingue d'une adresse <b>absente</b> de la base, qui n'est pas une
     * erreur : c'est le cas courant pour une adresse neuve, et la compter comme
     * un echec rendrait le compteur illisible.
     */
    private final java.util.function.Consumer<Throwable> surEchecDeConsultation;

    /**
     * Resultat d'une consultation detaillee.
     *
     * <p>Un enregistrement et non un DTO d'API : chaque service garde sa propre
     * reponse HTTP, parce que la passerelle et media-service n'exposent pas le
     * meme contrat. Ce qui est partage est la <b>lecture</b>, pas la forme
     * publiee.
     */
    public record Details(
            String adresseIp,
            String pays,
            String codePays,
            String ville,
            String region,
            String codePostal,
            Double latitude,
            Double longitude,
            String fuseauHoraire,
            String organisation,
            String numeroAsn) {
    }

    private LocalisationIp(boolean active, DatabaseReader lecteurPays,
                           DatabaseReader lecteurVille, DatabaseReader lecteurAsn,
                           java.util.function.Consumer<Throwable> surEchecDeConsultation) {
        this.active = active;
        this.lecteurPays = lecteurPays;
        this.lecteurVille = lecteurVille;
        this.lecteurAsn = lecteurAsn;
        // Jamais nul : un rappel absent devient un rappel qui ne fait rien,
        // plutot qu'un test de nullite repete a chaque appel.
        this.surEchecDeConsultation = surEchecDeConsultation != null
                ? surEchecDeConsultation
                : erreur -> { };
    }

    /** Rend une copie qui rapporte ses echecs de consultation. */
    public LocalisationIp enRapportantLesEchecs(
            java.util.function.Consumer<Throwable> surEchec) {
        return new LocalisationIp(active, lecteurPays, lecteurVille, lecteurAsn, surEchec);
    }

    /** Instance inactive : toute consultation rend {@link #INCONNU}. */
    public static LocalisationIp inactive() {
        return new LocalisationIp(false, null, null, null, null);
    }

    /**
     * Charge les bases disponibles.
     *
     * <p>Une base absente n'est pas une erreur : la plateforme doit demarrer
     * sans les fichiers MaxMind, qui ne sont pas versionnes. Seule la base
     * <b>Pays</b> conditionne l'activation — sans elle, il n'y a rien a
     * consulter.
     *
     * @param cheminPays obligatoire pour que la localisation soit active
     * @param cheminVille facultatif ; son absence prive des details de ville
     * @param cheminAsn facultatif ; son absence prive de l'organisation
     */
    public static LocalisationIp charger(String cheminPays, String cheminVille,
                                         String cheminAsn) {
        File fichierPays = new File(cheminPays);
        if (!fichierPays.exists()) {
            // Le lien de telechargement n'est PAS journalise : la consigne du
            // depot est qu'aucun lien ne figure dans le code ni dans les
            // journaux. Le nom du fichier attendu suffit a agir.
            log.warn("Base MaxMind Pays absente ({}) : la localisation reste inactive",
                    cheminPays);
            return inactive();
        }

        try {
            // FileMode.MEMORY sur la base Pays : voir la javadoc de la classe.
            // C'est ce qui rend la consultation du chemin chaud sans I/O, donc
            // sans danger sur une EventloopNetty.
            DatabaseReader pays = new DatabaseReader.Builder(fichierPays)
                    .fileMode(Reader.FileMode.MEMORY)
                    .build();
            log.info("Base MaxMind Pays chargee en memoire ({})", cheminPays);

            DatabaseReader ville = charger("Ville", cheminVille);
            DatabaseReader asn = charger("ASN", cheminAsn);

            return new LocalisationIp(true, pays, ville, asn, null);
        } catch (IOException e) {
            // La classe de l'erreur, pas son message : un message d'I/O contient
            // le chemin complet, donc l'arborescence du conteneur.
            log.error("Base MaxMind Pays illisible ({}) : la localisation reste inactive",
                    e.getClass().getSimpleName());
            return inactive();
        }
    }

    /**
     * Charge une base secondaire, projetee en memoire.
     *
     * <p>Rend {@code null} si elle est absente ou illisible : ces bases
     * n'enrichissent qu'un chemin d'administration, et leur absence ne doit pas
     * priver la plateforme de la consultation du pays.
     */
    private static DatabaseReader charger(String nom, String chemin) {
        if (chemin == null || chemin.isBlank()) {
            return null;
        }
        File fichier = new File(chemin);
        if (!fichier.exists()) {
            log.info("Base MaxMind {} absente : les details correspondants seront vides",
                    nom);
            return null;
        }
        try {
            DatabaseReader lecteur = new DatabaseReader.Builder(fichier).build();
            log.info("Base MaxMind {} chargee ({})", nom, chemin);
            return lecteur;
        } catch (IOException e) {
            log.warn("Base MaxMind {} illisible ({})", nom, e.getClass().getSimpleName());
            return null;
        }
    }

    public boolean estActive() {
        return active;
    }

    /**
     * Code pays ISO d'une adresse. <b>Sans I/O</b>, donc utilisable depuis une
     * EventLoop.
     *
     * <p>Rend {@link #LOCAL} pour une adresse privee : chercher le pays d'une
     * adresse de reseau local n'a pas de sens, et le compter comme
     * {@link #INCONNU} melangerait deux situations differentes dans les
     * metriques.
     *
     * <p>Jamais {@code null}, jamais un {@code Mono} vide. Une chaine
     * d'autorisation qui recevrait un {@code Mono} vide se terminerait sans
     * decision — ni autorisee ni refusee — et ce depot a corrige cette confusion
     * une dizaine de fois.
     */
    public Mono<String> codePays(String adresseIp) {
        if (estUneAdressePrivee(adresseIp)) {
            return Mono.just(LOCAL);
        }
        if (!active) {
            return Mono.just(INCONNU);
        }
        // Mono.fromCallable SANS subscribeOn, et c'est correct ici parce que la
        // base Pays est entierement en memoire : la consultation est du calcul.
        // Ce serait faux pour les bases projetees — voir details().
        return Mono.fromCallable(() -> {
            try {
                CountryResponse reponse = lecteurPays.country(
                        InetAddress.getByName(adresseIp));
                String code = reponse.getCountry().getIsoCode();
                return code != null ? code : INCONNU;
            } catch (com.maxmind.geoip2.exception.AddressNotFoundException absente) {
                // Adresse absente de la base : le cas COURANT pour une adresse
                // neuve, et pas une erreur. La compter comme un echec rendrait
                // le compteur illisible.
                return INCONNU;
            } catch (Exception e) {
                // Echec reel : adresse illisible, base corrompue, I/O. Rapporte
                // pour que l'appelant puisse le compter.
                surEchecDeConsultation.accept(e);
                return INCONNU;
            }
        });
    }

    /**
     * Details d'une adresse, pour l'administration.
     *
     * <p><b>Sur {@code boundedElastic}</b> : les bases Ville et ASN sont
     * projetees en memoire, donc une consultation peut declencher une lecture
     * disque. La laisser sur l'EventLoop bloquerait un fil que Netty alloue a
     * raison d'un par cœur.
     */
    public Mono<Details> details(String adresseIp) {
        if (!active) {
            return Mono.just(new Details(adresseIp, null, INCONNU,
                    null, null, null, null, null, null, null, null));
        }
        return Mono.fromCallable(() -> lireLesDetails(adresseIp))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Details lireLesDetails(String adresseIp) {
        String pays = null;
        String codePays = INCONNU;
        String ville = null;
        String region = null;
        String codePostal = null;
        Double latitude = null;
        Double longitude = null;
        String fuseau = null;
        String organisation = null;
        String asn = null;

        try {
            InetAddress adresse = InetAddress.getByName(adresseIp);

            if (lecteurVille != null) {
                CityResponse reponse = lecteurVille.city(adresse);
                pays = reponse.getCountry().getName();
                codePays = reponse.getCountry().getIsoCode() != null
                        ? reponse.getCountry().getIsoCode() : INCONNU;
                ville = reponse.getCity().getName();
                region = reponse.getMostSpecificSubdivision().getName();
                codePostal = reponse.getPostal().getCode();
                latitude = reponse.getLocation().getLatitude();
                longitude = reponse.getLocation().getLongitude();
                fuseau = reponse.getLocation().getTimeZone();
            } else {
                CountryResponse reponse = lecteurPays.country(adresse);
                pays = reponse.getCountry().getName();
                codePays = reponse.getCountry().getIsoCode() != null
                        ? reponse.getCountry().getIsoCode() : INCONNU;
            }

            if (lecteurAsn != null) {
                AsnResponse reponse = lecteurAsn.asn(adresse);
                organisation = reponse.getAutonomousSystemOrganization();
                asn = reponse.getAutonomousSystemNumber() != null
                        ? "AS" + reponse.getAutonomousSystemNumber() : null;
            }
        } catch (Exception e) {
            // Details partiels plutot qu'une erreur : l'administrateur qui
            // consulte une adresse absente de la base doit voir ce qu'on sait,
            // pas un refus.
            log.debug("Details MaxMind indisponibles ({})", e.getClass().getSimpleName());
        }

        return new Details(adresseIp, pays, codePays, ville, region, codePostal,
                latitude, longitude, fuseau, organisation, asn);
    }

    /**
     * Adresse de reseau local ou de bouclage.
     *
     * <h2>Pourquoi {@code InetAddress} et non une liste de prefixes</h2>
     *
     * Les deux implementations remplacees enumeraient les prefixes a la main, et
     * celle de la passerelle listait {@code 172.16.} a {@code 172.31.} en
     * seize tests {@code startsWith} — une ligne par sous-reseau. Outre la
     * longueur, cette forme rate {@code 172.016.} et toute notation
     * equivalente, et une comparaison de chaines ne reconnait aucune adresse
     * IPv6 privee.
     *
     * <p>{@code InetAddress} tranche sur la structure de l'adresse, pas sur son
     * ecriture.
     *
     * <h2>Pourquoi cette methode est publique</h2>
     *
     * Les deux implementations remplacees portaient chacune leur version, et
     * les deux etaient fausses — l'une listait seize prefixes a la main, l'autre
     * ne testait que deux cas. Exposer celle-ci evite qu'une troisieme
     * apparaisse le jour ou un module aura besoin du meme predicat.
     *
     * <p>Elle est utile en dehors de {@link #codePays(String)} : un appelant qui
     * construit sa propre reponse pour une adresse locale — comme media-service,
     * qui renseigne « Local ISP » et « Local Network » — a besoin de savoir,
     * pas seulement de recevoir {@link #LOCAL}.
     */
    public static boolean estUneAdressePrivee(String adresseIp) {
        if (adresseIp == null || adresseIp.isBlank()) {
            return false;
        }
        try {
            InetAddress adresse = InetAddress.getByName(adresseIp);
            return adresse.isLoopbackAddress()
                    || adresse.isSiteLocalAddress()
                    || adresse.isLinkLocalAddress()
                    || adresse.isAnyLocalAddress();
        } catch (Exception e) {
            // Une adresse qu'on ne sait pas lire n'est pas declaree locale :
            // la traiter comme locale la ferait echapper aux controles de pays.
            return false;
        }
    }
}
