package com.abdatytch.gateway_service.service;

import com.abdatytch.commons.geo.LocalisationIp;
import com.abdatytch.commons.security.ClesRedisSecurite;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Applique le refus par pays. <b>Ne décide rien.</b>
 *
 * <h2>Ce que cette classe faisait, et ce qu'elle fait désormais</h2>
 *
 * Elle comptait 553 lignes et mêlait trois préoccupations :
 *
 * <ol>
 *   <li>la <b>lecture</b> MaxMind — chargement de trois bases, extraction des
 *       champs. Partie dans {@code platform-commons}
 *       ({@link LocalisationIp}), où media-service en portait un second
 *       exemplaire, avec une autre version de la bibliothèque ;</li>
 *   <li>la <b>décision</b> — quels pays sont refusés, avec les écritures
 *       {@code blockCountry} et {@code unblockCountry} et le contrôleur
 *       d'administration qui les exposait. Partie dans
 *       {@code security-service} : la passerelle achemine et applique, décider
 *       quels pays sont refusés est une politique ;</li>
 *   <li>l'<b>application</b> — ce qui reste ici, et c'est tout ce dont
 *       {@code GeoLocationWebFilter} a besoin.</li>
 * </ol>
 *
 * <h2>Cette classe n'écrit plus dans Redis, et c'est important</h2>
 *
 * Elle ensemençait l'ensemble des pays refusés au démarrage
 * ({@code initializeDefaultBlockedCountries}, appelé <b>deux fois</b> dans
 * {@code init()} — une fois de trop). Or {@code security-service} remplace cet
 * ensemble à chaque publication, depuis sa table.
 *
 * <p>Deux écrivains pour un même ensemble : l'ensemencement aurait fait
 * réapparaître un pays à chaque redémarrage de la passerelle, et la publication
 * l'aurait fait disparaître. Un pays refusé par intermittence, sans qu'aucune
 * erreur ne le signale — exactement le défaut qui a fait qu'un verrouillage
 * administrateur ne verrouillait personne dans ce dépôt.
 *
 * <p>Le pays ensemencé par défaut est désormais une ligne de la migration de
 * {@code security-service} : visible, auditable, et retirable par l'API.
 */
@Service
public class GeoLocationService {

    private static final Logger log = LoggerFactory.getLogger(GeoLocationService.class);

    @Autowired
    @Qualifier("reactiveRedisTemplate")
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Value("${geolocation.database.path:classpath:GeoLite2-Country.mmdb}")
    private String cheminBasePays;

    @Value("${geolocation.enabled:false}")
    private boolean geoLocationActive;

    private LocalisationIp localisation;

    private Counter consultations;
    private Counter requetesRefusees;
    private Counter echecsDeConsultation;

    @PostConstruct
    public void init() {
        consultations = Counter.builder("geo_requests_total")
                .description("Total des consultations de geolocalisation")
                .register(meterRegistry);
        requetesRefusees = Counter.builder("geo_blocked_requests_total")
                .description("Total des requetes refusees par pays")
                .register(meterRegistry);
        echecsDeConsultation = Counter.builder("geo_lookup_errors_total")
                .description("Total des echecs de consultation MaxMind")
                .register(meterRegistry);

        if (!geoLocationActive) {
            log.info("Geolocalisation desactivee (geolocation.enabled=false)");
            localisation = LocalisationIp.inactive();
            return;
        }

        // Seule la base Pays est chargée : la passerelle n'expose plus de
        // détails d'adresse, ce contrôleur étant parti dans security-service.
        // Charger les bases Ville et ASN coûterait des dizaines de mégaoctets
        // pour une fonction que ce module n'a plus.
        //
        // Le rapporteur d'échecs alimente geo_lookup_errors_total : sans lui, la
        // métrique aurait disparu en silence lors de l'extraction de la lecture.
        localisation = LocalisationIp.charger(cheminBasePays, null, null)
                .enRapportantLesEchecs(erreur -> echecsDeConsultation.increment());

        if (!localisation.estActive()) {
            log.warn("Base MaxMind indisponible : aucun refus par pays ne sera applique");
        }
    }

    /**
     * La géolocalisation est-elle utilisable ?
     *
     * <p>Lu par {@code GeoLocationWebFilter} avant toute consultation : sans
     * base, il n'y a rien à appliquer et le filtre passe la main sans frais.
     */
    public boolean isEnabled() {
        return geoLocationActive && localisation != null && localisation.estActive();
    }

    /**
     * Code pays d'une adresse, <b>sans I/O</b>.
     *
     * <p>La base Pays est chargée entièrement en mémoire par
     * {@link LocalisationIp} : la consultation est du calcul, donc sans danger
     * sur une EventLoop. Avant l'extraction, elle lisait un fichier projeté en
     * mémoire depuis un {@code Mono.fromCallable} <b>sans</b>
     * {@code subscribeOn} — donc une lecture disque possible sur une EventLoop,
     * à chaque requête passant par le filtre.
     */
    public Mono<String> getCountryCode(String adresseIp) {
        if (localisation == null) {
            return Mono.just(LocalisationIp.INCONNU);
        }
        return localisation.codePays(adresseIp)
                .doOnNext(this::compterLaConsultation);
    }

    /**
     * Compte la consultation, par pays.
     *
     * <p>Les adresses locales ne sont pas comptées : elles viennent des sondes
     * et du trafic interne, et les mêler au trafic public rendrait la courbe par
     * pays illisible.
     */
    private void compterLaConsultation(String codePays) {
        if (LocalisationIp.LOCAL.equals(codePays)) {
            return;
        }
        consultations.increment();
        // L'étiquette est BORNÉE : un code ISO ou « UNKNOWN », jamais une valeur
        // venue de l'appelant. Une étiquette non bornée ferait exploser la
        // cardinalité Prometheus et rendrait la métrique inexploitable.
        Counter.builder("geo_requests_by_country_total")
                .tag("country", codePays)
                .description("Consultations par pays")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Ce pays est-il refusé ?
     *
     * <p>Lecture seule de l'ensemble publié par {@code security-service}.
     */
    public Mono<Boolean> isCountryBlocked(String codePays) {
        if (!isEnabled() || LocalisationIp.INCONNU.equals(codePays)
                || LocalisationIp.LOCAL.equals(codePays)) {
            // Un pays inconnu n'est pas refusé : refuser ce qu'on ne sait pas
            // identifier bloquerait toute adresse absente de la base,
            // c'est-à-dire les plages récentes.
            return Mono.just(false);
        }
        return redisTemplate.opsForSet()
                .isMember(ClesRedisSecurite.ENSEMBLE_PAYS_BLOQUES, codePays)
                // defaultIfEmpty sur le Mono TYPÉ : une absence de réponse vaut
                // « pas refusé ». Sans ce défaut, la chaîne se terminerait vide
                // et le filtre ne saurait ni autoriser ni refuser.
                .defaultIfEmpty(false)
                .onErrorResume(erreur -> {
                    // Une panne de Redis laisse passer, comme
                    // IpBlacklistWebFilter et MaintenanceWebFilter. Refuser en
                    // cas de doute ferait d'une panne de cache un refus de tout
                    // le trafic.
                    log.warn("Ensemble des pays refuses illisible ({}) : la requete passe",
                            erreur.getClass().getSimpleName());
                    return Mono.just(false);
                });
    }

    /** Cette adresse est-elle refusée, par son pays ? */
    public Mono<Boolean> isIpBlocked(String adresseIp) {
        return getCountryCode(adresseIp)
                .flatMap(codePays -> isCountryBlocked(codePays)
                        .doOnNext(refuse -> {
                            if (Boolean.TRUE.equals(refuse)) {
                                requetesRefusees.increment();
                                Counter.builder("geo_blocked_by_country_total")
                                        .tag("country", codePays)
                                        .description("Requetes refusees par pays")
                                        .register(meterRegistry)
                                        .increment();
                            }
                        }));
    }
}
